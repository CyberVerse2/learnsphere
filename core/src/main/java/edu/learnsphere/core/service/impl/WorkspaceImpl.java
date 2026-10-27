package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.ChunkRepository.ChunkWithTitle;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.service.AskService;
import edu.learnsphere.core.service.AssistantService;
import edu.learnsphere.core.service.CourseService;
import edu.learnsphere.core.service.DocumentService;
import edu.learnsphere.core.service.GenerationService;
import edu.learnsphere.core.service.ItemService;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.PracticeService;
import edu.learnsphere.core.service.ProfileContext;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.ProgressService;
import edu.learnsphere.core.service.SettingsService;
import edu.learnsphere.core.service.SharingService;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.service.WorkspaceFactory;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchIndex;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The services of one opened profile, built from the app's {@link Capabilities}.
 *
 * <p>Opening: the search index opens through {@link Capabilities#searchIndexFactory()} with the embedder's
 * dimensions and name (an index that {@link SearchIndex#needsRebuild() needs a rebuild} queues
 * {@link JobKind#REBUILD_INDEX}), unfinished jobs are resumed, and the study assistant starts in the
 * background. Closing stops the jobs, then the assistant, then the index; the profile service closes the
 * database and event bus afterwards.
 *
 * <p>Starting also queues documents whose processing was interrupted (see
 * {@link DocumentServiceImpl#resumeInterrupted()}). The document pipeline handles
 * {@link JobKind#INGEST_DOCUMENT} jobs once its handler is {@link JobQueue#register registered}; until then
 * they wait in the queue.
 *
 * <p>Settings, the study assistant, the job queue and the course, document and item services are
 * implemented; the ask, generation, practice, progress and sharing services throw
 * {@link UnsupportedOperationException}.
 */
public final class WorkspaceImpl implements Workspace {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceImpl.class);

    private final ProfileContext context;
    private final SearchIndex index;
    private final SettingsServiceImpl settings;
    private final AssistantServiceImpl assistant;
    private final JobQueueImpl jobs;
    private final PdfStore pdfs;
    private final CourseServiceImpl courses;
    private final DocumentServiceImpl documents;
    private final ItemServiceImpl items;
    private final AtomicBoolean closed = new AtomicBoolean();

    private WorkspaceImpl(ProfileContext context, Capabilities capabilities) {
        this.context = context;
        Repositories repos = context.repositories();
        this.index = capabilities.searchIndexFactory().open(context.paths().indexDir(),
                capabilities.embedder().dimensions(), capabilities.embedder().modelName());
        this.settings = new SettingsServiceImpl(repos.settings(), context.events());
        this.assistant = new AssistantServiceImpl(capabilities.aiRuntime(), settings, context.events());
        this.jobs = new JobQueueImpl(repos.jobs(), context.events(), context.clock());
        this.pdfs = new PdfStore(context.paths(), repos.documents());
        this.courses = new CourseServiceImpl(repos, context.events(), context.clock(), jobs, index, pdfs);
        this.documents = new DocumentServiceImpl(repos, context.events(), context.clock(), jobs, index, pdfs,
                capabilities.pageRenderer(), capabilities.passageLocator());
        this.items = new ItemServiceImpl(repos, context.events(), context.clock(), jobs);
    }

    /** The factory {@code ProfileServices.create(…, Capabilities)} uses. */
    public static WorkspaceFactory factory(Capabilities capabilities) {
        Objects.requireNonNull(capabilities, "capabilities");
        return context -> open(context, capabilities);
    }

    /** Builds and starts the workspace of an opened profile. */
    public static WorkspaceImpl open(ProfileContext context, Capabilities capabilities) {
        WorkspaceImpl workspace = new WorkspaceImpl(context, capabilities);
        try {
            workspace.start();
            return workspace;
        } catch (RuntimeException e) {
            workspace.close();
            throw e;
        }
    }

    private void start() {
        jobs.resumeUnfinished();
        jobs.register(JobKind.REBUILD_INDEX, job -> {
            String model = index.embeddingModel();
            context.repositories().chunks().withIndexable(model, rows -> {
                index.rebuild(rows.peek(row -> job.token().throwIfCancelled()).map(WorkspaceImpl::toIndexed));
                return null;
            });
        });
        if (index.needsRebuild()) {
            jobs.enqueue(JobSpec.of(JobKind.REBUILD_INDEX, null, null).withPriority(JobSpec.BACKGROUND_PRIORITY));
        }
        documents.resumeInterrupted();
        assistant.start();
    }

    static IndexedChunk toIndexed(ChunkWithTitle row) {
        Chunk chunk = row.chunk();
        return new IndexedChunk(chunk.id(), chunk.documentId(), chunk.courseId(), chunk.moduleId(), row.documentTitle(),
                chunk.pageStart(), chunk.pageEnd(), chunk.sectionTitle(), chunk.text(), chunk.embedding());
    }

    /** The profile's search index. */
    public SearchIndex index() {
        return index;
    }

    /** The profile's stored PDFs, shared by every service that adds or removes documents. */
    public PdfStore pdfs() {
        return pdfs;
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
        return Services.ASK;
    }

    @Override
    public GenerationService generation() {
        return Services.GENERATION;
    }

    @Override
    public ItemService items() {
        return items;
    }

    @Override
    public PracticeService practice() {
        return Services.PRACTICE;
    }

    @Override
    public ProgressService progress() {
        return Services.PROGRESS;
    }

    @Override
    public SharingService sharing() {
        return Services.SHARING;
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeQuietly("jobs", jobs);
        closeQuietly("study assistant", assistant);
        closeQuietly("search index", index);
    }

    private void closeQuietly(String what, AutoCloseable resource) {
        try {
            resource.close();
        } catch (Exception e) {
            LOG.warn("Closing the {} of profile {} failed", what, context.profile().id(), e);
        }
    }

    /** Services without an implementation yet. */
    private static final class Services {
        static final AskService ASK = Unimplemented.service(AskService.class);
        static final GenerationService GENERATION = Unimplemented.service(GenerationService.class);
        static final PracticeService PRACTICE = Unimplemented.service(PracticeService.class);
        static final ProgressService PROGRESS = Unimplemented.service(ProgressService.class);
        static final SharingService SHARING = Unimplemented.service(SharingService.class);
    }
}
