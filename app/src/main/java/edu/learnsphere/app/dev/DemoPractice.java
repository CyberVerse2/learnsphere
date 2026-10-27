package edu.learnsphere.app.dev;

import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.model.Attempt;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.service.ConceptChange;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.Hint;
import edu.learnsphere.core.service.PracticeService;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.SessionSummary;
import edu.learnsphere.core.service.SourcePassage;
import edu.learnsphere.core.service.StudyNext;
import edu.learnsphere.core.service.StudySession;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * {@link PracticeService} over the {@link DemoStore}: picks due, weak and new items, grades answers by rule
 * (short answers by shared key words, standing in for the study assistant), and schedules the next review
 * with simple growing intervals.
 */
final class DemoPractice implements PracticeService {

    private static final long DAY = Duration.ofDays(1).toMillis();
    private static final UserMessage SESSION_ENDED = UserMessage.of("This session has ended. Start a new one.");

    private record Running(StudySession session, Map<String, Feedback> answers, Map<String, Double> masteryBefore,
                           long startedAt) {
    }

    private final DemoStore store;
    private final DemoProgress progress;
    private final Map<String, Running> sessions = new HashMap<>();
    private final Random random = new Random(7);

    DemoPractice(DemoStore store, DemoProgress progress) {
        this.store = store;
        this.progress = progress;
    }

    @Override
    public StudyNext studyNext(String courseId) {
        synchronized (store) {
            return progress.studyNext(courseId);
        }
    }

    @Override
    public StudySession start(SessionRequest request) {
        synchronized (store) {
            long now = store.now();
            List<LearningItem> pool = store.studyItems(request.courseId()).stream()
                    .filter(i -> request.moduleId() == null || request.moduleId().equals(i.moduleId())).toList();
            List<LearningItem> picked = new ArrayList<>();
            switch (request.kind()) {
                case FLASHCARDS -> pool.stream().filter(i -> i.type() == ItemType.FLASHCARD).forEach(picked::add);
                case MODULE_QUIZ -> picked.addAll(pool);
                case WEAK_CONCEPTS -> {
                    Set<String> weak = new java.util.HashSet<>();
                    for (String courseId : courseIds(request.courseId())) {
                        progress.mastery(courseId).stream()
                                .filter(c -> c.mastery() != null && c.mastery() < ConceptMastery.WEAK_THRESHOLD)
                                .forEach(c -> weak.add(c.conceptId()));
                    }
                    pool.stream().filter(i -> store.itemConcepts.getOrDefault(i.id(), Set.of()).stream()
                            .anyMatch(weak::contains)).forEach(picked::add);
                }
                case STUDY_NEXT -> {
                    pool.stream().filter(i -> store.due(i.id(), now)).forEach(picked::add);
                    pool.stream().filter(i -> !store.started(i.id())).limit(10).forEach(picked::add);
                }
            }
            if (picked.isEmpty() && request.kind() != SessionKind.STUDY_NEXT) {
                picked.addAll(pool);
            }
            List<LearningItem> chosen = picked.stream().distinct().limit(request.size()).toList();
            String courseId = request.courseId() != null ? request.courseId()
                    : chosen.isEmpty() ? null : chosen.getFirst().courseId();
            String courseTitle = courseId == null ? null : store.course(courseId).title();
            String moduleTitle = store.moduleTitle(request.moduleId());
            List<SessionItem> items = chosen.stream().map(this::sessionItem).toList();
            StudySession session = new StudySession(DemoStore.newId(), request.kind(), request.courseId(), courseTitle,
                    request.moduleId(), moduleTitle, items, now);
            Map<String, Double> before = new HashMap<>();
            for (String id : courseIds(request.courseId())) {
                progress.mastery(id).forEach(c -> before.put(c.conceptId(), c.mastery()));
            }
            sessions.put(session.id(), new Running(session, new LinkedHashMap<>(), before, now));
            return session;
        }
    }

    private List<String> courseIds(String courseId) {
        return courseId != null ? List.of(courseId) : List.copyOf(store.enrolled);
    }

    private SessionItem sessionItem(LearningItem item) {
        List<String> choices = switch (item.type()) {
            case MCQ -> {
                List<String> list = new ArrayList<>(item.distractors());
                list.add(item.answer());
                Collections.shuffle(list, new Random(item.id().hashCode()));
                yield list;
            }
            case TRUE_FALSE -> List.of("True", "False");
            default -> List.of();
        };
        List<String> concepts = store.conceptsOfItem(item.id()).stream().map(Concept::name).toList();
        return new SessionItem(item.id(), item.type(), item.prompt(), choices,
                item.type() == ItemType.FLASHCARD ? item.answer() : null, concepts, store.moduleTitle(item.moduleId()),
                item.difficulty(), !store.started(item.id()), item.type() == ItemType.MCQ || item.type() == ItemType.CLOZE
                        || item.type() == ItemType.SHORT_ANSWER);
    }

    @Override
    public Feedback submit(String sessionId, Response response) {
        Feedback feedback;
        String courseId;
        synchronized (store) {
            Running running = running(sessionId);
            LearningItem item = store.item(response.itemId());
            courseId = item.courseId();
            Grade grade = grade(item, response);
            long now = store.now();
            Attempt attempt = new Attempt(DemoStore.newId(), item.id(), sessionId, response.answer(), grade.correct,
                    grade.score, grade.rating, response.confidence(), response.hintUsed(), response.durationMs(),
                    grade.gradedBy, grade.feedback, false, now, now);
            store.attempts.add(attempt);
            ReviewState next = schedule(item.id(), grade.rating, now);
            store.reviews.put(item.id(), next);
            SourcePassage source = store.sourcePassage(item);
            feedback = new Feedback(attempt.id(), item.id(), grade.correct, grade.score, grade.rating, grade.gradedBy,
                    item.answer(), item.explanation(), grade.feedback, grade.missing,
                    source == null ? null : source.ref(), next.dueAt(), next.scheduledDays(),
                    grade.gradedBy == GradedBy.AI, false);
            running.answers.put(item.id(), feedback);
            store.studyTime.merge(store.today(), response.durationMs(), Long::sum);
        }
        store.events.publish(new ProgressChanged(courseId));
        return feedback;
    }

    private record Grade(boolean correct, double score, Rating rating, GradedBy gradedBy, String feedback,
                         List<String> missing) {
    }

    private Grade grade(LearningItem item, Response response) {
        String answer = response.answer() == null ? "" : response.answer().strip();
        return switch (item.type()) {
            case FLASHCARD -> {
                Rating rating = response.selfRating() == null ? Rating.GOOD : response.selfRating();
                boolean correct = rating != Rating.AGAIN;
                yield new Grade(correct, correct ? 1 : 0, rating, GradedBy.SELF, null, List.of());
            }
            case SHORT_ANSWER -> {
                Set<String> expected = DemoContent.words(item.answer());
                Set<String> given = DemoContent.words(answer);
                long hits = expected.stream().filter(given::contains).count();
                double score = expected.isEmpty() ? 0 : Math.min(1, hits / (expected.size() * 0.6));
                boolean correct = score >= 0.6;
                List<String> missing = expected.stream().filter(w -> !given.contains(w)).limit(3).toList();
                String note = correct ? "Your answer covers the main points."
                        : "Your answer misses some key points from the material.";
                yield new Grade(correct, score, rating(correct, response), GradedBy.AI, note,
                        correct ? List.of() : missing);
            }
            default -> {
                boolean correct = normalise(answer).equals(normalise(item.answer()));
                yield new Grade(correct, correct ? 1 : 0, rating(correct, response), GradedBy.RULE, null, List.of());
            }
        };
    }

    private static Rating rating(boolean correct, Response response) {
        if (!correct) {
            return Rating.AGAIN;
        }
        if (response.hintUsed()) {
            return Rating.HARD;
        }
        return response.confidence() == edu.learnsphere.core.model.Confidence.CERTAIN ? Rating.EASY : Rating.GOOD;
    }

    private static String normalise(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    private ReviewState schedule(String itemId, Rating rating, long now) {
        ReviewState previous = store.reviews.get(itemId);
        double interval = previous == null || previous.phase() == ReviewPhase.NEW ? 0 : Math.max(1, previous.scheduledDays());
        double next = switch (rating) {
            case AGAIN -> 1;
            case HARD -> Math.max(1, interval * 1.2);
            case GOOD -> interval == 0 ? 2 : interval * 2.5;
            case EASY -> interval == 0 ? 4 : interval * 3.5;
        };
        int days = (int) Math.max(1, Math.round(next));
        int reps = previous == null ? 1 : previous.reps() + 1;
        int lapses = (previous == null ? 0 : previous.lapses()) + (rating == Rating.AGAIN ? 1 : 0);
        ReviewPhase phase = rating == Rating.AGAIN && previous != null && previous.phase() == ReviewPhase.REVIEW
                ? ReviewPhase.RELEARNING : rating == Rating.AGAIN ? ReviewPhase.LEARNING : ReviewPhase.REVIEW;
        long created = previous == null ? now : previous.createdAt();
        return new ReviewState(itemId, phase, days, previous == null ? 5 : previous.difficulty(), now + days * DAY, now,
                reps, lapses, days, 0, created, now);
    }

    @Override
    public Hint hint(String sessionId, String itemId) {
        synchronized (store) {
            running(sessionId);
            LearningItem item = store.item(itemId);
            return switch (item.type()) {
                case MCQ -> {
                    List<String> removed = new ArrayList<>(item.distractors());
                    Collections.shuffle(removed, random);
                    yield new Hint("Two options are ruled out.", removed.stream().limit(2).toList());
                }
                case CLOZE -> new Hint("It starts with \"" + item.answer().substring(0, 1) + "\" and has "
                        + item.answer().length() + " letters.", List.of());
                default -> {
                    SourcePassage source = store.sourcePassage(item);
                    String section = source == null ? "the material" : "\"" + source.sectionTitle() + "\"";
                    yield new Hint("Think about what " + section + " says.", List.of());
                }
            };
        }
    }

    @Override
    public SessionSummary finish(String sessionId) {
        SessionSummary summary;
        synchronized (store) {
            Running running = sessions.remove(sessionId);
            if (running == null) {
                throw new NotFoundException(SESSION_ENDED, "No session " + sessionId);
            }
            int answered = running.answers.size();
            int correct = (int) running.answers.values().stream().filter(Feedback::correct).count();
            int learned = (int) running.session.items().stream()
                    .filter(i -> i.isNew() && running.answers.containsKey(i.itemId())).count();
            List<ConceptChange> improved = new ArrayList<>();
            for (String courseId : courseIds(running.session.courseId())) {
                for (ConceptMastery after : progress.mastery(courseId)) {
                    Double before = running.masteryBefore.get(after.conceptId());
                    if (after.mastery() != null && (before == null || after.mastery() > before + 0.001)) {
                        improved.add(new ConceptChange(after.conceptId(), after.name(), before, after.mastery()));
                    }
                }
            }
            long now = store.now();
            Long nextReview = store.reviews.values().stream().filter(r -> r.dueAt() > now).map(ReviewState::dueAt)
                    .min(Long::compare).orElse(null);
            summary = new SessionSummary(sessionId, running.session.kind(), running.session.courseId(),
                    running.session.items().size(), answered, correct, answered == 0 ? 0 : correct / (double) answered,
                    now - running.startedAt, learned, improved.stream().limit(4).toList(), nextReview, store.streakDays());
        }
        return summary;
    }

    @Override
    public Feedback dispute(String attemptId) {
        synchronized (store) {
            Attempt attempt = store.attempts.stream().filter(a -> a.id().equals(attemptId)).findFirst()
                    .orElseThrow(() -> new NotFoundException(UserMessage.NOT_AVAILABLE, "No attempt " + attemptId));
            LearningItem item = store.item(attempt.itemId());
            Attempt accepted = new Attempt(attempt.id(), attempt.itemId(), attempt.sessionId(), attempt.response(), true, 1,
                    Rating.GOOD, attempt.confidence(), attempt.hintUsed(), attempt.durationMs(), attempt.gradedBy(),
                    "Marked correct after you disputed it.", true, attempt.createdAt(), store.now());
            store.attempts.set(store.attempts.indexOf(attempt), accepted);
            ReviewState next = schedule(item.id(), Rating.GOOD, store.now());
            store.reviews.put(item.id(), next);
            SourcePassage source = store.sourcePassage(item);
            return new Feedback(attempt.id(), item.id(), true, 1, Rating.GOOD, attempt.gradedBy(), item.answer(),
                    item.explanation(), accepted.feedback(), List.of(), source == null ? null : source.ref(),
                    next.dueAt(), next.scheduledDays(), false, true);
        }
    }

    private Running running(String sessionId) {
        Running running = sessions.get(sessionId);
        if (running == null) {
            throw new NotFoundException(SESSION_ENDED, "No session " + sessionId);
        }
        return running;
    }
}
