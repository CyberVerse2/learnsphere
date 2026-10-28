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
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@link AskService} over the {@link DemoStore}, replying by rule the way the study assistant would: a greeting
 * gets a greeting, "quiz me" gets a question on one of the course's topics, a message that shares words with the
 * course's passages is answered word by word from them with numbered citations, and anything else is told the
 * course doesn't cover it, with two of its topics to try.
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
    public List<String> topics(String courseId, int limit) {
        synchronized (store) {
            return store.modules.values().stream()
                    .filter(m -> m.courseId().equals(courseId))
                    .sorted(Comparator.comparingInt(CourseModule::position))
                    .map(CourseModule::title)
                    .limit(Math.max(0, limit))
                    .toList();
        }
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
            String lower = question.content().toLowerCase(Locale.ROOT).strip();
            List<String> topics = topics(courseId, 2);
            if (lower.matches("(hi|hello|hey)\\b.*")) {
                text = "Hi! What would you like to go over in " + courseTitle(courseId) + "?";
            } else if (lower.contains("quiz") || lower.contains("test me")) {
                text = "Here's one: what does your course say about "
                        + (topics.isEmpty() ? courseTitle(courseId) : topics.getFirst()) + "? Have a go.";
            } else if (sources.isEmpty()) {
                text = "Your course material doesn't cover that."
                        + (topics.isEmpty() ? "" : " It does cover " + String.join(" and ", topics) + ", if you'd like to start there.");
            } else {
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
            }
            listener.onPhase(AskPhase.WRITING);
            for (String word : text.split("(?<= )")) {
                if (cancelled.isCancelled()) {
                    break;
                }
                listener.onToken(word);
                pause(wordDelay);
            }
            if (cancelled.isCancelled()) {
                listener.onCancelled();
                return;
            }
            long now;
            ChatMessage reply;
            synchronized (store) {
                now = store.now();
                reply = new ChatMessage(DemoStore.newId(), threadId, ChatRole.ASSISTANT, text, MessageStatus.COMPLETE,
                        citations, List.of(), now);
                store.messages.computeIfAbsent(threadId, k -> new ArrayList<>()).add(reply);
            }
            listener.onAnswer(new Answer(reply.id(), threadId, question.id(), text, MessageStatus.COMPLETE, citations,
                    List.of(), now));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onCancelled();
        } catch (RuntimeException e) {
            listener.onError(UserMessage.SOMETHING_WENT_WRONG);
        }
    }

    private String courseTitle(String courseId) {
        synchronized (store) {
            Course course = store.courses.get(courseId);
            return course == null ? "this course" : course.title();
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
