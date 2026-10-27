package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.ask.CitationValidator;
import edu.learnsphere.core.db.ChatRepository;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatThread;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.Enrolment;
import edu.learnsphere.core.model.MessageStatus;
import edu.learnsphere.core.service.Answer;
import edu.learnsphere.core.service.AnswerListener;
import edu.learnsphere.core.service.AskPhase;
import edu.learnsphere.core.service.AskService;
import edu.learnsphere.core.service.ProfileContext;
import edu.learnsphere.core.service.RecentQuestion;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.SettingsService;
import edu.learnsphere.core.service.ThreadSummary;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.spi.GroundedAnswer;
import edu.learnsphere.core.spi.GroundedAnswerer;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.Reranker;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.spi.SearchQuery;
import edu.learnsphere.core.util.Cancellable;
import edu.learnsphere.core.util.CancellationToken;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AskService}: questions answered only from a course's material, with page citations.
 *
 * <p>{@link #ask} checks the question, the conversation and access (the course was created here, or the learner
 * is enrolled) before returning; everything else runs on a background thread and reports to the
 * {@link AnswerListener}:
 *
 * <ol>
 *   <li>the question is stored (the first question of an untitled conversation becomes its title);</li>
 *   <li>{@link AskPhase#SEARCHING}: the question is embedded as a {@link EmbedKind#QUERY} and searched in the
 *       course with {@link SearchQuery#forCourse} (keyword top 40 + vector top 40, fusion k = 60, keep 24); hits
 *       from PDFs that are not ready are dropped;</li>
 *   <li>the {@link Reranker} scores the hits; up to {@value #MAX_PASSAGES} passages scoring at least the
 *       {@link SettingKeys#EVIDENCE_THRESHOLD} setting become the evidence, numbered {@code [1]…[n]}. With none,
 *       the answer is the insufficient-evidence card and the answerer is never asked;</li>
 *   <li>{@link AskPhase#WRITING}: the {@link GroundedAnswerer} streams the answer (tokens go to
 *       {@link AnswerListener#onToken});</li>
 *   <li>{@link CitationValidator} keeps only markers that point at a passage; an answer without a valid
 *       citation, or one the answerer marked as not covered, becomes the insufficient-evidence result;</li>
 *   <li>the answer is stored with its citations ({@link MessageStatus#COMPLETE} or
 *       {@link MessageStatus#INSUFFICIENT}), then {@link AnswerListener#onAnswer} or
 *       {@link AnswerListener#onInsufficientEvidence}.</li>
 * </ol>
 *
 * <p>Cancelling the returned handle stops the answer: the partial answer is stored as
 * {@link MessageStatus#CANCELLED} and the listener gets {@link AnswerListener#onCancelled}. Failures are stored as
 * {@link MessageStatus#ERROR} with the sentence the listener gets in {@link AnswerListener#onError}. The study
 * assistant is never waited for: while it is starting, the learner is told so at once. Answers run at
 * {@link edu.learnsphere.core.spi.ChatPriority#INTERACTIVE}, which the answerer's implementation uses.
 *
 * <p>Thread-safe. {@link #close()} stops answers in progress and the background threads.
 */
public final class AskServiceImpl implements AskService, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(AskServiceImpl.class);

    /** Most passages given to the answerer. */
    public static final int MAX_PASSAGES = 6;

    /** The insufficient-evidence card. */
    public static final UserMessage NOT_COVERED =
            UserMessage.of("Your material doesn't cover this. Try rephrasing, or add a document that does.");

    /** The insufficient-evidence card when the course has no PDF ready to answer from. */
    public static final UserMessage NOTHING_READY =
            UserMessage.of("This course has no PDFs ready yet. Add one, or wait until its import finishes.");

    static final UserMessage THREAD_NOT_FOUND = UserMessage.of("This conversation isn't in the course.");
    static final UserMessage BLANK_QUESTION = UserMessage.of("Type a question first.");
    static final UserMessage BLANK_TITLE = UserMessage.of("Give the conversation a name.");
    static final UserMessage NOT_STUDYING = UserMessage.of("Enrol in this course to ask questions about it.");
    static final UserMessage MATERIAL_PREPARING =
            UserMessage.of("Your material is still being prepared. Try again in a moment.");

    /** Longest automatic title, in characters. */
    static final int AUTO_TITLE_LENGTH = 60;
    private static final int MAX_TITLE_LENGTH = 120;
    private static final int PREVIEW_LENGTH = 200;
    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(5);

    private final Repositories repos;
    private final Clock clock;
    private final SettingsService settings;
    private final SearchIndex index;
    private final Embedder embedder;
    private final Reranker reranker;
    private final GroundedAnswerer answerer;
    private final ExecutorService executor = Executors.newCachedThreadPool(
            Thread.ofPlatform().name("learnsphere-ask-", 0).daemon(true).factory());
    private final Set<CancellationToken> running = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AskServiceImpl(Repositories repos, SettingsService settings, SearchIndex index, Embedder embedder,
                          Reranker reranker, GroundedAnswerer answerer) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.clock = repos.clock();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.index = Objects.requireNonNull(index, "index");
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.reranker = Objects.requireNonNull(reranker, "reranker");
        this.answerer = Objects.requireNonNull(answerer, "answerer");
    }

    /** The service of an opened profile, from the workspace's capabilities, index and settings. */
    public static AskServiceImpl create(ProfileContext context, Capabilities capabilities, SearchIndex index,
                                        SettingsService settings) {
        return new AskServiceImpl(context.repositories(), settings, index, capabilities.embedder(), capabilities.reranker(),
                capabilities.answerer());
    }

    // ---- conversations -----------------------------------------------------------------------------

    @Override
    public List<ThreadSummary> threads(String courseId) {
        repos.courses().get(courseId);
        return repos.chats().listThreadRows(courseId).stream()
                .map(row -> new ThreadSummary(row.thread().id(), row.thread().courseId(), row.thread().title(),
                        row.messageCount(), PipelineText.preview(row.lastMessage(), PREVIEW_LENGTH), row.lastMessageAt(),
                        row.thread().updatedAt()))
                .toList();
    }

    @Override
    public ChatThread createThread(String courseId, String title) {
        Course course = repos.courses().get(courseId);
        requireAccess(course);
        String cleaned = title == null ? "" : PipelineText.shorten(title, MAX_TITLE_LENGTH);
        ChatThread thread = ChatThread.create(courseId, cleaned, clock.millis());
        repos.chats().insertThread(thread);
        return thread;
    }

    @Override
    public void renameThread(String threadId, String title) {
        if (title == null || title.isBlank()) {
            throw new InvalidInputException(BLANK_TITLE, "Blank conversation title");
        }
        thread(threadId);
        repos.chats().renameThread(threadId, PipelineText.shorten(title, MAX_TITLE_LENGTH));
    }

    @Override
    public void deleteThread(String threadId) {
        thread(threadId);
        repos.chats().deleteThread(threadId);
    }

    @Override
    public List<ChatMessage> messages(String threadId) {
        thread(threadId);
        return repos.chats().listMessages(threadId);
    }

    @Override
    public List<RecentQuestion> recentQuestions(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<String, Optional<Course>> courses = new HashMap<>();
        List<RecentQuestion> result = new ArrayList<>();
        for (ChatRepository.Exchange exchange : repos.chats().recentExchanges(null, Math.max(limit * 4, limit + 20))) {
            Optional<Course> course = courses.computeIfAbsent(exchange.thread().courseId(),
                    id -> repos.courses().find(id).filter(this::showsOnHome));
            if (course.isEmpty()) {
                continue;
            }
            ChatMessage question = exchange.question();
            ChatMessage answer = exchange.answer();
            result.add(new RecentQuestion(exchange.thread().id(), course.get().id(), course.get().title(),
                    course.get().coverHue(), question.content(),
                    PipelineText.shorten(PipelineText.withoutCitations(answer.content()), PREVIEW_LENGTH),
                    answer.citations(), question.createdAt()));
            if (result.size() == limit) {
                break;
            }
        }
        return List.copyOf(result);
    }

    /** Courses whose questions appear on Home: not archived, and not left (imported courses must be enrolled). */
    private boolean showsOnHome(Course course) {
        if (course.status() == CourseStatus.ARCHIVED) {
            return false;
        }
        Optional<Enrolment> enrolment = repos.courses().findEnrolment(course.id());
        if (enrolment.isPresent()) {
            return enrolment.get().isActive();
        }
        return course.isCreatedHere();
    }

    // ---- asking ------------------------------------------------------------------------------------

    @Override
    public Cancellable ask(String threadId, String question, AnswerListener listener) {
        Objects.requireNonNull(listener, "listener");
        String text = question == null ? "" : question.strip();
        if (text.isEmpty()) {
            throw new InvalidInputException(BLANK_QUESTION, "Blank question");
        }
        ChatThread thread = thread(threadId);
        Course course = repos.courses().get(thread.courseId());
        requireAccess(course);
        if (closed.get()) {
            throw LearnSphereException.unexpected("Ask service is closed", null);
        }
        CancellationToken token = CancellationToken.create();
        running.add(token);
        try {
            executor.execute(() -> {
                try {
                    new Exchange(thread, text, new SafeListener(listener), token).run();
                } finally {
                    running.remove(token);
                }
            });
        } catch (RejectedExecutionException e) {
            running.remove(token);
            throw LearnSphereException.unexpected("Ask service is closed", e);
        }
        return token;
    }

    /** Confirms the course was created here or the learner is enrolled in it. */
    private void requireAccess(Course course) {
        if (!course.isCreatedHere() && !repos.courses().isEnrolled(course.id())) {
            throw new InvalidInputException(NOT_STUDYING, "Course " + course.id() + " is neither local nor enrolled");
        }
    }

    private ChatThread thread(String threadId) {
        return repos.chats().findThread(threadId)
                .orElseThrow(() -> NotFoundException.of(THREAD_NOT_FOUND, "chat thread", threadId));
    }

    /**
     * Stops answers in progress (each is stored as cancelled) and waits briefly for them, then stops the
     * background threads. Idempotent.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        running.forEach(CancellationToken::cancel);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("Answers did not stop within {}", CLOSE_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** One question and its answer. Runs on one background thread. */
    private final class Exchange {

        private final ChatThread thread;
        private final String question;
        private final SafeListener listener;
        private final CancellationToken token;
        private final StringBuilder streamed = new StringBuilder();
        private List<Passage> passages = List.of();
        private ChatMessage saved;

        Exchange(ChatThread thread, String question, SafeListener listener, CancellationToken token) {
            this.thread = thread;
            this.question = question;
            this.listener = listener;
            this.token = token;
        }

        void run() {
            try {
                saved = saveQuestion();
            } catch (RuntimeException e) {
                LOG.error("Could not store a question in conversation {}", thread.id(), e);
                listener.onError(LearnSphereException.userMessageOf(e));
                return;
            }
            listener.onQuestionSaved(saved);
            try {
                answer();
            } catch (CancellationException e) {
                cancelled();
            } catch (RuntimeException e) {
                if (token.isCancelled()) {
                    cancelled();
                } else {
                    if (e instanceof LearnSphereException) {
                        LOG.info("Question in conversation {} could not be answered: {}", thread.id(), e.getMessage());
                    } else {
                        LOG.error("Question in conversation {} could not be answered", thread.id(), e);
                    }
                    failed(LearnSphereException.userMessageOf(e));
                }
            }
        }

        private ChatMessage saveQuestion() {
            return repos.database().inTransaction(h -> {
                ChatThread current = repos.chats().findThread(thread.id())
                        .orElseThrow(() -> NotFoundException.of(THREAD_NOT_FOUND, "chat thread", thread.id()));
                ChatMessage message = ChatMessage.question(thread.id(), question, clock.millis());
                repos.chats().insertMessage(message);
                if (current.title().isBlank()) {
                    repos.chats().renameThread(thread.id(), PipelineText.shorten(question, AUTO_TITLE_LENGTH));
                }
                return message;
            });
        }

        private void answer() {
            token.throwIfCancelled();
            listener.onPhase(AskPhase.SEARCHING);
            if (index.needsRebuild()) {
                failed(MATERIAL_PREPARING);
                return;
            }
            Map<String, String> readyTitles = readyDocumentTitles(thread.courseId());
            if (readyTitles.isEmpty()) {
                insufficient(NOTHING_READY);
                return;
            }
            float[] vector = embedder.embed(List.of(question), EmbedKind.QUERY, token).getFirst();
            token.throwIfCancelled();
            List<SearchHit> hits = index.search(SearchQuery.forCourse(thread.courseId(), question, vector)).stream()
                    .filter(hit -> thread.courseId().equals(hit.courseId()) && readyTitles.containsKey(hit.documentId()))
                    .toList();
            passages = evidence(hits, readyTitles);
            if (passages.isEmpty()) {
                insufficient(NOT_COVERED);
                return;
            }
            token.throwIfCancelled();
            listener.onPhase(AskPhase.WRITING);
            GroundedAnswer result = answerer.answer(question, passages, piece -> {
                streamed.append(piece);
                listener.onToken(piece);
            }, token);
            token.throwIfCancelled();
            if (result.insufficient()) {
                insufficient(NOT_COVERED);
                return;
            }
            CitationValidator.Result validated = CitationValidator.validate(result.text(), passages);
            if (!validated.hasCitations()) {
                LOG.debug("Answer in conversation {} cited no passage; reporting insufficient evidence", thread.id());
                insufficient(NOT_COVERED);
                return;
            }
            ChatMessage stored = store(validated.text(), MessageStatus.COMPLETE, validated.citations());
            listener.onAnswer(toAnswer(stored));
        }

        /** Reranks the hits and keeps the best passages that clear the evidence threshold, numbered from 1. */
        private List<Passage> evidence(List<SearchHit> hits, Map<String, String> titles) {
            if (hits.isEmpty()) {
                return List.of();
            }
            double[] scores = reranker.score(question, hits.stream().map(SearchHit::text).toList(), token);
            if (scores.length != hits.size()) {
                throw LearnSphereException.unexpected("Reranker returned " + scores.length + " scores for "
                        + hits.size() + " passages", null);
            }
            double threshold = settings.get(SettingKeys.EVIDENCE_THRESHOLD);
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                if (scores[i] >= threshold) {
                    order.add(i);
                }
            }
            order.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
            List<Passage> result = new ArrayList<>();
            for (int i : order.subList(0, Math.min(MAX_PASSAGES, order.size()))) {
                SearchHit hit = hits.get(i);
                result.add(new Passage(result.size() + 1, hit.chunkId(), hit.documentId(),
                        titles.getOrDefault(hit.documentId(), hit.documentTitle()), hit.pageStart(), hit.pageEnd(),
                        hit.sectionTitle(), hit.text(), scores[i]));
            }
            return List.copyOf(result);
        }

        private void insufficient(UserMessage explanation) {
            ChatMessage stored = store(explanation.text(), MessageStatus.INSUFFICIENT, List.of());
            listener.onInsufficientEvidence(toAnswer(stored));
        }

        private void cancelled() {
            try {
                String partial = streamed.toString();
                List<Citation> citations = List.of();
                if (!passages.isEmpty() && !partial.isBlank()) {
                    CitationValidator.Result validated = CitationValidator.validate(partial, passages);
                    partial = validated.text();
                    citations = validated.citations();
                }
                store(partial.strip(), MessageStatus.CANCELLED, citations);
            } catch (RuntimeException e) {
                LOG.warn("Could not store a stopped answer in conversation {}", thread.id(), e);
            }
            listener.onCancelled();
        }

        private void failed(UserMessage message) {
            try {
                store(message.text(), MessageStatus.ERROR, List.of());
            } catch (RuntimeException e) {
                LOG.warn("Could not store a failed answer in conversation {}", thread.id(), e);
            }
            listener.onError(message);
        }

        private ChatMessage store(String text, MessageStatus status, List<Citation> citations) {
            ChatMessage message = ChatMessage.answer(thread.id(), text, status, citations, clock.millis());
            repos.chats().insertMessage(message);
            return message;
        }

        private Answer toAnswer(ChatMessage message) {
            return new Answer(message.id(), message.threadId(), saved.id(), message.content(), message.status(),
                    message.citations(), message.createdAt());
        }
    }

    /** Titles of the course's ready PDFs, keyed by document id. */
    private Map<String, String> readyDocumentTitles(String courseId) {
        Map<String, String> titles = new HashMap<>();
        for (Document document : repos.documents().listByCourse(courseId)) {
            if (document.status() == DocumentStatus.READY) {
                titles.put(document.id(), document.title());
            }
        }
        return titles;
    }

    /** Forwards to the caller's listener; a listener that throws is logged and does not stop the answer. */
    private record SafeListener(AnswerListener delegate) {

        void onQuestionSaved(ChatMessage question) {
            guard(() -> delegate.onQuestionSaved(question));
        }

        void onPhase(AskPhase phase) {
            guard(() -> delegate.onPhase(phase));
        }

        void onToken(String token) {
            guard(() -> delegate.onToken(token));
        }

        void onAnswer(Answer answer) {
            guard(() -> delegate.onAnswer(answer));
        }

        void onInsufficientEvidence(Answer answer) {
            guard(() -> delegate.onInsufficientEvidence(answer));
        }

        void onError(UserMessage message) {
            guard(() -> delegate.onError(message));
        }

        void onCancelled() {
            guard(delegate::onCancelled);
        }

        private static void guard(Runnable call) {
            try {
                call.run();
            } catch (RuntimeException e) {
                LOG.warn("Answer listener failed", e);
            }
        }
    }
}
