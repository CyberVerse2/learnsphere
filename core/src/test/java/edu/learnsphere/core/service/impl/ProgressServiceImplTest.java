package edu.learnsphere.core.service.impl;

import static edu.learnsphere.core.learning.StudyData.DAY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.learning.DayRange;
import edu.learnsphere.core.learning.StudyData;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.model.SessionRecord;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.AskService;
import edu.learnsphere.core.service.ClassInsightSummary;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.CourseProgress;
import edu.learnsphere.core.service.CourseService;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DashboardData;
import edu.learnsphere.core.service.DayActivity;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.ForecastDay;
import edu.learnsphere.core.service.ItemInsight;
import edu.learnsphere.core.service.ItemStats;
import edu.learnsphere.core.service.ModuleProgress;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.RetentionPoint;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.testing.MutableClock;
import edu.learnsphere.core.testing.TestDb;
import edu.learnsphere.core.util.Ids;
import java.nio.file.Path;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProgressServiceImplTest {

    @TempDir
    Path dir;
    TestDb t;
    StudyData data;
    EventBus events;
    SettingsServiceImpl settings;
    CourseService courses;
    AskService ask;

    @BeforeEach
    void open() {
        t = TestDb.open(dir);
        data = new StudyData(t);
        events = new EventBus();
        settings = new SettingsServiceImpl(t.repos.settings(), events);
        courses = mock(CourseService.class);
        ask = mock(AskService.class);
    }

    @AfterEach
    void close() {
        events.close();
        t.close();
    }

    private ProgressServiceImpl progress(Function<String, ClassInsightSummary> classInsights, Clock clock) {
        return new ProgressServiceImpl(t.repos, settings, courses, ask, classInsights, () -> "Ada", clock);
    }

    private ProgressServiceImpl progress() {
        return progress(id -> null, t.clock);
    }

    private static CourseSummary summary(Course c) {
        return new CourseSummary(c.id(), c.title(), c.subtitle(), c.authorName(), c.coverHue(), c.origin(), c.status(),
                c.visibility(), c.versionNumber(), c.isCreatedHere(), true, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.0, null,
                c.updatedAt());
    }

    @Test
    void aNewProfileHasAnEmptyDashboard() {
        DashboardData dashboard = progress().dashboard();

        assertThat(dashboard.isEmpty()).isTrue();
        assertThat(dashboard.profileName()).isEqualTo("Ada");
        assertThat(dashboard.today()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(dashboard.streakDays()).isZero();
        assertThat(dashboard.reviewsToday()).isZero();
        assertThat(dashboard.week()).hasSize(7).extracting(DayActivity::reviews).containsOnly(0);
        assertThat(dashboard.week().getLast()).isEqualTo(new DayActivity(LocalDate.of(2026, 3, 2), 0, true));
        assertThat(dashboard.studyNext().caughtUp()).isTrue();
        verify(ask).recentQuestions(ProgressServiceImpl.RECENT_QUESTIONS);

        CourseProgress overview = progress().overview();
        assertThat(overview.courseId()).isNull();
        assertThat(overview.progress()).isZero();
        assertThat(overview.accuracy()).isNull();
        assertThat(overview.averageMastery()).isNull();
        assertThat(overview.totalReviews()).isZero();
        assertThat(overview.forecast()).hasSize(CourseProgress.FORECAST_DAYS).extracting(ForecastDay::dueCount).containsOnly(0);
        assertThat(overview.retention()).isEmpty();
    }

    // ---- thirty days of study -----------------------------------------------------------------

    /** What the simulated learner did, tallied independently of the services. */
    private static final class Tally {
        int attempts;
        int correct;
        long durationMs;
        int reviews;
        int recalled;
        final Map<String, LocalDate> lastDayByItem = new HashMap<>();
        final Map<String, Integer> attemptsByItem = new HashMap<>();
        final Map<String, Integer> correctByItem = new HashMap<>();
        final Map<LocalDate, Integer> attemptsByDay = new TreeMap<>();
        final Map<LocalDate, Set<String>> introducedByDay = new TreeMap<>();
    }

    private static final long ANSWER_MS = 15_000;
    private static final int NEW_PER_DAY = 2;

    @Test
    void thirtyDaysOfStudyGiveSensibleProgressNumbers() {
        // The simulation runs from Sunday 1 February to Monday 2 March 2026 (today), skipping 10 February.
        Instant firstDay = MutableClock.START.minus(Duration.ofDays(29));
        t.clock.set(firstDay);
        settings.set(SettingKeys.NEW_ITEMS_PER_DAY, NEW_PER_DAY);
        Course course = t.enrolledCourse("Biology");
        CourseModule cells = t.module(course, "Cells");
        CourseModule genetics = t.module(course, "Genetics");
        CourseModule ecology = t.module(course, "Ecology");
        Map<String, Integer> errorRate = new HashMap<>();
        Map<String, CourseModule> moduleOf = new HashMap<>();
        List<LearningItem> items = new ArrayList<>();
        List<Object[]> plan = List.of(
                new Object[] {cells, "Membranes", 5, 10},
                new Object[] {cells, "Organelles", 5, 10},
                new Object[] {genetics, "DNA", 5, 10},
                new Object[] {genetics, "Inheritance", 5, 90},
                new Object[] {ecology, "Food webs", 10, 10},
                new Object[] {ecology, null, 40, 10});
        ItemType[] types = {ItemType.MCQ, ItemType.CLOZE, ItemType.FLASHCARD};
        for (Object[] group : plan) {
            CourseModule module = (CourseModule) group[0];
            String conceptName = (String) group[1];
            Concept concept = conceptName == null ? null : t.concept(course, module, conceptName);
            for (int i = 0; i < (int) group[2]; i++) {
                LearningItem item = data.item(course, module, types[items.size() % types.length],
                        (conceptName == null ? module.title() : conceptName) + " " + (i + 1));
                if (concept != null) {
                    data.tag(item, concept);
                }
                errorRate.put(item.id(), (int) group[3]);
                moduleOf.put(item.id(), module);
                items.add(item);
            }
        }
        LearningItem draft = t.item(course, cells, ItemType.MCQ, ItemStatus.DRAFT, "Not approved yet");

        PracticeServiceImpl practice = new PracticeServiceImpl(t.repos, settings, (q, r, a, c, token) -> {
            throw new AssertionError("no short answers in this course");
        }, events, t.clock);
        Tally tally = new Tally();
        for (int day = 0; day < 30; day++) {
            t.clock.set(firstDay.plus(Duration.ofDays(day)));
            if (day != 9) {
                studyDay(practice, course, day, errorRate, tally);
            }
        }

        long now = t.now();
        LocalDate today = LocalDate.of(2026, 3, 2);
        Set<String> seen = tally.attemptsByItem.keySet();
        assertThat(seen).as("a month of study meets most items").hasSizeGreaterThan(40).doesNotContain(draft.id());
        assertThat(tally.attempts).isGreaterThan(200);

        // ---- Home ----
        when(courses.list(any())).thenReturn(List.of(summary(course)));
        ProgressServiceImpl service = progress();
        DashboardData dashboard = service.dashboard();
        assertThat(dashboard.today()).isEqualTo(today);
        assertThat(dashboard.continueCourses()).extracting(CourseSummary::id).containsExactly(course.id());
        assertThat(dashboard.streakDays()).as("every day since the skipped 10 February").isEqualTo(20);
        List<DayActivity> expectedWeek = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            expectedWeek.add(new DayActivity(date, tally.attemptsByDay.getOrDefault(date, 0), i == 0));
        }
        assertThat(dashboard.week()).isEqualTo(expectedWeek);
        assertThat(dashboard.week()).extracting(DayActivity::reviews).allMatch(n -> n > 0);
        assertThat(dashboard.reviewsToday()).isEqualTo(tally.attemptsByDay.get(today));

        int dueNow = 0;
        List<ReviewState> states = new ArrayList<>();
        for (String id : seen) {
            ReviewState state = t.repos.reviews().find(id).orElseThrow();
            states.add(state);
            if (state.dueAt() <= now) {
                dueNow++;
            }
        }
        int introducedToday = tally.introducedByDay.getOrDefault(today, Set.of()).size();
        assertThat(dashboard.studyNext().dueCount()).isEqualTo(dueNow);
        assertThat(dashboard.studyNext().newCount())
                .isEqualTo(Math.min(Math.max(0, NEW_PER_DAY - introducedToday), items.size() - seen.size()));

        // ---- Course progress ----
        CourseProgress progress = service.course(course.id());
        assertThat(progress.title()).isEqualTo("Biology");
        assertThat(progress.progress()).isCloseTo((double) seen.size() / items.size(), within(1e-9));
        assertThat(progress.totalReviews()).isEqualTo(tally.attempts);
        assertThat(progress.accuracy()).isCloseTo((double) tally.correct / tally.attempts, within(1e-9));
        assertThat(progress.studyTimeMs()).isEqualTo(tally.durationMs);
        assertThat(progress.streakDays()).isEqualTo(20);

        assertThat(progress.modules()).extracting(ModuleProgress::title).containsExactly("Cells", "Genetics", "Ecology");
        for (ModuleProgress module : progress.modules()) {
            long inModule = items.stream().filter(i -> moduleOf.get(i.id()).id().equals(module.moduleId())).count();
            long started = seen.stream().filter(id -> moduleOf.get(id).id().equals(module.moduleId())).count();
            assertThat(module.itemCount()).isEqualTo((int) inModule);
            assertThat(module.startedCount()).isEqualTo((int) started);
            assertThat(module.completion()).isCloseTo((double) started / inModule, within(1e-9));
        }
        assertThat(progress.modules().get(0).completion()).as("new items come in module order").isEqualTo(1.0);
        assertThat(progress.modules().get(1).completion()).isEqualTo(1.0);
        assertThat(progress.modules().get(2).completion()).isBetween(0.1, 0.99);
        assertThat(progress.modules().get(1).mastery()).as("Genetics holds the weak concept")
                .isLessThan(progress.modules().get(0).mastery());

        List<ConceptMastery> concepts = progress.concepts();
        assertThat(concepts).extracting(ConceptMastery::name)
                .containsExactlyInAnyOrder("Membranes", "Organelles", "DNA", "Inheritance", "Food webs");
        ConceptMastery weakest = concepts.getFirst();
        assertThat(weakest.name()).isEqualTo("Inheritance");
        assertThat(weakest.accuracy()).isLessThan(0.5);
        assertThat(weakest.isWeak()).as("a concept just answered mostly wrong is weak right away").isTrue();
        for (ConceptMastery concept : concepts.subList(1, concepts.size())) {
            assertThat(concept.mastery()).isBetween(weakest.mastery() + 0.15, 1.0);
            assertThat(concept.isWeak()).as(concept.name()).isFalse();
            assertThat(concept.accuracy()).isGreaterThan(0.7);
        }
        assertThat(progress.averageMastery()).isBetween(weakest.mastery(), 1.0);
        assertThat(service.conceptMastery(course.id())).isEqualTo(concepts);

        // The forecast counts reviewed items falling due on each of the next 14 days (today with overdue ones).
        List<ForecastDay> forecast = progress.forecast();
        assertThat(forecast).hasSize(CourseProgress.FORECAST_DAYS);
        for (int i = 0; i < forecast.size(); i++) {
            LocalDate date = today.plusDays(i);
            long end = DayRange.startOf(date.plusDays(1), ZoneOffset.UTC);
            long start = i == 0 ? Long.MIN_VALUE : DayRange.startOf(date, ZoneOffset.UTC);
            long expected = states.stream().filter(s -> s.dueAt() >= start && s.dueAt() < end).count();
            assertThat(forecast.get(i)).isEqualTo(new ForecastDay(date, (int) expected));
        }
        assertThat(forecast.stream().mapToInt(ForecastDay::dueCount).sum()).isPositive();

        // Retention: per week, reviews rated Hard or better, where a review is an item's first answer of a day
        // after it was answered on an earlier day.
        List<RetentionPoint> retention = progress.retention();
        assertThat(retention).isNotEmpty();
        assertThat(retention.getLast().weekStart()).isEqualTo(today);
        assertThat(retention).allSatisfy(p -> {
            assertThat(p.weekStart().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
            assertThat(p.retention()).isBetween(0.0, 1.0);
            assertThat(p.reviews()).isPositive();
        });
        int reviews = retention.stream().mapToInt(RetentionPoint::reviews).sum();
        double recalled = retention.stream().mapToDouble(p -> p.retention() * p.reviews()).sum();
        assertThat(reviews).isEqualTo(tally.reviews);
        assertThat(recalled).isCloseTo(tally.recalled, within(1e-6));
        assertThat(tally.reviews).as("tries after Again and learning steps the same day are left out")
                .isPositive().isLessThan(tally.attempts - seen.size());

        // ---- Creator statistics ----
        List<ItemStats> stats = service.itemStats(course.id());
        assertThat(stats).hasSize(items.size()).extracting(ItemStats::itemId).doesNotContain(draft.id());
        assertThat(stats.getFirst().prompt()).startsWith("Inheritance");
        for (ItemStats s : stats) {
            int attempts = tally.attemptsByItem.getOrDefault(s.itemId(), 0);
            assertThat(s.attempts()).isEqualTo(attempts);
            assertThat(s.correct()).isEqualTo(tally.correctByItem.getOrDefault(s.itemId(), 0));
            if (attempts == 0) {
                assertThat(s.accuracy()).isNull();
                assertThat(s.phase()).isNull();
                assertThat(s.dueAt()).isNull();
            } else {
                assertThat(s.averageDurationMs()).isEqualTo(ANSWER_MS);
                assertThat(s.phase()).isIn(ReviewPhase.LEARNING, ReviewPhase.REVIEW, ReviewPhase.RELEARNING);
            }
        }
        List<ItemStats> attempted = stats.stream().filter(s -> s.accuracy() != null).toList();
        assertThat(stats.subList(attempted.size(), stats.size())).allMatch(s -> s.accuracy() == null);
        for (int i = 1; i < attempted.size(); i++) {
            assertThat(attempted.get(i).accuracy()).isGreaterThanOrEqualTo(attempted.get(i - 1).accuracy());
        }

        // ---- Overview ----
        CourseProgress overview = service.overview();
        assertThat(overview.courseId()).isNull();
        assertThat(overview.courses()).extracting(CourseSummary::id).containsExactly(course.id());
        assertThat(overview.totalReviews()).isEqualTo(tally.attempts);
        assertThat(overview.modules()).isEmpty();
        assertThat(overview.forecast()).isEqualTo(forecast);

        // By the next morning what was recalled has faded a little: the weak concept is no stronger, and the
        // others have lost some mastery too.
        t.clock.advance(Duration.ofHours(20));
        List<ConceptMastery> nextMorning = service.conceptMastery(course.id());
        assertThat(nextMorning.getFirst().name()).isEqualTo("Inheritance");
        assertThat(nextMorning.getFirst().mastery()).isLessThanOrEqualTo(weakest.mastery());
        assertThat(nextMorning.getFirst().isWeak()).isTrue();
        assertThat(nextMorning.stream().mapToDouble(ConceptMastery::mastery).sum())
                .isLessThan(concepts.stream().mapToDouble(ConceptMastery::mastery).sum());
    }

    private void studyDay(PracticeServiceImpl practice, Course course, int day, Map<String, Integer> errorRate, Tally tally) {
        LocalDate date = LocalDate.ofInstant(t.clock.instant(), ZoneOffset.UTC);
        StudySession session = practice.start(SessionRequest.studyNext(course.id()));
        for (SessionItem shown : session.items()) {
            LearningItem item = t.repos.items().get(shown.itemId());
            for (int attempt = 0; attempt < PracticeServiceImpl.MAX_TRIES_PER_SESSION; attempt++) {
                // A fixed draw per item, day and try keeps the month reproducible.
                long seed = ((long) item.prompt().hashCode() << 16) ^ (day * 8L + attempt);
                boolean right = new SplittableRandom(seed).nextInt(100) >= errorRate.get(item.id());
                Feedback feedback = practice.submit(session.id(), response(item, right));
                t.clock.advance(Duration.ofMillis(ANSWER_MS));

                LocalDate lastDay = tally.lastDayByItem.put(item.id(), date);
                if (lastDay == null) {
                    tally.introducedByDay.computeIfAbsent(date, d -> new HashSet<>()).add(item.id());
                } else if (!lastDay.equals(date)) {
                    // The item's first answer of the day, after it was answered on an earlier day: a review.
                    tally.reviews++;
                    if (feedback.rating() != Rating.AGAIN) {
                        tally.recalled++;
                    }
                }
                tally.attempts++;
                tally.durationMs += ANSWER_MS;
                tally.attemptsByItem.merge(item.id(), 1, Integer::sum);
                tally.attemptsByDay.merge(date, 1, Integer::sum);
                if (feedback.correct()) {
                    tally.correct++;
                    tally.correctByItem.merge(item.id(), 1, Integer::sum);
                }
                assertThat(feedback.correct()).isEqualTo(right);
                if (feedback.rating() != Rating.AGAIN) {
                    break;
                }
            }
        }
        practice.finish(session.id());
    }

    private static Response response(LearningItem item, boolean right) {
        return switch (item.type()) {
            case FLASHCARD -> Response.flashcard(item.id(), right ? Rating.GOOD : Rating.AGAIN, ANSWER_MS);
            case MCQ -> Response.answer(item.id(), right ? item.answer() : item.distractors().getFirst(), null, false, ANSWER_MS);
            default -> Response.answer(item.id(), right ? item.answer() : "nothing", null, false, ANSWER_MS);
        };
    }

    // ---- scope and time zone ------------------------------------------------------------------

    @Test
    void theOverviewCoversCoursesBeingStudiedWhileHabitsCountEverything() {
        Course studied = t.enrolledCourse("Biology");
        Course mine = t.course("Draft course");
        LearningItem x = data.item(studied, null, ItemType.MCQ, "x");
        data.item(studied, null, ItemType.MCQ, "x2");
        LearningItem y = data.item(mine, null, ItemType.MCQ, "y");
        SessionRecord session = t.session(null);
        long yesterday = t.now() - DAY;
        data.attempt(x, session, true, yesterday);
        data.state(x, 3, 1, t.now() + 2 * DAY);
        data.attempt(y, session, true, yesterday);
        data.attempt(y, session, false, yesterday + 1);
        data.state(y, 3, 1, t.now() + 2 * DAY);
        when(courses.list(any())).thenReturn(List.of(summary(studied)));

        CourseProgress overview = progress().overview();
        assertThat(overview.totalReviews()).isEqualTo(1);
        assertThat(overview.accuracy()).isEqualTo(1.0);
        assertThat(overview.progress()).isEqualTo(0.5);
        assertThat(overview.courses()).extracting(CourseSummary::id).containsExactly(studied.id());
        assertThat(overview.forecast().get(2).dueCount()).isEqualTo(1);

        CourseProgress own = progress().course(mine.id());
        assertThat(own.totalReviews()).isEqualTo(2);
        assertThat(own.accuracy()).isEqualTo(0.5);
        assertThat(own.coverHue()).isEqualTo(mine.coverHue());

        DashboardData dashboard = progress().dashboard();
        assertThat(dashboard.week().get(5).reviews()).isEqualTo(3);
        assertThat(dashboard.streakDays()).as("today has no reviews yet, which keeps the streak").isEqualTo(1);
        assertThat(dashboard.studyNext().newCount()).as("only courses being studied").isEqualTo(1);
    }

    @Test
    void daysFollowTheLearnersTimeZone() {
        Course course = t.enrolledCourse("Biology");
        LearningItem item = data.item(course, null, ItemType.MCQ, "x");
        SessionRecord session = t.session(course);
        // 03:00 UTC on 2 March is 22:00 on 1 March in New York; 06:00 UTC on 1 March is 01:00 there.
        data.attempt(item, session, true, Instant.parse("2026-03-02T03:00:00Z").toEpochMilli());
        data.attempt(item, session, true, Instant.parse("2026-03-01T06:00:00Z").toEpochMilli());

        DashboardData utc = progress().dashboard();
        assertThat(utc.streakDays()).isEqualTo(2);
        assertThat(utc.reviewsToday()).isEqualTo(1);

        Clock newYork = new MutableClock(t.clock.instant(), ZoneId.of("America/New_York"));
        DashboardData local = progress(id -> null, newYork).dashboard();
        assertThat(local.today()).isEqualTo(LocalDate.of(2026, 3, 2));
        assertThat(local.reviewsToday()).isZero();
        assertThat(local.week().get(5)).isEqualTo(new DayActivity(LocalDate.of(2026, 3, 1), 2, false));
        assertThat(local.streakDays()).isEqualTo(1);
    }

    // ---- creator statistics -------------------------------------------------------------------

    @Test
    void itemStatisticsAddTheClassForCoursesCreatedHere() {
        Course course = t.enrolledCourse("Biology");
        LearningItem hard = data.item(course, null, ItemType.MCQ, "hard");
        LearningItem easy = data.item(course, null, ItemType.CLOZE, "easy");
        LearningItem untried = data.item(course, null, ItemType.FLASHCARD, "untried");
        t.item(course, null, ItemType.MCQ, ItemStatus.REJECTED, "rejected");
        SessionRecord session = t.session(course);
        data.attempt(hard, session, false, t.now() - 3 * DAY);
        data.attempt(hard, session, false, t.now() - 2 * DAY);
        data.attempt(hard, session, true, t.now() - DAY);
        data.attempt(easy, session, true, t.now() - 2 * DAY);
        data.attempt(easy, session, true, t.now() - DAY);
        data.state(hard, 1.5, 1, t.now() + DAY);
        data.state(easy, 9, 1, t.now() + 8 * DAY);
        AtomicInteger asked = new AtomicInteger();
        Function<String, ClassInsightSummary> classInsights = id -> {
            asked.incrementAndGet();
            return new ClassInsightSummary(id, List.of(), List.of(),
                    List.of(new ItemInsight(hard.id(), "hard", 12, 0.5, 4)));
        };

        List<ItemStats> stats = progress(classInsights, t.clock).itemStats(course.id());
        assertThat(stats).extracting(ItemStats::prompt).containsExactly("hard", "easy", "untried");
        ItemStats first = stats.getFirst();
        assertThat(first.attempts()).isEqualTo(3);
        assertThat(first.correct()).isEqualTo(1);
        assertThat(first.accuracy()).isCloseTo(1.0 / 3, within(1e-9));
        assertThat(first.averageDurationMs()).isEqualTo(10_000);
        assertThat(first.lastAttemptAt()).isEqualTo(t.now() - DAY);
        assertThat(first.phase()).isEqualTo(ReviewPhase.REVIEW);
        assertThat(first.stability()).isEqualTo(1.5);
        assertThat(first.classLearners()).isEqualTo(4);
        assertThat(first.classAccuracy()).isEqualTo(0.5);
        assertThat(stats.get(1).classLearners()).isZero();
        assertThat(stats.get(2).attempts()).isZero();
        assertThat(stats.get(2).accuracy()).isNull();
        assertThat(stats.get(2).phase()).isNull();
        assertThat(asked).hasValue(1);

        ItemStats one = progress(classInsights, t.clock).itemStatsFor(easy.id());
        assertThat(one.accuracy()).isEqualTo(1.0);
        assertThat(one.dueAt()).isEqualTo(t.now() + 8 * DAY);

        List<ItemStats> withoutClass = progress(id -> {
            throw new IllegalStateException("unreadable");
        }, t.clock).itemStats(course.id());
        assertThat(withoutClass).extracting(ItemStats::classLearners).containsOnly(0);

        Course imported = new Course(Ids.newId(), "Chemistry", "", "", "Grace", CourseOrigin.IMPORTED, Visibility.PRIVATE,
                CourseStatus.PUBLISHED, 30, 1, t.now(), Ids.newId(), null, t.now(), t.now());
        t.repos.courses().insert(imported);
        data.item(imported, null, ItemType.MCQ, "imported");
        asked.set(0);
        assertThat(progress(classInsights, t.clock).itemStats(imported.id())).hasSize(1);
        assertThat(asked).as("class progress belongs to courses created here").hasValue(0);
    }
}
