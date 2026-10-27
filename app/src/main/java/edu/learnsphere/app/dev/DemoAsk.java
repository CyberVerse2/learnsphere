package edu.learnsphere.app.dev;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatRole;
import edu.learnsphere.core.model.ChatThread;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.MessageStatus;
import edu.learnsphere.core.service.Answer;
import edu.learnsphere.core.service.AnswerListener;
import edu.learnsphere.core.service.AskPhase;
import edu.learnsphere.core.service.AskService;
import edu.learnsphere.core.service.RecentQuestion;
import edu.learnsphere.core.service.ThreadSummary;
import edu.learnsphere.core.util.Cancellable;
import edu.learnsphere.core.util.CancellationToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * {@link AskService} over the {@link DemoStore}. Questions are matched to the course's passages by shared
 * words; a good match is answered word by word from the passages with numbered citations, and a question
 * the material doesn't cover gets the insufficient-evidence answer, as the real assistant would.
 */
final class DemoAsk implements AskService {

    /** Title of a conversation before its first question names it. */
    static final String NEW_THREAD_TITLE = "New conversation";
    private static final int MIN_SCORE = 2;
    private static final UserMessage QUESTION_NEEDED = UserMessage.of("Type a question first.");
    private static final UserMessage THREAD_NOT_FOUND = UserMessage.of("This conversation isn't available.");

    private final DemoStore store;
    private final DemoProgress progress;
    private final Duration wordDelay;

    DemoAsk(DemoStore store, DemoProgress progress, Duration wordDelay) {
        this.store = store;
        this.progress = progress;
        this.wordDelay = wordDelay;
    }

    @Override
    public List<ThreadSummary> threads(String courseId) {
        synchronized (store) {
            store.course(courseId);
            return store.threads.values().stream().filter(t -> t.courseId().equals(courseId))
                    .sorted(Comparator.comparingLong(ChatThread::updatedAt).reversed())
                    .map(t -> {
                        List<ChatMessage> messages = store.messages.getOrDefault(t.id(), List.of());
                        ChatMessage last = messages.isEmpty() ? null : messages.getLast();
                        return new ThreadSummary(t.id(), t.courseId(), t.title(), messages.size(),
                                last == null ? null : last.content(), last == null ? null : last.createdAt(), t.updatedAt());
                    }).toList();
        }
    }

    @Override
    public ChatThread createThread(String courseId, String title) {
        synchronized (store) {
            store.course(courseId);
            long now = store.now();
            ChatThread thread = new ChatThread(DemoStore.newId(), courseId,
                    title == null || title.isBlank() ? NEW_THREAD_TITLE : title.strip(), now, now);
            store.threads.put(thread.id(), thread);
            store.messages.put(thread.id(), new ArrayList<>());
            return thread;
        }
    }

    @Override
    public void renameThread(String threadId, String title) {
        synchronized (store) {
            ChatThread t = thread(threadId);
            store.threads.put(threadId, new ChatThread(t.id(), t.courseId(), title.strip(), t.createdAt(), store.now()));
        }
    }

    @Override
    public void deleteThread(String threadId) {
        synchronized (store) {
            thread(threadId);
            store.threads.remove(threadId);
            store.messages.remove(threadId);
        }
    }

    @Override
    public List<ChatMessage> messages(String threadId) {
        synchronized (store) {
            thread(threadId);
            return List.copyOf(store.messages.getOrDefault(threadId, List.of()));
        }
    }

    @Override
    public List<RecentQuestion> recentQuestions(int limit) {
        synchronized (store) {
            return progress.recentQuestions(limit);
        }
    }

    private ChatThread thread(String threadId) {
        ChatThread thread = store.threads.get(threadId);
        if (thread == null) {
            throw new NotFoundException(THREAD_NOT_FOUND, "No thread " + threadId);
        }
        return thread;
    }

    @Override
    public Cancellable ask(String threadId, String question, AnswerListener listener) {
        if (question == null || question.isBlank()) {
            throw new InvalidInputException(QUESTION_NEEDED, "blank question");
        }
        ChatMessage saved;
        List<DemoStore.Passage> sources;
        String courseId;
        synchronized (store) {
            ChatThread t = thread(threadId);
            courseId = t.courseId();
            long now = store.now();
            saved = new ChatMessage(DemoStore.newId(), threadId, ChatRole.USER, question.strip(), MessageStatus.COMPLETE,
                    List.of(), now);
            store.messages.computeIfAbsent(threadId, k -> new ArrayList<>()).add(saved);
            String title = t.title().equals(NEW_THREAD_TITLE) ? shorten(question.strip()) : t.title();
            store.threads.put(threadId, new ChatThread(t.id(), t.courseId(), title, t.createdAt(), now));
            sources = match(courseId, question);
        }
        listener.onQuestionSaved(saved);
        CancellationToken cancelled = CancellationToken.create();
        Thread worker = Thread.ofVirtual().name("demo-answer").unstarted(() -> answer(threadId, saved, courseId,
                sources, listener, cancelled));
        worker.start();
        return cancelled;
    }

    private static String shorten(String question) {
        String text = question.replaceAll("\\s+", " ");
        return text.length() <= 48 ? text : text.substring(0, 47).strip() + "…";
    }

    /** The passages of {@code courseId} that share the most words with {@code question}, best first. */
    private List<DemoStore.Passage> match(String courseId, String question) {
        Set<String> words = DemoContent.words(question);
        record Scored(DemoStore.Passage passage, int score) {
        }
        return store.passages.values().stream()
                .filter(p -> p.courseId().equals(courseId))
                .map(p -> {
                    Set<String> text = DemoContent.words(p.section() + " " + p.text());
                    int score = (int) words.stream().filter(text::contains).count();
                    return new Scored(p, score);
                })
                .filter(s -> s.score() >= MIN_SCORE)
                .sorted(Comparator.comparingInt(Scored::score).reversed())
                .limit(2)
                .map(Scored::passage)
                .toList();
    }

    private void answer(String threadId, ChatMessage question, String courseId, List<DemoStore.Passage> sources,
                        AnswerListener listener, CancellationToken cancelled) {
        try {
            listener.onPhase(AskPhase.SEARCHING);
            pause(Duration.ofMillis(500));
            String text;
            List<Citation> citations = new ArrayList<>();
            MessageStatus status;
            if (sources.isEmpty()) {
                status = MessageStatus.INSUFFICIENT;
                text = "Your course material doesn't cover this. Try asking about one of the topics in its "
                        + "modules, or rephrase the question with words from your notes.";
            } else {
                status = MessageStatus.COMPLETE;
                StringBuilder builder = new StringBuilder();
                synchronized (store) {
                    for (int i = 0; i < sources.size(); i++) {
                        DemoStore.Passage passage = sources.get(i);
                        Document document = store.documents.get(passage.documentId());
                        citations.add(new Citation(i + 1, passage.id(), passage.documentId(),
                                document == null ? "" : document.title(), passage.page(), passage.page(), passage.text()));
                        String sentences = twoSentences(passage.text());
                        builder.append(i == 0 ? "" : " ").append(sentences, 0, sentences.length() - 1)
                                .append(" [").append(i + 1).append("].");
                    }
                }
                text = builder.toString();
                listener.onPhase(AskPhase.WRITING);
                for (String word : text.split("(?<= )")) {
                    if (cancelled.isCancelled()) {
                        break;
                    }
                    listener.onToken(word);
                    pause(wordDelay);
                }
            }
            if (cancelled.isCancelled()) {
                listener.onCancelled();
                return;
            }
            long now;
            ChatMessage reply;
            synchronized (store) {
                now = store.now();
                reply = new ChatMessage(DemoStore.newId(), threadId, ChatRole.ASSISTANT, text, status, citations, now);
                store.messages.computeIfAbsent(threadId, k -> new ArrayList<>()).add(reply);
            }
            Answer answer = new Answer(reply.id(), threadId, question.id(), text, status, citations, now);
            if (status == MessageStatus.INSUFFICIENT) {
                listener.onInsufficientEvidence(answer);
            } else {
                listener.onAnswer(answer);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onCancelled();
        } catch (RuntimeException e) {
            listener.onError(UserMessage.SOMETHING_WENT_WRONG);
        }
    }

    private static String twoSentences(String text) {
        String[] parts = text.split("(?<=\\.) ");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[1];
    }

    private static void pause(Duration duration) throws InterruptedException {
        if (!duration.isZero()) {
            Thread.sleep(duration);
        }
    }
}
