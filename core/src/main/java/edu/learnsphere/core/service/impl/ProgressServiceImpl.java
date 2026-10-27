package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.db.StudyScope;
import edu.learnsphere.core.db.StudySessionRepository.ItemAttemptStats;
import edu.learnsphere.core.learning.ActivityCalendar;
import edu.learnsphere.core.learning.DayRange;
import edu.learnsphere.core.learning.MasteryCalculator;
import edu.learnsphere.core.learning.StudyPlanner;
import edu.learnsphere.core.learning.StudyQueries;
import edu.learnsphere.core.learning.StudyQueries.AttemptTotals;
import edu.learnsphere.core.learning.StudyQueries.ModuleCounts;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.service.AskService;
import edu.learnsphere.core.service.ClassInsightSummary;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseProgress;
import edu.learnsphere.core.service.CourseService;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DashboardData;
import edu.learnsphere.core.service.DayActivity;
import edu.learnsphere.core.service.ForecastDay;
import edu.learnsphere.core.service.ItemInsight;
import edu.learnsphere.core.service.ItemStats;
import edu.learnsphere.core.service.ModuleProgress;
import edu.learnsphere.core.service.ProgressService;
import edu.learnsphere.core.service.RetentionPoint;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.SettingsService;
import edu.learnsphere.core.service.StudyNext;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Progress views computed from the profile database: counts, accuracy, forecast and retention are aggregated in
 * SQLite ({@link StudyQueries}); mastery uses FSRS retrievability ({@link MasteryCalculator}); day and week
 * boundaries follow the learner's time zone (the clock's zone).
 *
 * <ul>
 *   <li>The overview and Study next cover the courses being studied ({@link StudyScope#enrolled()}); one
 *       course covers that course, enrolled or not.</li>
 *   <li>Progress is the share of approved items studied at least once; accuracy, reviews and study time count
 *       every attempt on the course's items.</li>
 *   <li>The forecast counts reviewed approved items falling due on each of the next 14 days (today includes
 *       overdue items). Retention is weekly (Monday to Sunday) over the last 12 weeks: reviews rated Hard or
 *       better out of reviews, where a review is an item's first answer of a day after it was answered on an
 *       earlier day ({@link StudyQueries#retentionPerDay}).</li>
 *   <li>Streak, the week strip and reviews today describe the learner's habit and count attempts in any
 *       course.</li>
 * </ul>
 *
 * Course cards come from the {@link CourseService} and recent questions from the {@link AskService}, so they
 * look the same everywhere. Thread-safe.
 */
public final class ProgressServiceImpl implements ProgressService {

    private static final Logger LOG = LoggerFactory.getLogger(ProgressServiceImpl.class);

    /** Courses under "Continue" on Home. */
    public static final int CONTINUE_COURSES = 6;
    /** Recent questions on Home. */
    public static final int RECENT_QUESTIONS = 5;
    /** Days in the week strip. */
    public static final int WEEK_DAYS = 7;
    /** Weeks in the retention trend. */
    public static final int RETENTION_WEEKS = 12;

    private final Repositories repos;
    private final SettingsService settings;
    private final CourseService courses;
    private final AskService ask;
    private final Function<String, ClassInsightSummary> classInsights;
    private final Supplier<String> profileName;
    private final Clock clock;
    private final StudyQueries queries;
    private final MasteryCalculator mastery;
    private final StudyPlanner planner;
    private final ActivityCalendar calendar;

    /**
     * @param courses       course cards for Home and the course picker
     * @param ask           recent questions for Home
     * @param classInsights imported class progress of a course created here (for creator item statistics);
     *                      may return null or throw when there is none
     * @param profileName   the open profile's display name, for the greeting
     */
    public ProgressServiceImpl(Repositories repos, SettingsService settings, CourseService courses, AskService ask,
                               Function<String, ClassInsightSummary> classInsights, Supplier<String> profileName,
                               Clock clock) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.courses = Objects.requireNonNull(courses, "courses");
        this.ask = Objects.requireNonNull(ask, "ask");
        this.classInsights = Objects.requireNonNull(classInsights, "classInsights");
        this.profileName = Objects.requireNonNull(profileName, "profileName");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.queries = new StudyQueries(repos.database());
        this.mastery = new MasteryCalculator(queries);
        this.planner = new StudyPlanner(repos, queries, mastery, clock);
        this.calendar = new ActivityCalendar(queries, clock);
    }

    // ---- home ---------------------------------------------------------------------------------

    @Override
    public DashboardData dashboard() {
        LocalDate today = calendar.today();
        StudyNext studyNext = planner.studyNext(null, StudyScope.enrolled(), settings.get(SettingKeys.NEW_ITEMS_PER_DAY));
        List<CourseSummary> continueCourses = courses.list(CourseFilter.studying()).stream().limit(CONTINUE_COURSES).toList();
        List<DayActivity> week = calendar.lastDays(WEEK_DAYS);
        int reviewsToday = week.getLast().reviews();
        return new DashboardData(profileName.get(), today, studyNext, continueCourses, week, calendar.streakDays(),
                reviewsToday, ask.recentQuestions(RECENT_QUESTIONS));
    }

    // ---- progress screen ----------------------------------------------------------------------

    @Override
    public CourseProgress overview() {
        List<CourseSummary> studying = courses.list(CourseFilter.studying());
        return progress(null, StudyScope.enrolled(), studying);
    }

    @Override
    public CourseProgress course(String courseId) {
        Course course = repos.courses().get(courseId);
        return progress(course, StudyScope.course(course.id()), List.of());
    }

    private CourseProgress progress(Course course, StudyScope scope, List<CourseSummary> picker) {
        long now = clock.millis();
        LocalDate today = calendar.today();

        Map<String, ModuleCounts> counts = queries.itemCountsByModule(scope, now);
        int approved = counts.values().stream().mapToInt(ModuleCounts::approved).sum();
        int started = counts.values().stream().mapToInt(ModuleCounts::started).sum();
        List<ConceptMastery> concepts = mastery.conceptMastery(scope, now);
        AttemptTotals totals = queries.attemptTotals(scope);

        List<ModuleProgress> modules = course == null ? List.of() : moduleProgress(course.id(), counts, concepts);
        return new CourseProgress(course == null ? null : course.id(), course == null ? null : course.title(),
                course == null ? null : course.coverHue(), approved == 0 ? 0 : (double) started / approved,
                mean(concepts.stream().map(ConceptMastery::mastery).toList()), totals.accuracy(), totals.attempts(),
                totals.durationMs(), calendar.streakDays(), modules, concepts, forecast(scope, today),
                retention(scope, today), picker);
    }

    private List<ModuleProgress> moduleProgress(String courseId, Map<String, ModuleCounts> counts,
                                                List<ConceptMastery> concepts) {
        Map<String, List<Double>> masteryByModule = new HashMap<>();
        for (ConceptMastery c : concepts) {
            if (c.moduleId() != null) {
                masteryByModule.computeIfAbsent(c.moduleId(), k -> new ArrayList<>()).add(c.mastery());
            }
        }
        List<ModuleProgress> result = new ArrayList<>();
        for (CourseModule module : repos.modules().listByCourse(courseId)) {
            ModuleCounts c = counts.getOrDefault(module.id(), new ModuleCounts(module.id(), 0, 0, 0));
            result.add(new ModuleProgress(module.id(), module.title(), module.position(), c.approved(), c.started(), c.due(),
                    c.approved() == 0 ? 0 : (double) c.started() / c.approved(),
                    mean(masteryByModule.getOrDefault(module.id(), List.of()))));
        }
        return result;
    }

    private List<ForecastDay> forecast(StudyScope scope, LocalDate today) {
        List<DayRange> days = DayRange.days(today, CourseProgress.FORECAST_DAYS, clock.getZone());
        int[] due = queries.duePerRange(scope, days);
        List<ForecastDay> forecast = new ArrayList<>(days.size());
        for (int i = 0; i < days.size(); i++) {
            forecast.add(new ForecastDay(days.get(i).first(), due[i]));
        }
        return forecast;
    }

    private List<RetentionPoint> retention(StudyScope scope, LocalDate today) {
        List<DayRange> weeks = DayRange.weeksEndingWith(today, RETENTION_WEEKS, clock.getZone());
        List<DayRange> days = DayRange.days(weeks.getFirst().first(), RETENTION_WEEKS * 7, clock.getZone());
        int[][] perDay = queries.retentionPerDay(scope, days);
        List<RetentionPoint> points = new ArrayList<>();
        for (int w = 0; w < weeks.size(); w++) {
            int reviews = 0;
            int recalled = 0;
            for (int d = w * 7; d < (w + 1) * 7; d++) {
                reviews += perDay[d][0];
                recalled += perDay[d][1];
            }
            if (reviews > 0) {
                points.add(new RetentionPoint(weeks.get(w).first(), (double) recalled / reviews, reviews));
            }
        }
        return points;
    }

    /** Longest stretch {@link #activity(int)} returns (a year). */
    public static final int MAX_ACTIVITY_DAYS = 366;

    @Override
    public List<DayActivity> activity(int days) {
        if (days < 1 || days > MAX_ACTIVITY_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_ACTIVITY_DAYS + ": " + days);
        }
        return calendar.lastDays(days);
    }

    @Override
    public List<ConceptMastery> conceptMastery(String courseId) {
        Course course = repos.courses().get(courseId);
        return mastery.conceptMastery(StudyScope.course(course.id()), clock.millis());
    }

    // ---- creator statistics -------------------------------------------------------------------

    @Override
    public List<ItemStats> itemStats(String courseId) {
        Course course = repos.courses().get(courseId);
        List<LearningItem> items = repos.items().listByCourse(course.id(), ItemStatus.APPROVED);
        Context context = context(course, items.stream().map(LearningItem::id).toList());
        List<ItemStats> stats = new ArrayList<>(items.size());
        items.forEach(item -> stats.add(stats(item, context)));
        stats.sort(HARDEST_FIRST);
        return stats;
    }

    @Override
    public ItemStats itemStatsFor(String itemId) {
        LearningItem item = repos.items().get(itemId);
        Course course = repos.courses().get(item.courseId());
        return stats(item, context(course, List.of(item.id())));
    }

    /** Hardest (lowest accuracy) first, items without attempts last, then most attempted, then by prompt. */
    static final Comparator<ItemStats> HARDEST_FIRST = Comparator
            .comparing((ItemStats s) -> s.accuracy() == null ? 1 : 0)
            .thenComparing(s -> s.accuracy() == null ? 0.0 : s.accuracy())
            .thenComparing(Comparator.comparingInt(ItemStats::attempts).reversed())
            .thenComparing(s -> s.prompt().toLowerCase(Locale.ROOT))
            .thenComparing(ItemStats::itemId);

    /** Everything item statistics of one course need, loaded once. */
    private record Context(Map<String, ItemAttemptStats> attempts, Map<String, ReviewState> states,
                           Map<String, Integer> openFlags, Map<String, ItemInsight> classItems) {
    }

    private Context context(Course course, List<String> itemIds) {
        return new Context(repos.sessions().itemStats(course.id()), repos.reviews().findAll(itemIds),
                repos.flags().countOpenByItem(course.id()), classItems(course));
    }

    private Map<String, ItemInsight> classItems(Course course) {
        if (!course.isCreatedHere()) {
            return Map.of();
        }
        try {
            ClassInsightSummary summary = classInsights.apply(course.id());
            Map<String, ItemInsight> byItem = new HashMap<>();
            if (summary != null) {
                summary.items().forEach(i -> byItem.put(i.itemId(), i));
            }
            return byItem;
        } catch (RuntimeException e) {
            LOG.warn("Class progress of course {} could not be read; item statistics show this profile only", course.id(), e);
            return Map.of();
        }
    }

    private static ItemStats stats(LearningItem item, Context context) {
        ItemAttemptStats a = context.attempts().get(item.id());
        ReviewState state = context.states().get(item.id());
        boolean seen = state != null && state.phase() != ReviewPhase.NEW;
        ItemInsight cls = context.classItems().get(item.id());
        return new ItemStats(item.id(), item.type(), item.prompt(), item.status(),
                a == null ? 0 : a.attempts(), a == null ? 0 : a.correct(), a == null ? null : a.accuracy(),
                a == null ? null : a.averageScore(), a == null ? null : a.averageDurationMs(),
                a == null ? null : a.lastAttemptAt(),
                seen ? state.phase() : null, seen ? state.dueAt() : null, seen ? state.stability() : null,
                state == null ? 0 : state.lapses(), context.openFlags().getOrDefault(item.id(), 0),
                cls == null ? 0 : cls.learners(), cls == null ? null : cls.accuracy());
    }

    private static Double mean(List<Double> values) {
        double sum = 0;
        int n = 0;
        for (Double v : values) {
            if (v != null) {
                sum += v;
                n++;
            }
        }
        return n == 0 ? null : sum / n;
    }
}
