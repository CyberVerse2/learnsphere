package edu.learnsphere.core.service.impl.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.fake.FakeEmbedder;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.PageSource;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.spi.FigureDescriber;
import edu.learnsphere.core.spi.PageReader;
import edu.learnsphere.core.spi.PageRenderer;
import edu.learnsphere.core.spi.PageTranscription;
import edu.learnsphere.core.util.CancellationToken;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IngestionServiceTest {

    @TempDir
    Path dir;

    private PipelineFixture f;
    private Course course;
    private CourseModule module;

    @BeforeEach
    void setUp() {
        f = new PipelineFixture(dir);
        course = f.t.course("Biology");
        module = f.t.module(course, "Cells");
    }

    @AfterEach
    void tearDown() {
        f.close();
    }

    private Job importWith(IngestionService ingestion, Document document) {
        f.queue.register(JobKind.INGEST_DOCUMENT, ingestion);
        return f.importAndWait(document);
    }

    private Document reload(Document document) {
        return f.repos.documents().get(document.id());
    }

    private static String page(int n) {
        return "# Topic " + n + "\nMitochondria page " + n + " explains how cells release energy from glucose.";
    }

    // ---- happy path ------------------------------------------------------------------------------------

    @Test
    void importsAPdfIntoEmbeddedSearchablePassages() {
        Document document = f.addPdf(course, module, "Cell biology",
                FakePdf.document().page(page(1)).page(page(2)).page(page(3)));

        Job job = importWith(f.ingestion(), document);

        assertThat(job.status()).isEqualTo(JobStatus.DONE);
        assertThat(job.progress()).isEqualTo(1.0);
        assertThat(f.jobMessages(job.id())).contains("Checking your PDF…", "Reading page 3 of 3",
                "Making your PDF searchable…", "Your PDF is ready");
        Document ready = reload(document);
        assertThat(ready.status()).isEqualTo(DocumentStatus.READY);
        assertThat(ready.pageCount()).isEqualTo(3);
        assertThat(ready.errorCode()).isNull();

        List<Chunk> chunks = f.repos.chunks().listByDocument(document.id());
        assertThat(chunks).hasSize(3);
        assertThat(chunks).extracting(Chunk::ordinal).containsExactly(0, 1, 2);
        assertThat(chunks).extracting(Chunk::sectionTitle).containsExactly("Topic 1", "Topic 2", "Topic 3");
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.embeddingModel()).isEqualTo(FakeEmbedder.MODEL_NAME);
            assertThat(chunk.embedding()).hasSize(FakeEmbedder.DEFAULT_DIMENSIONS);
            assertThat(chunk.moduleId()).isEqualTo(module.id());
        });
        assertThat(f.index.chunksOf(document.id())).hasSize(3)
                .allSatisfy(indexed -> assertThat(indexed.documentTitle()).isEqualTo("Cell biology"));
        assertThat(f.repos.documents().listPages(document.id()))
                .extracting(DocumentPage::source).containsOnly(PageSource.TEXT_LAYER);
    }

    @Test
    void reportsEveryStageWithAStepCounter() {
        Document document = f.addPdf(course, module, "Cell biology",
                FakePdf.document().page(page(1)).page(page(2)));

        importWith(f.ingestion(), document);

        List<DocumentChanged> changes = f.events(DocumentChanged.class).stream()
                .filter(change -> change.documentId().equals(document.id())).toList();
        assertThat(changes).extracting(DocumentChanged::status).containsSubsequence(DocumentStatus.CHECKING,
                DocumentStatus.EXTRACTING, DocumentStatus.INDEXING, DocumentStatus.READY);
        assertThat(changes).filteredOn(change -> change.status() == DocumentStatus.EXTRACTING)
                .last().satisfies(change -> {
                    assertThat(change.stepDone()).isEqualTo(2);
                    assertThat(change.stepTotal()).isEqualTo(2);
                });
        assertThat(changes).filteredOn(DocumentChanged::hasSteps).filteredOn(c -> c.status() == DocumentStatus.INDEXING)
                .isNotEmpty();
    }

    @Test
    void importsAgainWithoutRepeatingWorkOnceReady() {
        Document document = f.addPdf(course, module, "Cell biology", FakePdf.document().page(page(1)));
        importWith(f.ingestion(), document);
        int extractions = f.pdf.fullExtractions.get();

        Job again = f.importAndWait(document);

        assertThat(again.status()).isEqualTo(JobStatus.DONE);
        assertThat(f.pdf.fullExtractions.get()).isEqualTo(extractions);
        assertThat(f.repos.chunks().listByDocument(document.id())).hasSize(1);
    }

    @Test
    void storesACopyOfTheOriginalWhenTheFileIsNotThereYet() throws Exception {
        FakePdf.Builder content = FakePdf.document().page(page(1));
        Path original = content.write(dir.resolve("incoming").resolve("notes.pdf"));
        String sha = FakePdf.sha256(content.bytes());
        Document document = f.insertDocument(course, module, "Notes", sha, Files.size(original));
        f.queue.register(JobKind.INGEST_DOCUMENT, f.ingestion());

        Job job = f.awaitJob(f.queue.enqueue(IngestionService.jobSpec(document.id(), original)).jobId());

        assertThat(job.status()).isEqualTo(JobStatus.DONE);
        assertThat(reload(document).status()).isEqualTo(DocumentStatus.READY);
        assertThat(Files.readAllBytes(f.paths.pdfFile(sha))).isEqualTo(content.bytes());
        try (var leftovers = Files.list(f.paths.filesDir())) {
            assertThat(leftovers.map(p -> p.getFileName().toString())).containsExactly(sha + ".pdf");
        }
    }

    @Test
    void endsQuietlyWhenTheDocumentWasRemoved() {
        Document document = f.addPdf(course, module, "Cell biology", FakePdf.document().page(page(1)));
        f.repos.documents().delete(document.id());

        Job job = importWith(f.ingestion(), document);

        assertThat(job.status()).isEqualTo(JobStatus.DONE);
        assertThat(f.pdf.inspections.get()).isZero();
    }

    // ---- scanned pages and review ----------------------------------------------------------------------

    @Test
    void scannedPagesWaitForReviewAndAreNeverIndexedUntilApproved() {
        CountingReader reader = new CountingReader(f.ai.pageReader());
        Document document = f.addPdf(course, module, "Lecture scans",
                FakePdf.document().page(page(1)).scannedPage().page("p. 3", 0.6));
        IngestionService ingestion = f.ingestion(f.ai.embedder(), reader, f.ai.figureDescriber());

        Job first = importWith(ingestion, document);

        assertThat(first.status()).isEqualTo(JobStatus.DONE);
        assertThat(f.jobMessages(first.id())).contains("Reading scanned page 1 of 2", "Some scanned pages need your check");
        assertThat(reload(document).status()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        assertThat(reader.pages).containsExactly(2, 3);
        assertThat(f.repos.documents().listPagesNeedingReview(document.id()))
                .extracting(DocumentPage::pageNumber).containsExactly(2, 3);
        assertThat(f.repos.documents().findPage(document.id(), 2).orElseThrow().text())
                .isEqualTo("## Page 2\n\nText read from page 2.");
        assertThat(f.repos.chunks().listByDocument(document.id())).isEmpty();
        assertThat(f.index.chunksOf(document.id())).isEmpty();
        assertThat(f.pdf.renders.get()).isEqualTo(2);

        // Approving only some pages keeps the document waiting.
        f.repos.documents().markPagesReviewed(document.id(), List.of(2));
        assertThat(f.importAndWait(document).status()).isEqualTo(JobStatus.DONE);
        assertThat(reload(document).status()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        assertThat(f.repos.chunks().listByDocument(document.id())).isEmpty();

        f.repos.documents().markPagesReviewed(document.id(), List.of(3));
        assertThat(f.importAndWait(document).status()).isEqualTo(JobStatus.DONE);

        assertThat(reload(document).status()).isEqualTo(DocumentStatus.READY);
        assertThat(reader.pages).containsExactly(2, 3);
        List<Chunk> chunks = f.repos.chunks().listByDocument(document.id());
        assertThat(chunks).extracting(Chunk::pageStart).containsExactly(1, 2, 3);
        assertThat(chunks.get(1).sectionTitle()).isEqualTo("Page 2");
        assertThat(chunks.get(1).text()).contains("Text read from page 2.");
        assertThat(f.index.chunksOf(document.id())).hasSize(3);
    }

    @Test
    void anEditedReviewedPageIsUsedAsTheCreatorWroteIt() {
        Document document = f.addPdf(course, module, "Lecture scans", FakePdf.document().scannedPage());
        importWith(f.ingestion(), document);
        f.repos.documents().updatePageText(document.id(), 1, "Ribosomes build proteins from amino acids.", true);

        f.importAndWait(document);

        assertThat(reload(document).status()).isEqualTo(DocumentStatus.READY);
        assertThat(f.repos.chunks().listByDocument(document.id())).singleElement()
                .satisfies(chunk -> assertThat(chunk.text()).isEqualTo("Ribosomes build proteins from amino acids."));
    }

    // ---- figures -------------------------------------------------------------------------------------

    @Test
    void describesFiguresIntoThePassagesWhenTheSettingIsOn() {
        Document document = f.addPdf(course, module, "Diagrams",
                FakePdf.document().page(page(1), 0.5).page(page(2), 0.05).page(page(3)));

        importWith(f.ingestion(), document);

        List<Chunk> chunks = f.repos.chunks().listByDocument(document.id());
        assertThat(chunks.get(0).text()).endsWith("Figure: A labelled diagram on page 1.");
        assertThat(chunks.get(1).text()).doesNotContain("Figure:");
        assertThat(chunks.get(2).text()).doesNotContain("Figure:");
        assertThat(f.repos.documents().findPage(document.id(), 1).orElseThrow().figureNotes())
                .isEqualTo("A labelled diagram on page 1.");
    }

    @Test
    void leavesFiguresAloneWhenTheSettingIsOff() {
        f.settings.set(SettingKeys.DESCRIBE_FIGURES, false);
        AtomicInteger described = new AtomicInteger();
        FigureDescriber describer = (png, pageNumber, token) -> {
            described.incrementAndGet();
            return List.of("A diagram");
        };
        Document document = f.addPdf(course, module, "Diagrams", FakePdf.document().page(page(1), 0.5));

        importWith(f.ingestion(f.ai.embedder(), f.ai.pageReader(), describer), document);

        assertThat(described).hasValue(0);
        assertThat(f.repos.chunks().listByDocument(document.id())).singleElement()
                .satisfies(chunk -> assertThat(chunk.text()).doesNotContain("Figure:"));
        assertThat(reload(document).status()).isEqualTo(DocumentStatus.READY);
    }

    @Test
    void showsTheAssistantScannedPagesAndFiguresAtTheReadingResolution() {
        List<String> images = new java.util.concurrent.CopyOnWriteArrayList<>();
        PageReader reader = (png, pageNumber, token) -> {
            images.add(new String(png, StandardCharsets.UTF_8));
            return new PageTranscription("Text read from page " + pageNumber + ".", List.of());
        };
        FigureDescriber describer = (png, pageNumber, token) -> {
            images.add(new String(png, StandardCharsets.UTF_8));
            return List.of("A diagram");
        };
        IngestionService ingestion = f.ingestion(f.ai.embedder(), reader, describer);

        importWith(ingestion, f.addPdf(course, module, "Scans", FakePdf.document().scannedPage()));
        importWith(ingestion, f.addPdf(course, module, "Diagrams", FakePdf.document().page(page(1), 0.5)));

        float scale = IngestionService.READ_DPI / PageRenderer.POINTS_PER_INCH;
        assertThat(images).containsExactly("PNG page 1 at " + scale, "PNG page 1 at " + scale);
    }

    // ---- failures ------------------------------------------------------------------------------------

    private void assertFailed(Document document, Job job, DocumentError error) {
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.message()).isEqualTo(error.userMessage().text());
        Document failed = reload(document);
        assertThat(failed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.errorCode()).isEqualTo(error);
        assertThat(f.events(DocumentChanged.class)).filteredOn(c -> c.status() == DocumentStatus.FAILED)
                .singleElement().satisfies(change -> assertThat(change.error()).isEqualTo(error.userMessage()));
        assertThat(f.repos.chunks().listByDocument(document.id())).isEmpty();
    }

    @Test
    void refusesAPasswordProtectedPdf() {
        Document document = f.addPdf(course, module, "Locked", FakePdf.document().page(page(1)).encrypted());

        assertFailed(document, importWith(f.ingestion(), document), DocumentError.ENCRYPTED);
        assertThat(reload(document).errorCode().userMessage().text())
                .isEqualTo("This PDF is password-protected. Remove the password and import it again.");
    }

    @Test
    void refusesADamagedPdf() throws Exception {
        byte[] garbage = "not a pdf at all".getBytes(StandardCharsets.UTF_8);
        String sha = FakePdf.sha256(garbage);
        Files.write(f.paths.pdfFile(sha), garbage);
        Document document = f.insertDocument(course, module, "Broken", sha, garbage.length);

        assertFailed(document, importWith(f.ingestion(), document), DocumentError.DAMAGED);
    }

    @Test
    void refusesAPdfWithTooManyPages() {
        Document document = f.addPdf(course, module, "Huge", FakePdf.document().page(page(1)).declaredPages(2_001));

        assertFailed(document, importWith(f.ingestion(), document), DocumentError.TOO_MANY_PAGES);
    }

    @Test
    void refusesAPdfThatIsTooLarge() {
        Document document = f.addPdf(course, module, "Big", FakePdf.document().page(page(1)));

        assertFailed(document, importWith(f.ingestion(new IngestionService.Limits(16, 2_000)), document),
                DocumentError.TOO_LARGE);
    }

    @Test
    void refusesAPdfWithoutReadableText() {
        Document document = f.addPdf(course, module, "Blank", FakePdf.document().page("").page(""));

        assertFailed(document, importWith(f.ingestion(), document), DocumentError.NO_TEXT);
    }

    @Test
    void reportsAMissingFile() {
        Document document = f.insertDocument(course, module, "Gone", "a".repeat(64), 10);

        assertFailed(document, importWith(f.ingestion(), document), DocumentError.MISSING_FILE);
    }

    @Test
    void refusesAnOriginalThatChangedSinceItWasImported() {
        Path original = FakePdf.document().page(page(1)).write(dir.resolve("notes.pdf"));
        Document document = f.insertDocument(course, module, "Notes", "b".repeat(64), 10);
        f.queue.register(JobKind.INGEST_DOCUMENT, f.ingestion());

        Job job = f.awaitJob(f.queue.enqueue(IngestionService.jobSpec(document.id(), original)).jobId());

        assertFailed(document, job, DocumentError.MISSING_FILE);
    }

    @Test
    void reportsAPageThatCouldNotBeRead() {
        PageReader failing = (png, pageNumber, token) -> {
            throw new UnavailableException(UserMessage.ASSISTANT_UNAVAILABLE, "runtime crashed");
        };
        f.ai.runtime().setStatus(new AiStatus(AiStatus.State.FAILED, null, "crashed"));
        Document document = f.addPdf(course, module, "Scans", FakePdf.document().scannedPage());

        assertFailed(document, importWith(f.ingestion(f.ai.embedder(), failing, f.ai.figureDescriber()), document),
                DocumentError.READ_FAILED);
    }

    // ---- assistant starting, interruption and cancellation ---------------------------------------------

    @Test
    void waitsForAStartingAssistant() {
        f.ai.runtime().setStatus(new AiStatus(AiStatus.State.STARTING, null, null));
        AtomicInteger refused = new AtomicInteger();
        Embedder starting = new DelegatingEmbedder(f.ai.embedder()) {
            @Override
            public List<float[]> embed(List<String> texts, EmbedKind kind, CancellationToken token) {
                if (f.ai.runtime().status().state() != AiStatus.State.READY) {
                    refused.incrementAndGet();
                    throw new UnavailableException(UserMessage.ASSISTANT_STARTING, "starting");
                }
                return super.embed(texts, kind, token);
            }
        };
        Document document = f.addPdf(course, module, "Cell biology", FakePdf.document().page(page(1)));
        f.queue.register(JobKind.INGEST_DOCUMENT, f.ingestion(starting, f.ai.pageReader(), f.ai.figureDescriber()));
        JobUpdate job = f.queue.enqueue(IngestionService.jobSpec(document.id()));
        PipelineFixture.await(() -> refused.get() > 0, "the first refused request");

        f.ai.runtime().start(null);

        assertThat(f.awaitJob(job.jobId()).status()).isEqualTo(JobStatus.DONE);
        assertThat(reload(document).status()).isEqualTo(DocumentStatus.READY);
    }

    @Test
    void continuesEmbeddingWhereAnInterruptedImportStopped() {
        FakePdf.Builder content = FakePdf.document();
        for (int n = 1; n <= 20; n++) {
            content.page(page(n));
        }
        Document document = f.addPdf(course, module, "Long notes", content);
        Gate gate = new Gate(2);
        f.queue.register(JobKind.INGEST_DOCUMENT, f.ingestion(new GatedEmbedder(f.ai.embedder(), gate),
                f.ai.pageReader(), f.ai.figureDescriber()));
        JobUpdate job = f.queue.enqueue(IngestionService.jobSpec(document.id()));
        gate.awaitReached();

        f.queue.close();

        assertThat(f.repos.jobs().find(job.jobId()).orElseThrow().status()).isEqualTo(JobStatus.QUEUED);
        assertThat(f.repos.chunks().listByDocument(document.id())).filteredOn(Chunk::hasEmbedding)
                .hasSize(IngestionService.EMBED_BATCH);
        assertThat(reload(document).status()).isEqualTo(DocumentStatus.INDEXING);

        CountingEmbedder counting = new CountingEmbedder(f.ai.embedder());
        f.queue = f.newQueue();
        f.queue.register(JobKind.INGEST_DOCUMENT, f.ingestion(counting, f.ai.pageReader(), f.ai.figureDescriber()));
        f.queue.resumeUnfinished();

        assertThat(f.awaitJob(job.jobId()).status()).isEqualTo(JobStatus.DONE);
        assertThat(counting.texts).hasValue(20 - IngestionService.EMBED_BATCH);
        assertThat(f.pdf.fullExtractions).hasValue(1);
        assertThat(reload(document).status()).isEqualTo(DocumentStatus.READY);
        assertThat(f.index.chunksOf(document.id())).hasSize(20);
    }

    @Test
    void continuesReadingScannedPagesWhereAnInterruptedImportStopped() {
        Document document = f.addPdf(course, module, "Scans",
                FakePdf.document().page(page(1)).scannedPage().scannedPage().scannedPage().scannedPage());
        Gate gate = new Gate(3);
        PageReader gated = (png, pageNumber, token) -> {
            gate.pass(token);
            return f.ai.pageReader().read(png, pageNumber, token);
        };
        f.queue.register(JobKind.INGEST_DOCUMENT, f.ingestion(f.ai.embedder(), gated, f.ai.figureDescriber()));
        JobUpdate job = f.queue.enqueue(IngestionService.jobSpec(document.id()));
        gate.awaitReached();

        f.queue.close();

        assertThat(f.repos.documents().listPagesNeedingReview(document.id())).extracting(DocumentPage::pageNumber)
                .containsExactly(2, 3);
        CountingReader reader = new CountingReader(f.ai.pageReader());
        f.queue = f.newQueue();
        f.queue.register(JobKind.INGEST_DOCUMENT, f.ingestion(f.ai.embedder(), reader, f.ai.figureDescriber()));
        f.queue.resumeUnfinished();

        assertThat(f.awaitJob(job.jobId()).status()).isEqualTo(JobStatus.DONE);
        assertThat(reader.pages).containsExactly(4, 5);
        assertThat(reload(document).status()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        assertThat(f.repos.documents().listPagesNeedingReview(document.id())).hasSize(4);
    }

    @Test
    void aCancelledImportKeepsItsWorkAndCanBeStartedAgain() {
        FakePdf.Builder content = FakePdf.document();
        for (int n = 1; n <= 20; n++) {
            content.page(page(n));
        }
        Document document = f.addPdf(course, module, "Long notes", content);
        Gate gate = new Gate(2);
        GatedEmbedder gated = new GatedEmbedder(f.ai.embedder(), gate);
        f.queue.register(JobKind.INGEST_DOCUMENT, f.ingestion(gated, f.ai.pageReader(), f.ai.figureDescriber()));
        JobUpdate job = f.queue.enqueue(IngestionService.jobSpec(document.id()));
        gate.awaitReached();

        f.queue.cancel(job.jobId());

        assertThat(f.awaitJob(job.jobId()).status()).isEqualTo(JobStatus.CANCELLED);
        assertThat(reload(document).status()).isNotEqualTo(DocumentStatus.FAILED);
        assertThat(f.repos.chunks().listByDocument(document.id())).filteredOn(Chunk::hasEmbedding).hasSize(16);

        assertThat(f.importAndWait(document).status()).isEqualTo(JobStatus.DONE);
        assertThat(reload(document).status()).isEqualTo(DocumentStatus.READY);
        assertThat(gated.texts).hasValue(20);
    }

    // ---- SPI wrappers ----------------------------------------------------------------------------------

    private static class DelegatingEmbedder implements Embedder {

        private final Embedder delegate;

        DelegatingEmbedder(Embedder delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<float[]> embed(List<String> texts, EmbedKind kind, CancellationToken token) {
            return delegate.embed(texts, kind, token);
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }

        @Override
        public String modelName() {
            return delegate.modelName();
        }
    }

    /** Counts the texts it embeds. */
    static final class CountingEmbedder extends DelegatingEmbedder {

        final AtomicInteger texts = new AtomicInteger();

        CountingEmbedder(Embedder delegate) {
            super(delegate);
        }

        @Override
        public List<float[]> embed(List<String> batch, EmbedKind kind, CancellationToken token) {
            texts.addAndGet(batch.size());
            return super.embed(batch, kind, token);
        }
    }

    /** Embeds through a {@link Gate}, counting the texts it embedded. */
    static final class GatedEmbedder extends DelegatingEmbedder {

        final AtomicInteger texts = new AtomicInteger();
        private final Gate gate;

        GatedEmbedder(Embedder delegate, Gate gate) {
            super(delegate);
            this.gate = gate;
        }

        @Override
        public List<float[]> embed(List<String> batch, EmbedKind kind, CancellationToken token) {
            gate.pass(token);
            texts.addAndGet(batch.size());
            return super.embed(batch, kind, token);
        }
    }

    /** Records the pages it reads. */
    static final class CountingReader implements PageReader {

        final List<Integer> pages = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final PageReader delegate;

        CountingReader(PageReader delegate) {
            this.delegate = delegate;
        }

        @Override
        public PageTranscription read(byte[] pngImage, int pageNumber, CancellationToken token) {
            pages.add(pageNumber);
            return delegate.read(pngImage, pageNumber, token);
        }
    }
}
