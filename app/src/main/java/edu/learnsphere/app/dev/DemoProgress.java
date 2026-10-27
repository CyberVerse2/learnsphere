package edu.learnsphere.app.dev;

import edu.learnsphere.core.model.Attempt;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatRole;
import edu.learnsphere.core.model.ChatThread;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseProgress;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DashboardData;
import edu.learnsphere.core.service.DayActivity;
import edu.learnsphere.core.service.ForecastDay;
import edu.learnsphere.core.service.ItemStats;
import edu.learnsphere.core.service.ModuleProgress;
import edu.learnsphere.core.service.ProgressService;
import edu.learnsphere.core.service.RecentQuestion;
import edu.learnsphere.core.service.RetentionPoint;
import edu.learnsphere.core.service.StudyNext;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link ProgressService} computed from the {@link DemoStore}'s attempts and review states. */
final class DemoProgress implements ProgressService {

    /** Weeks shown in the retention trend. */
    static final int RETENTION_WEEKS = 6;
    /** Minutes one review takes on average, for estimates. */
    static final double MINUTES_PER_REVIEW = 0.6;

    private final DemoStore store;

    /** The demo content (screenshot states that need more than the services offer). */
    DemoStore store() {
        return store;
    }

    DemoProgress(DemoStore store) {
        this.store = store;
    }

    @Override
    public DashboardData dashboard() {
        synchronized (store) {
            LocalDate today = store.today();
            List<CourseSummary> continuing = store.courses.values().stream()
                    .filter(c -> store.enrolled.contains(c.id()))
                    .sorted(Comparator.comparing((Course c) -> c.lastOpenedAt() == null ? 0L : c.lastOpenedAt()).reversed())
                    .map(c -> store.summary(c.id())).toList();
            List<DayActivity> week = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                LocalDate day = today.minusDays(i);
                week.add(new DayActivity(day, store.reviewsOn(day), i == 0));
            }
            return new DashboardData(store.profileName, today, studyNext(null), continuing, week, store.streakDays(),
                    store.reviewsOn(today), recentQuestions(4));
        }
    }

    @Override
    public List<DayActivity> activity(int days) {
        if (days < 1) {
            throw new IllegalArgumentException("days must be >= 1: " + days);
        }
        synchronized (store) {
            LocalDate today = store.today();
            List<DayActivity> list = new ArrayList<>(days);
            for (int i = days - 1; i >= 0; i--) {
                LocalDate day = today.minusDays(i);
                list.add(new DayActivity(day, store.reviewsOn(day), i == 0));
            }
            return list;
        }
    }

    /** What to study next in {@code courseId} (null for every course being studied). Caller holds the store. */
    StudyNext studyNext(String courseId) {
        long now = store.now();
        List<LearningItem> items = store.studyItems(courseId);
        int due = (int) items.stream().filter(i -> store.due(i.id(), now)).count();
        int fresh = (int) Math.min(10, items.stream().filter(i -> !store.started(i.id())).count());
        Long next = items.stream().map(i -> store.reviews.get(i.id())).filter(r -> r != null && r.dueAt() > now)
                .map(ReviewState::dueAt).min(Long::compare).orElse(null);
        int minutes = (int) Math.ceil((due + fresh) * MINUTES_PER_REVIEW);
        return new StudyNext(courseId, due, fresh, minutes, next);
    }

    List<RecentQuestion> recentQuestions(int limit) {
        List<RecentQuestion> list = new ArrayList<>();
        for (ChatThread thread : store.threads.values()) {
            List<ChatMessage> messages = store.messages.getOrDefault(thread.id(), List.of());
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage m = messages.get(i);
                if (m.role() != ChatRole.USER) {
                    continue;
                }
                ChatMessage answer = i + 1 < messages.size() ? messages.get(i + 1) : null;
                Course course = store.courses.get(thread.courseId());
                if (course == null) {
                    continue;
                }
                list.add(new RecentQuestion(thread.id(), course.id(), course.title(), course.coverHue(), m.content(),
                        answer == null ? "" : answer.content(), answer == null ? List.of() : answer.citations(),
                        m.createdAt()));
            }
        }
        list.sort(Comparator.comparingLong(RecentQuestion::askedAt).reversed());
        return list.stream().limit(limit).toList();
    }

    @Override
    public CourseProgress overview() {
        synchronized (store) {
            List<CourseSummary> courses = store.courses.values().stream()
                    .filter(c -> store.enrolled.contains(c.id())).map(c -> store.summary(c.id())).toList();
            List<ConceptMastery> concepts = new ArrayList<>();
            for (CourseSummary course : courses) {
                concepts.addAll(mastery(course.id()));
            }
            return build(null, null, null, courses.stream().map(CourseSummary::id).collect(Collectors.toSet()),
                    List.of(), concepts, courses);
        }
    }

    @Override
    public CourseProgress course(String courseId) {
        synchronized (store) {
            Course course = store.course(courseId);
            long now = store.now();
            List<ModuleProgress> modules = new ArrayList<>();
            for (CourseModule module : store.modulesOf(courseId)) {
                List<LearningItem> items = store.studyItems(courseId).stream()
                        .filter(i -> module.id().equals(i.moduleId())).toList();
                int started = (int) items.stream().filter(i -> store.started(i.id())).count();
                int due = (int) items.stream().filter(i -> store.due(i.id(), now)).count();
                List<ConceptMastery> moduleConcepts = mastery(courseId).stream()
                        .filter(c -> module.id().equals(c.moduleId())).toList();
                modules.add(new ModuleProgress(module.id(), module.title(), module.position(), items.size(), started, due,
                        items.isEmpty() ? 0 : started / (double) items.size(), average(moduleConcepts)));
            }
            return build(courseId, course.title(), course.coverHue(), Set.of(courseId), modules, mastery(courseId),
                    List.of());
        }
    }

    private CourseProgress build(String courseId, String title, Integer hue, Set<String> courseIds,
                                 List<ModuleProgress> modules, List<ConceptMastery> concepts, List<CourseSummary> courses) {
        long now = store.now();
        LocalDate today = store.today();
        List<LearningItem> items = store.items.values().stream()
                .filter(i -> courseIds.contains(i.courseId()) && i.status() == ItemStatus.APPROVED).toList();
        Set<String> itemIds = items.stream().map(LearningItem::id).collect(Collectors.toSet());
        List<Attempt> attempts = store.attempts.stream().filter(a -> itemIds.contains(a.itemId())).toList();
        double progress = items.isEmpty() ? 0 : items.stream().filter(i -> store.started(i.id())).count() / (double) items.size();
        Double accuracy = attempts.isEmpty() ? null : attempts.stream().filter(Attempt::correct).count() / (double) attempts.size();
        List<ForecastDay> forecast = new ArrayList<>();
        for (int d = 0; d < CourseProgress.FORECAST_DAYS; d++) {
            LocalDate day = today.plusDays(d);
            long end = store.startOfDay(day.plusDays(1));
            long start = d == 0 ? Long.MIN_VALUE : store.startOfDay(day);
            int count = (int) items.stream().map(i -> store.reviews.get(i.id()))
                    .filter(r -> r != null && r.dueAt() >= start && r.dueAt() < end && store.started(r.itemId())).count();
            forecast.add(new ForecastDay(day, count));
        }
        List<RetentionPoint> retention = new ArrayList<>();
        LocalDate thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        for (int w = RETENTION_WEEKS - 1; w >= 0; w--) {
            LocalDate weekStart = thisWeek.minusWeeks(w);
            long from = store.startOfDay(weekStart);
            long to = store.startOfDay(weekStart.plusWeeks(1));
            List<Attempt> week = attempts.stream().filter(a -> a.createdAt() >= from && a.createdAt() < to).toList();
            if (!week.isEmpty()) {
                retention.add(new RetentionPoint(weekStart, week.stream().filter(Attempt::correct).count()
                        / (double) week.size(), week.size()));
            }
        }
        long studyTime = attempts.stream().mapToLong(Attempt::durationMs).sum();
        List<ConceptMastery> sorted = concepts.stream()
                .sorted(Comparator.comparing((ConceptMastery c) -> c.mastery() == null ? 2.0 : c.mastery())).toList();
        return new CourseProgress(courseId, title, hue, progress, average(concepts), accuracy, attempts.size(),
                studyTime, store.streakDays(), modules, sorted, forecast, retention, courses);
    }

    private static Double average(List<ConceptMastery> concepts) {
        return concepts.stream().map(ConceptMastery::mastery).filter(m -> m != null)
                .mapToDouble(Double::doubleValue).average().stream().boxed().findFirst().orElse(null);
    }

    @Override
    public List<ConceptMastery> conceptMastery(String courseId) {
        synchronized (store) {
            store.course(courseId);
            return mastery(courseId);
        }
    }

    /** Mastery per concept of {@code courseId}: recent accuracy weighted by how much of it was studied. */
    List<ConceptMastery> mastery(String courseId) {
        List<ConceptMastery> list = new ArrayList<>();
        for (Concept concept : store.conceptsOf(courseId)) {
            List<LearningItem> items = store.studyItems(courseId).stream()
                    .filter(i -> store.itemConcepts.getOrDefault(i.id(), Set.of()).contains(concept.id())).toList();
            Set<String> ids = items.stream().map(LearningItem::id).collect(Collectors.toSet());
            List<Attempt> attempts = store.attempts.stream().filter(a -> ids.contains(a.itemId())).toList();
            int reviewed = (int) items.stream().filter(i -> store.started(i.id())).count();
            Double accuracy = attempts.isEmpty() ? null
                    : attempts.stream().filter(Attempt::correct).count() / (double) attempts.size();
            Double mastery = null;
            if (accuracy != null && !items.isEmpty()) {
                List<Attempt> recent = attempts.stream().sorted(Comparator.comparingLong(Attempt::createdAt).reversed())
                        .limit(6).toList();
                double recentAccuracy = recent.stream().filter(Attempt::correct).count() / (double) recent.size();
                mastery = Math.min(1, recentAccuracy * (0.45 + 0.55 * reviewed / items.size()));
            }
            list.add(new ConceptMastery(concept.id(), concept.name(), concept.moduleId(), mastery, accuracy, items.size(),
                    reviewed, attempts.size()));
        }
        return list;
    }

    @Override
    public List<ItemStats> itemStats(String courseId) {
        synchronized (store) {
            store.course(courseId);
            return store.itemsOf(courseId).stream().filter(item -> item.status() == ItemStatus.APPROVED)
                    .map(this::stats).sorted(HARDEST_FIRST).toList();
        }
    }

    /** Hardest (lowest accuracy) first, questions without answers last, then most answered, then by prompt. */
    private static final Comparator<ItemStats> HARDEST_FIRST = Comparator
            .comparing((ItemStats s) -> s.accuracy() == null ? 1 : 0)
            .thenComparing(s -> s.accuracy() == null ? 0.0 : s.accuracy())
            .thenComparing(Comparator.comparingInt(ItemStats::attempts).reversed())
            .thenComparing(s -> s.prompt().toLowerCase(java.util.Locale.ROOT))
            .thenComparing(ItemStats::itemId);

    @Override
    public ItemStats itemStatsFor(String itemId) {
        synchronized (store) {
            return stats(store.item(itemId));
        }
    }

    ItemStats stats(LearningItem item) {
        List<Attempt> attempts = store.attempts.stream().filter(a -> a.itemId().equals(item.id())).toList();
        int correct = (int) attempts.stream().filter(Attempt::correct).count();
        ReviewState state = store.reviews.get(item.id());
        Map<String, Flag> flags = store.flags;
        int open = (int) flags.values().stream().filter(f -> f.itemId().equals(item.id()) && f.resolvedAt() == null).count();
        Set<String> sessions = new HashSet<>();
        attempts.forEach(a -> sessions.add(a.sessionId()));
        DemoClassProgress.ItemResult classResult = store.classResults.get(item.id());
        return new ItemStats(item.id(), item.type(), item.prompt(), item.status(), attempts.size(), correct,
                attempts.isEmpty() ? null : correct / (double) attempts.size(),
                attempts.isEmpty() ? null : attempts.stream().mapToDouble(Attempt::score).average().orElse(0),
                attempts.isEmpty() ? null : (long) attempts.stream().mapToLong(Attempt::durationMs).average().orElse(0),
                attempts.isEmpty() ? null : attempts.getLast().createdAt(),
                state == null ? null : state.phase(), state == null ? null : state.dueAt(),
                state == null ? null : state.stability(), state == null ? 0 : state.lapses(), open,
                classResult == null ? 0 : classResult.learners(), classResult == null ? null : classResult.accuracy());
    }

    /** Every studying course (tests and the overview). */
    List<CourseSummary> studying() {
        return store.courses.keySet().stream().filter(store.enrolled::contains).map(store::summary).toList();
    }

    static CourseFilter studyingFilter() {
        return CourseFilter.studying();
    }
}
