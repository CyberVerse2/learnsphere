package edu.learnsphere.app.dev;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.AskService;
import edu.learnsphere.core.service.AssistantService;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseProgress;
import edu.learnsphere.core.service.CourseService;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.DashboardData;
import edu.learnsphere.core.service.DayActivity;
import edu.learnsphere.core.service.DocumentService;
import edu.learnsphere.core.service.ForecastDay;
import edu.learnsphere.core.service.GenerationService;
import edu.learnsphere.core.service.ItemService;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.ModuleProgress;
import edu.learnsphere.core.service.PracticeService;
import edu.learnsphere.core.service.ProfileContext;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.ProgressService;
import edu.learnsphere.core.service.RecentQuestion;
import edu.learnsphere.core.service.RetentionPoint;
import edu.learnsphere.core.service.SettingsService;
import edu.learnsphere.core.service.SharingService;
import edu.learnsphere.core.service.StudyNext;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.service.WorkspaceFactory;
import edu.learnsphere.core.service.impl.AssistantServiceImpl;
import edu.learnsphere.core.service.impl.JobQueueImpl;
import edu.learnsphere.core.service.impl.SettingsServiceImpl;
import edu.learnsphere.core.model.Citation;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The workspace of demo mode ({@code runDemo}) and the screenshot tour. Settings, the study assistant (the
 * simulated one from {@link FakeAi}) and the job queue are the real implementations over the profile's
 * database; courses and progress are a fixed sample (four courses, a week of reviews, recent questions).
 * Calls outside what the demo's screens use throw {@link UnsupportedOperationException}; add canned answers
 * to {@link Courses} or {@link Progress} as screens need them.
 */
public final class DemoWorkspace implements Workspace {

    /** Delay between streamed words of the simulated assistant's answers. */
    public static final Duration WORD_DELAY = Duration.ofMillis(35);

    private final ProfileContext context;
    private final SettingsServiceImpl settings;
    private final AssistantServiceImpl assistant;
    private final JobQueueImpl jobs;
    private final CourseService courses;
    private final ProgressService progress;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DemoWorkspace(ProfileContext context, FakeAi ai) {
        this.context = context;
        this.settings = new SettingsServiceImpl(context.repositories().settings(), context.events());
        this.assistant = new AssistantServiceImpl(ai.runtime(), settings, context.events());
        this.jobs = new JobQueueImpl(context.repositories().jobs(), context.events(), context.clock());
        Courses catalog = new Courses(context.clock(), context.profile().displayName());
        this.courses = Partial.of(CourseService.class, catalog);
        this.progress = Partial.of(ProgressService.class, new Progress(context, catalog));
        assistant.start();
    }

    /** The factory demo mode passes to {@code ProfileServices.create}. */
    public static WorkspaceFactory factory() {
        return context -> new DemoWorkspace(context, FakeAi.create(WORD_DELAY));
    }

    @Override
    public ProfileSummary profile() {
        return context.profile();
    }

    @Override
    public CourseService courses() {
        return courses;
    }

    @Override
    public DocumentService documents() {
        return Partial.of(DocumentService.class, new Object());
    }

    @Override
    public AskService ask() {
        return Partial.of(AskService.class, new Object());
    }

    @Override
    public GenerationService generation() {
        return Partial.of(GenerationService.class, new Object());
    }

    @Override
    public ItemService items() {
        return Partial.of(ItemService.class, new Object());
    }

    @Override
    public PracticeService practice() {
        return Partial.of(PracticeService.class, new Object());
    }

    @Override
    public ProgressService progress() {
        return progress;
    }

    @Override
    public SharingService sharing() {
        return Partial.of(SharingService.class, new Object());
    }

    @Override
    public SettingsService settings() {
        return settings;
    }

    @Override
    public AssistantService assistant() {
        return assistant;
    }

    @Override
    public JobQueue jobs() {
        return jobs;
    }

    @Override
    public EventBus events() {
        return context.events();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            jobs.close();
            assistant.close();
        }
    }

    /** The sample courses. Public methods answer the {@link CourseService} calls of the same signature. */
    public static final class Courses {

        static final String BIOLOGY = "7d0c2f43-5b1e-4f7a-9a51-3c1b8e2d9f01";
        static final String CHEMISTRY = "2a9e6b18-0c4d-4e3f-8b72-61d5f0a4c702";
        static final String STATISTICS = "c45f1d27-9e8a-4b06-a3d4-08e7b2c6f503";
        static final String HISTORY = "e81b3a96-4d2c-47f5-b0e9-5a6c1d7f8204";

        private final List<CourseSummary> all;

        Courses(Clock clock, String profileName) {
            long now = clock.millis();
            long hour = Duration.ofHours(1).toMillis();
            all = List.of(
                    course(BIOLOGY, "Cell Biology", "From membranes to mitosis", "Dr. Lena Park", 150, CourseOrigin.IMPORTED,
                            CourseStatus.PUBLISHED, Visibility.SHAREABLE, 3, false, true, 6, 4, 0, 0, 148, 0, 23, 12, 0.62,
                            now - 2 * hour),
                    course(CHEMISTRY, "Organic Chemistry I", "Structure, bonding and reactions", "Prof. Samuel Adeyemi", 28,
                            CourseOrigin.IMPORTED, CourseStatus.PUBLISHED, Visibility.SHAREABLE, 2, false, true, 8, 5, 0, 0,
                            212, 0, 40, 7, 0.38, now - 26 * hour),
                    course(STATISTICS, "Statistics for Research", "Hypothesis tests that make sense", profileName, 262,
                            CourseOrigin.LOCAL, CourseStatus.PUBLISHED, Visibility.SHAREABLE, 1, true, true, 5, 3, 0, 1, 64,
                            9, 10, 3, 0.81, now - 75 * hour),
                    course(HISTORY, "World History, 1900–1950", "Empires, wars and the world they made", profileName, 205,
                            CourseOrigin.LOCAL, CourseStatus.DRAFT, Visibility.PRIVATE, 0, true, false, 4, 6, 1, 0, 18, 31, 0,
                            0, 0, now - 120 * hour));
        }

        private static CourseSummary course(String id, String title, String subtitle, String author, int hue,
                                            CourseOrigin origin, CourseStatus status, Visibility visibility, int version,
                                            boolean createdHere, boolean enrolled, int modules, int documents,
                                            int processing, int attention, int items, int drafts, int fresh, int due,
                                            double progress, long lastOpened) {
            return new CourseSummary(id, title, subtitle, author, hue, origin, status, visibility, version, createdHere,
                    enrolled, modules, documents, processing, attention, items, drafts, fresh, due, 0, progress, lastOpened,
                    lastOpened);
        }

        public List<CourseSummary> list(CourseFilter filter) {
            String search = filter.search() == null ? "" : filter.search().strip().toLowerCase(Locale.ROOT);
            return all.stream()
                    .filter(c -> filter.includeArchived() || !c.isArchived())
                    .filter(c -> switch (filter.scope()) {
                        case ALL -> true;
                        case STUDYING -> c.enrolled();
                        case CREATING -> c.createdHere();
                    })
                    .filter(c -> search.isEmpty() || (c.title() + " " + c.subtitle() + " " + c.authorName())
                            .toLowerCase(Locale.ROOT).contains(search))
                    .sorted(Comparator.comparing((CourseSummary c) -> c.lastOpenedAt() == null ? 0L : c.lastOpenedAt())
                            .reversed())
                    .toList();
        }

        public CourseSummary summary(String courseId) {
            return all.stream().filter(c -> c.id().equals(courseId)).findFirst()
                    .orElseThrow(() -> NotFoundException.of(UserMessage.COURSE_NOT_FOUND, "course", courseId));
        }

        public void markOpened(String courseId) {
            summary(courseId);
        }
    }

    /** Sample progress. Public methods answer the {@link ProgressService} calls of the same signature. */
    public static final class Progress {

        private static final int[] WEEK = {14, 22, 0, 31, 18, 25, 9};
        private static final double[] RETENTION = {0.78, 0.81, 0.80, 0.84, 0.83, 0.86, 0.85, 0.88, 0.87, 0.89, 0.90, 0.91};

        private final ProfileContext context;
        private final Courses courses;

        Progress(ProfileContext context, Courses courses) {
            this.context = context;
            this.courses = courses;
        }

        public DashboardData dashboard() {
            LocalDate today = LocalDate.now(context.clock());
            List<CourseSummary> studying = courses.list(CourseFilter.studying());
            int due = studying.stream().mapToInt(CourseSummary::dueCount).sum();
            List<DayActivity> week = new ArrayList<>();
            for (int i = 0; i < WEEK.length; i++) {
                LocalDate date = today.minusDays(WEEK.length - 1 - i);
                week.add(new DayActivity(date, WEEK[i], date.equals(today)));
            }
            long now = context.clock().millis();
            List<RecentQuestion> questions = List.of(
                    new RecentQuestion("4f1d2e3c-0000-4000-8000-000000000001", Courses.BIOLOGY, "Cell Biology", 150,
                            "Why does the inner mitochondrial membrane fold into cristae?",
                            "The folds give the membrane far more surface for the electron transport chain [1].",
                            List.of(new Citation(1, null, "3b2a1c0d-0000-4000-8000-000000000011", "Cell Biology, chapter 5",
                                    112, 113, "Cristae greatly increase the surface area of the inner membrane.")),
                            now - Duration.ofHours(3).toMillis()),
                    new RecentQuestion("4f1d2e3c-0000-4000-8000-000000000002", Courses.CHEMISTRY, "Organic Chemistry I", 28,
                            "What makes a nucleophile strong?",
                            "Charge, low electronegativity and little steric hindrance all help [1][2].", List.of(),
                            now - Duration.ofHours(27).toMillis()));
            return new DashboardData(context.profile().displayName(), today,
                    new StudyNext(null, due, 18, 16, null), studying.stream().limit(6).toList(), week, 5, WEEK[WEEK.length - 1],
                    questions);
        }

        public CourseProgress overview() {
            List<CourseSummary> studying = courses.list(CourseFilter.studying());
            return new CourseProgress(null, null, null, 0.58, 0.71, 0.84, 1_240, Duration.ofHours(19).toMillis(), 5,
                    List.of(), concepts(), forecast(), retention(), studying);
        }

        public CourseProgress course(String courseId) {
            CourseSummary course = courses.summary(courseId);
            List<ModuleProgress> modules = new ArrayList<>();
            String[] titles = {"Foundations", "Core ideas", "Working with evidence", "Applications"};
            for (int i = 0; i < titles.length; i++) {
                double completion = Math.max(0, Math.min(1, course.progress() + 0.25 - i * 0.2));
                modules.add(new ModuleProgress("9a0b1c2d-0000-4000-8000-00000000010" + i, titles[i], i, 24, (int) (24 * completion),
                        Math.max(0, 4 - i), completion, completion > 0 ? 0.55 + completion * 0.35 : null));
            }
            return new CourseProgress(course.id(), course.title(), course.coverHue(), course.progress(), 0.68, 0.82, 412,
                    Duration.ofHours(6).toMillis(), 5, modules, concepts(), forecast(), retention(), List.of());
        }

        private static List<ConceptMastery> concepts() {
            String[] names = {"Membrane transport", "Nucleophilic substitution", "Confidence intervals", "Cell cycle checkpoints",
                    "Stereochemistry", "p-values"};
            double[] mastery = {0.42, 0.55, 0.63, 0.74, 0.81, 0.9};
            List<ConceptMastery> list = new ArrayList<>();
            for (int i = 0; i < names.length; i++) {
                list.add(new ConceptMastery("5c6d7e8f-0000-4000-8000-00000000020" + i, names[i], null, mastery[i],
                        mastery[i] + 0.05, 12, 10, 30 + i * 7));
            }
            return List.copyOf(list);
        }

        private List<ForecastDay> forecast() {
            LocalDate today = LocalDate.now(context.clock());
            int[] due = {22, 14, 9, 17, 11, 6, 13, 8, 10, 5, 12, 7, 4, 9};
            List<ForecastDay> days = new ArrayList<>();
            for (int i = 0; i < CourseProgress.FORECAST_DAYS; i++) {
                days.add(new ForecastDay(today.plusDays(i), due[i]));
            }
            return List.copyOf(days);
        }

        private List<RetentionPoint> retention() {
            LocalDate monday = LocalDate.now(context.clock()).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            List<RetentionPoint> points = new ArrayList<>();
            for (int i = 0; i < RETENTION.length; i++) {
                points.add(new RetentionPoint(monday.minusWeeks(RETENTION.length - 1 - i), RETENTION[i], 60 + i * 9));
            }
            return List.copyOf(points);
        }
    }
}
