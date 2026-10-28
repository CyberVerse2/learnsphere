package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.ask.CitationValidator;
import edu.learnsphere.core.db.ChatRepository;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatRole;
import edu.learnsphere.core.model.ChatThread;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
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
import edu.learnsphere.core.spi.AnswerRequest;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.spi.GroundedAnswer;
import edu.learnsphere.core.spi.GroundedAnswerer;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.QuestionReader;
import edu.learnsphere.core.spi.QuestionReading;
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
import java.util.HashSet;
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
 *   <li>the message is stored (the first message of an untitled conversation becomes its title);</li>
 *   <li>{@link AskPhase#SEARCHING}: what to search for is the message as typed or, in a conversation that has
 *       answers, the {@link QuestionReader}'s rewrite of it using the last {@value #EARLIER_TURNS} exchanges, so a
 *       follow-up such as "give an example" stands on its own (the message as typed if reading fails);</li>
 *   <li>that is embedded as a {@link EmbedKind#QUERY} and searched in the course with
 *       {@link SearchQuery#forCourse} (keyword top 40 + vector top 40, fusion k = 60, keep 24); hits from PDFs that
 *       are not ready are dropped;</li>
 *   <li>the {@link Reranker} scores the hits, and up to {@value #MAX_PASSAGES} passages scoring at least the
 *       {@link SettingKeys#EVIDENCE_THRESHOLD} setting are kept. Passages the conversation's last answer cited
 *       fill the remaining places (up to {@value #CARRIED_PASSAGES}), so a follow-up or an answer to a quiz
 *       question keeps its topic. With none at all (a greeting, "teach me something", or a question the course
 *       doesn't cover), {@value #SAMPLED_PASSAGES} passages picked at random from the course's PDFs are used. The
 *       passages are numbered {@code [1]…[n]};</li>
 *   <li>{@link AskPhase#WRITING}: the {@link GroundedAnswerer} gets the course title, its {@link #topics}, the
 *       conversation's last exchanges, the message and the passages, and streams its reply (tokens go to
 *       {@link AnswerListener#onToken}). Every message reaches it: it greets, teaches, quizzes and says when the
 *       course doesn't cover something, taking every fact from the passages;</li>
 *   <li>{@link CitationValidator} keeps only markers that point at a passage, and the reply is stored as
 *       {@link MessageStatus#COMPLETE} with its citations, then {@link AnswerListener#onAnswer}. A reply needs no
 *       citation (a greeting or a quiz question has none). When the course has no PDF ready, or the answerer
 *       writes nothing, the reply is the insufficient-evidence card ({@link MessageStatus#INSUFFICIENT},
 *       {@link AnswerListener#onInsufficientEvidence}) offering the course's topics.</li>
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
    /** Exchanges of the conversation the question reader and the answerer see. */
    static final int EARLIER_TURNS = 3;
    /** Most passages the conversation's last answer cited that are given again with the next message. */
    static final int CARRIED_PASSAGES = 3;
    /** Passages picked at random from the course when nothing else applies. */
    static final int SAMPLED_PASSAGES = 4;
    /** Shortest passage, in characters, worth picking at random. */
    private static final int SAMPLED_MIN_LENGTH = 300;
    /** Topics offered with the insufficient-evidence card. */
    public static final int OFFERED_TOPICS = 6;
    /** Topics the answerer may suggest. */
    static final int REPLY_TOPICS = 12;
    /** Longest earlier answer the question reader sees, in characters. */
    private static final int EARLIER_ANSWER_LENGTH = 600;

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
    private final QuestionReader reader;
    private final ExecutorService executor = Executors.newCachedThreadPool(
            Thread.ofPlatform().name("learnsphere-ask-", 0).daemon(true).factory());
    private final Set<CancellationToken> running = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AskServiceImpl(Repositories repos, SettingsService settings, SearchIndex index, Embedder embedder,
                          Reranker reranker, GroundedAnswerer answerer, QuestionReader reader) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.clock = repos.clock();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.index = Objects.requireNonNull(index, "index");
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.reranker = Objects.requireNonNull(reranker, "reranker");
        this.answerer = Objects.requireNonNull(answerer, "answerer");
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    /** A service that takes every message as a question ({@link QuestionReader#AS_IS}). */
    public AskServiceImpl(Repositories repos, SettingsService settings, SearchIndex index, Embedder embedder,
                          Reranker reranker, GroundedAnswerer answerer) {
        this(repos, settings, index, embedder, reranker, answerer, QuestionReader.AS_IS);
    }

    /** The service of an opened profile, from the workspace's capabilities, index and settings. */
    public static AskServiceImpl create(ProfileContext context, Capabilities capabilities, SearchIndex index,
                                        SettingsService settings) {
        return new AskServiceImpl(context.repositories(), settings, index, capabilities.embedder(), capabilities.reranker(),
                capabilities.answerer(), capabilities.questionReader());
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
    public List<String> topics(String courseId, int limit) {
        repos.courses().get(courseId);
        List<String> topics = Topics.distinct(repos.modules().listByCourse(courseId).stream().map(CourseModule::title).toList());
        if (topics.isEmpty()) {
            topics = Topics.distinct(repos.concepts().listByCourse(courseId).stream().map(Concept::name).toList());
        }
        if (topics.isEmpty()) {
            topics = Topics.distinct(repos.chunks().sectionTitles(courseId));
        }
        return Topics.spread(topics, limit);
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
        /** What the learner typed (stored, and shown). */
        private final String message;
        /** What gets searched and answered: the message, or the reader's standalone version of it. */
        private String question;
        private final SafeListener listener;
        private final CancellationToken token;
        private final StringBuilder streamed = new StringBuilder();
        private List<Passage> passages = List.of();
        private ChatMessage saved;

        Exchange(ChatThread thread, String question, SafeListener listener, CancellationToken token) {
            this.thread = thread;
            this.message = question;
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
                List<QuestionReader.Turn> turns = earlierTurns();
                ChatMessage stored = ChatMessage.question(thread.id(), message, clock.millis());
                repos.chats().insertMessage(stored);
                if (current.title().isBlank()) {
                    repos.chats().renameThread(thread.id(), PipelineText.shorten(message, AUTO_TITLE_LENGTH));
                }
                earlier = turns;
                return stored;
            });
        }

        private List<QuestionReader.Turn> earlier = List.of();
        /** Chunks the conversation's last answer cited, in citation order. */
        private List<String> lastCited = List.of();

        /** The conversation's last answered exchanges before this one, oldest first; notes what the last one cited. */
        private List<QuestionReader.Turn> earlierTurns() {
            List<QuestionReader.Turn> turns = new ArrayList<>();
            String asked = null;
            for (ChatMessage m : repos.chats().listMessages(thread.id())) {
                if (m.role() == ChatRole.USER) {
                    asked = m.content();
                } else if (asked != null && m.status() == MessageStatus.COMPLETE) {
                    turns.add(new QuestionReader.Turn(asked,
                            PipelineText.shorten(PipelineText.withoutCitations(m.content()), EARLIER_ANSWER_LENGTH)));
                    lastCited = m.citations().stream().map(Citation::chunkId).distinct().toList();
                    asked = null;
                }
            }
            return List.copyOf(turns.subList(Math.max(0, turns.size() - EARLIER_TURNS), turns.size()));
        }

        /** What to search for: the message as typed (a first message) or as the reader rewrites it. */
        private String searchText() {
            if (earlier.isEmpty()) {
                return message;
            }
            try {
                QuestionReading reading = reader.read(message, earlier, token);
                if (reading.kind() != QuestionReading.Kind.QUESTION || reading.question().isBlank()) {
                    return message;
                }
                return reading.question().strip();
            } catch (CancellationException | UnavailableException e) {
                throw e;
            } catch (RuntimeException e) {
                if (token.isCancelled()) {
                    throw e;
                }
                LOG.warn("Could not read a message in conversation {}; searching it as typed: {}", thread.id(), e.toString());
                return message;
            }
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
            question = searchText();
            token.throwIfCancelled();
            if (!question.equals(message)) {
                LOG.debug("Searching conversation {} for the follow-up as: {}", thread.id(), question);
            }
            float[] vector = embedder.embed(List.of(question), EmbedKind.QUERY, token).getFirst();
            token.throwIfCancelled();
            List<SearchHit> hits = index.search(SearchQuery.forCourse(thread.courseId(), question, vector)).stream()
                    .filter(hit -> thread.courseId().equals(hit.courseId()) && readyTitles.containsKey(hit.documentId()))
                    .toList();
            passages = numbered(withContext(evidence(hits, readyTitles), readyTitles));
            if (passages.isEmpty()) {
                insufficient(NOT_COVERED);
                return;
            }
            token.throwIfCancelled();
            listener.onPhase(AskPhase.WRITING);
            AnswerRequest request = new AnswerRequest(repos.courses().get(thread.courseId()).title().strip(),
                    topics(thread.courseId(), REPLY_TOPICS), earlier, message, passages);
            GroundedAnswer result = answerer.answer(request, piece -> {
                streamed.append(piece);
                listener.onToken(piece);
            }, token);
            token.throwIfCancelled();
            if (result.insufficient()) {
                insufficient(NOT_COVERED);
                return;
            }
            CitationValidator.Result validated = CitationValidator.validate(result.text(), passages);
            ChatMessage stored = store(validated.text(), MessageStatus.COMPLETE, validated.citations());
            listener.onAnswer(toAnswer(stored));
        }

        /** Reranks the hits and keeps the best passages that clear the evidence threshold, best first. */
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
            List<Integer> ranked = new ArrayList<>();
            for (int i = 0; i < hits.size(); i++) {
                ranked.add(i);
            }
            ranked.sort(Comparator.comparingDouble((Integer i) -> scores[i]).reversed());
            List<Integer> order = ranked.stream().filter(i -> scores[i] >= threshold).toList();
            List<Passage> result = new ArrayList<>();
            for (int i : order.subList(0, Math.min(MAX_PASSAGES, order.size()))) {
                SearchHit hit = hits.get(i);
                result.add(new Passage(result.size() + 1, hit.chunkId(), hit.documentId(),
                        titles.getOrDefault(hit.documentId(), hit.documentTitle()), hit.pageStart(), hit.pageEnd(),
                        hit.sectionTitle(), hit.text(), scores[i]));
            }
            return result;
        }

        /**
         * The passages found, then passages the conversation's last answer cited in the places left; with nothing
         * at all, passages picked at random from the course (preferring longer ones).
         */
        private List<Passage> withContext(List<Passage> found, Map<String, String> titles) {
            List<Passage> result = new ArrayList<>(found);
            Set<String> present = new HashSet<>(found.stream().map(Passage::chunkId).toList());
            List<String> carried = lastCited.stream().filter(id -> !present.contains(id))
                    .limit(Math.min(CARRIED_PASSAGES, MAX_PASSAGES - result.size())).toList();
            Map<String, Chunk> chunks = new HashMap<>();
            repos.chunks().listByIds(carried).forEach(chunk -> chunks.put(chunk.id(), chunk));
            for (String id : carried) {
                Chunk chunk = chunks.get(id);
                if (chunk != null && titles.containsKey(chunk.documentId())) {
                    result.add(passage(chunk, titles));
                }
            }
            if (result.isEmpty()) {
                List<Chunk> sampled = repos.chunks().sample(thread.courseId(), SAMPLED_PASSAGES, SAMPLED_MIN_LENGTH);
                if (sampled.isEmpty()) {
                    sampled = repos.chunks().sample(thread.courseId(), SAMPLED_PASSAGES, 0);
                }
                sampled.stream()
                        .filter(chunk -> titles.containsKey(chunk.documentId()))
                        .forEach(chunk -> result.add(passage(chunk, titles)));
            }
            return result;
        }

        private static Passage passage(Chunk chunk, Map<String, String> titles) {
            return new Passage(1, chunk.id(), chunk.documentId(), titles.get(chunk.documentId()), chunk.pageStart(),
                    chunk.pageEnd(), chunk.sectionTitle(), chunk.text(), 0);
        }

        private static List<Passage> numbered(List<Passage> passages) {
            List<Passage> result = new ArrayList<>();
            for (Passage p : passages) {
                result.add(new Passage(result.size() + 1, p.chunkId(), p.documentId(), p.documentTitle(), p.pageStart(),
                        p.pageEnd(), p.sectionTitle(), p.text(), p.relevance()));
            }
            return List.copyOf(result);
        }

        /** The insufficient-evidence card, offering the course's topics. */
        private void insufficient(UserMessage explanation) {
            ChatMessage stored = ChatMessage.reply(thread.id(), explanation.text(), MessageStatus.INSUFFICIENT,
                    topics(thread.courseId(), OFFERED_TOPICS), clock.millis());
            repos.chats().insertMessage(stored);
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
                    message.citations(), message.suggestions(), message.createdAt());
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
