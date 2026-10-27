package edu.learnsphere.app.dev;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.service.AskService;
import edu.learnsphere.core.service.AssistantService;
import edu.learnsphere.core.service.CourseService;
import edu.learnsphere.core.service.DocumentService;
import edu.learnsphere.core.service.GenerationService;
import edu.learnsphere.core.service.ItemService;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.PracticeService;
import edu.learnsphere.core.service.ProfileContext;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.ProgressService;
import edu.learnsphere.core.service.SettingsService;
import edu.learnsphere.core.service.SharingService;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.service.WorkspaceFactory;
import edu.learnsphere.core.service.impl.AssistantServiceImpl;
import edu.learnsphere.core.service.impl.JobQueueImpl;
import edu.learnsphere.core.service.impl.SettingsServiceImpl;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The workspace of demo mode ({@code runDemo}), the screenshot tour and UI tests. Settings, the study
 * assistant (the simulated one from {@link FakeAi}) and the job queue are the real implementations over the
 * profile's database; every other service works on an in-memory {@link DemoStore} filled by
 * {@link DemoContent}: three courses (two being studied, one being written), a month of study history and
 * a conversation with cited answers. Changes made in the demo last until the app closes.
 */
public final class DemoWorkspace implements Workspace {

    /** Delay between streamed words of answers. */
    public static final Duration WORD_DELAY = Duration.ofMillis(35);
    /** One step of a background job (imports, generation), so progress is visible. */
    public static final Duration JOB_STEP = Duration.ofMillis(250);

    private final ProfileContext context;
    private final SettingsServiceImpl settings;
    private final AssistantServiceImpl assistant;
    private final JobQueueImpl jobs;
    private final DemoCourses courses;
    private final DemoDocuments documents;
    private final DemoAsk ask;
    private final DemoGeneration generation;
    private final DemoItems items;
    private final DemoPractice practice;
    private final DemoProgress progress;
    private final DemoSharing sharing;
    private final AtomicBoolean closed = new AtomicBoolean();

    public DemoWorkspace(ProfileContext context, FakeAi ai) {
        this(context, ai, WORD_DELAY, JOB_STEP);
    }

    /** A demo workspace with explicit pacing (tests pass zero delays). */
    public DemoWorkspace(ProfileContext context, FakeAi ai, Duration wordDelay, Duration jobStep) {
        this.context = context;
        this.settings = new SettingsServiceImpl(context.repositories().settings(), context.events());
        this.assistant = new AssistantServiceImpl(ai.runtime(), settings, context.events());
        this.jobs = new JobQueueImpl(context.repositories().jobs(), context.events(), context.clock());
        DemoStore store = new DemoStore(context.clock(), context.events(), context.profile().displayName());
        DemoContent.populate(store);
        this.progress = new DemoProgress(store);
        this.courses = new DemoCourses(store, progress);
        this.documents = new DemoDocuments(store, jobs);
        this.ask = new DemoAsk(store, progress, wordDelay);
        this.generation = new DemoGeneration(store, jobs);
        this.items = new DemoItems(store, progress);
        this.practice = new DemoPractice(store, progress);
        this.sharing = new DemoSharing(store, jobs, progress);
        new DemoJobs(store, documents, generation, sharing, jobStep).register(jobs);
        assistant.start();
    }

    /** The factory demo mode passes to {@code ProfileServices.create}. */
    public static WorkspaceFactory factory() {
        return context -> new DemoWorkspace(context, FakeAi.create(WORD_DELAY));
    }

    /** A factory without delays, for tests. */
    public static WorkspaceFactory instantFactory() {
        return context -> new DemoWorkspace(context, FakeAi.create(Duration.ZERO), Duration.ZERO, Duration.ZERO);
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
        return documents;
    }

    @Override
    public AskService ask() {
        return ask;
    }

    @Override
    public GenerationService generation() {
        return generation;
    }

    @Override
    public ItemService items() {
        return items;
    }

    @Override
    public PracticeService practice() {
        return practice;
    }

    @Override
    public ProgressService progress() {
        return progress;
    }

    @Override
    public SharingService sharing() {
        return sharing;
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
}
