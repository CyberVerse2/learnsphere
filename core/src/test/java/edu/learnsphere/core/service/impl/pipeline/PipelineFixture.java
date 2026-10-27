package edu.learnsphere.core.service.impl.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.events.Event;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.JobProgress;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.impl.AssistantServiceImpl;
import edu.learnsphere.core.service.impl.JobQueueImpl;
import edu.learnsphere.core.service.impl.SettingsServiceImpl;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.spi.FigureDescriber;
import edu.learnsphere.core.spi.PageReader;
import edu.learnsphere.core.testing.TestDb;
import edu.learnsphere.core.util.Ids;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

/**
 * Everything the pipelines need, with real SQLite in a temp directory, the real job queue, the AI fakes, the fake
 * PDF reader and an in-memory search index. Close it after each test.
 */
final class PipelineFixture implements AutoCloseable {

    static final Duration TIMEOUT = Duration.ofSeconds(30);

    final TestDb t;
    final Repositories repos;
    final EventBus events = new EventBus();
    final List<Event> published = new CopyOnWriteArrayList<>();
    final FakeAi ai;
    final SettingsServiceImpl settings;
    final AssistantServiceImpl assistant;
    final AppPaths.ProfilePaths paths;
    final InMemorySearchIndex index;
    final FakePdf pdf = new FakePdf();
    final FakePdf.PageChunker chunker = new FakePdf.PageChunker();
    JobQueueImpl queue;

    PipelineFixture(Path dir) {
        this(dir, FakeAi.create());
    }

    PipelineFixture(Path dir, FakeAi ai) {
        this.t = TestDb.open(dir);
        this.repos = t.repos;
        this.ai = ai;
        this.settings = new SettingsServiceImpl(repos.settings(), events);
        this.assistant = new AssistantServiceImpl(ai.runtime(), settings, events);
        this.paths = AppPaths.at(dir.resolve("home")).profile(Ids.newId());
        this.index = new InMemorySearchIndex(ai.embedder().dimensions(), ai.embedder().modelName());
        this.queue = newQueue();
        events.subscribeAll(published::add);
    }

    /** A queue without retries: these tests check how the pipeline itself fails, and JobQueueImplTest covers retrying. */
    JobQueueImpl newQueue() {
        return new JobQueueImpl(repos.jobs(), events, t.clock, JobQueueImpl.RetryPolicy.NONE);
    }

    // ---- services ----------------------------------------------------------------------------------

    IngestionService ingestion() {
        return ingestion(ai.embedder(), ai.pageReader(), ai.figureDescriber());
    }

    IngestionService ingestion(Embedder embedder, PageReader reader, FigureDescriber describer) {
        return new IngestionService(repos, events, settings, assistant, paths, index, pdf, pdf, chunker, pdf, embedder,
                reader, describer);
    }

    IngestionService ingestion(IngestionService.Limits limits) {
        return new IngestionService(repos, events, settings, new BackgroundAi(assistant), paths, index, pdf, pdf, chunker,
                pdf, ai.embedder(), ai.pageReader(), ai.figureDescriber(), limits);
    }

    AskServiceImpl ask() {
        return new AskServiceImpl(repos, settings, index, ai.embedder(), ai.reranker(), ai.answerer());
    }

    GenerationServiceImpl generation() {
        return new GenerationServiceImpl(repos, events, queue, assistant, index, ai.embedder(), ai.conceptExtractor(),
                ai.itemGenerator(), ai.supportChecker());
    }

    /** Registers the generation handlers on the current queue. */
    void register(GenerationServiceImpl generation) {
        queue.register(JobKind.EXTRACT_CONCEPTS, generation::runConceptExtraction);
        queue.register(JobKind.GENERATE_ITEMS, generation::runItemGeneration);
        queue.register(JobKind.REGENERATE_ITEM, generation::runRegeneration);
    }

    // ---- material ----------------------------------------------------------------------------------

    /** Stores a fake PDF under {@code files/} and adds its queued document. */
    Document addPdf(Course course, CourseModule module, String title, FakePdf.Builder content) {
        byte[] bytes = content.bytes();
        String sha = FakePdf.sha256(bytes);
        content.write(paths.pdfFile(sha));
        return insertDocument(course, module, title, sha, bytes.length);
    }

    Document insertDocument(Course course, CourseModule module, String title, String sha, long size) {
        Document document = Document.queued(course.id(), module == null ? null : module.id(), title, title + ".pdf", sha,
                size, t.now());
        repos.documents().insert(document);
        return document;
    }

    /** Imports a document through the job queue and waits until the job ends. */
    Job importAndWait(Document document) {
        JobUpdate job = queue.enqueue(IngestionService.jobSpec(document.id()));
        return awaitJob(job.jobId());
    }

    /** A course created here with one module holding a ready PDF of {@code pages}. */
    Material readyMaterial(String... pages) {
        Course course = t.course("Biology");
        CourseModule module = t.module(course, "Cells");
        FakePdf.Builder builder = FakePdf.document();
        for (String page : pages) {
            builder.page(page);
        }
        Document document = addPdf(course, module, "Cell biology", builder);
        queue.register(JobKind.INGEST_DOCUMENT, ingestion());
        Job job = importAndWait(document);
        assertThat(job.status()).isEqualTo(JobStatus.DONE);
        assertThat(repos.documents().get(document.id()).status()).isEqualTo(DocumentStatus.READY);
        return new Material(course, module, repos.documents().get(document.id()));
    }

    record Material(Course course, CourseModule module, Document document) {
    }

    // ---- waiting -----------------------------------------------------------------------------------

    Job awaitJob(String jobId) {
        await(() -> repos.jobs().find(jobId).map(job -> !job.status().equals(JobStatus.QUEUED)
                && !job.status().equals(JobStatus.RUNNING)).orElse(false), "job " + jobId + " to end");
        return repos.jobs().find(jobId).orElseThrow();
    }

    static void await(BooleanSupplier condition, String what) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting for " + what);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for " + what, e);
            }
        }
    }

    <E extends Event> List<E> events(Class<E> type) {
        events.awaitIdle(TIMEOUT);
        return published.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Progress lines a job reported, in order, without repeats in a row. */
    List<String> jobMessages(String jobId) {
        List<String> messages = new java.util.ArrayList<>();
        for (JobProgress progress : events(JobProgress.class)) {
            String message = progress.job().message();
            if (progress.job().jobId().equals(jobId) && message != null
                    && (messages.isEmpty() || !messages.getLast().equals(message))) {
                messages.add(message);
            }
        }
        return messages;
    }

    @Override
    public void close() {
        queue.close();
        events.close();
        t.close();
    }
}
