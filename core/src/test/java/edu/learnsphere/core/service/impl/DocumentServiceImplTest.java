package edu.learnsphere.core.service.impl;

import static edu.learnsphere.core.service.impl.CourseServiceImplTest.assertMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.AuditLogEntry;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.PageSource;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.RenderedPage;
import edu.learnsphere.core.service.impl.DocumentServiceImpl.IngestPayload;
import edu.learnsphere.core.service.impl.DocumentServiceImpl.IngestPayload.Reason;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.NormalizedRect;
import edu.learnsphere.core.spi.PageRenderer;
import edu.learnsphere.core.spi.PassageLocator;
import edu.learnsphere.core.util.Json;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentServiceImplTest {

    private static final NormalizedRect RECT = new NormalizedRect(0.1, 0.2, 0.5, 0.1);

    @TempDir
    Path dir;

    private ServiceHarness h;
    private DocumentServiceImpl documents;
    private final AtomicInteger renders = new AtomicInteger();
    private PageRenderer renderer;
    private PassageLocator locator;
    private Course course;
    private CourseModule cells;

    @BeforeEach
    void setUp() throws IOException {
        h = new ServiceHarness(dir);
        byte[] png = png(20, 30);
        renderer = (pdf, page, scale) -> {
            assertThat(pdf).exists();
            renders.incrementAndGet();
            return png;
        };
        locator = (pdf, page, passage) -> passage.contains("cell") ? List.of(RECT) : List.of();
        documents = documents(Long.MAX_VALUE);
        course = h.t.enrolledCourse("Biology");
        cells = h.t.module(course, "Cells");
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void importsAPdfIntoTheCourseAndQueuesItsPreparation() throws IOException {
        Path source = pdf("Cell Biology.pdf", "cells");

        DocumentSummary summary = documents.importPdf(course.id(), cells.id(), source);

        assertThat(summary.title()).isEqualTo("Cell Biology");
        assertThat(summary.fileName()).isEqualTo("Cell Biology.pdf");
        assertThat(summary.moduleTitle()).isEqualTo("Cells");
        assertThat(summary.status()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(summary.byteSize()).isEqualTo(Files.size(source));
        Document stored = h.t.repos.documents().get(summary.id());
        assertThat(h.pdfs.file(stored.fileSha256())).hasSameBinaryContentAs(source);
        assertThat(stored.fileSha256()).isEqualTo(PdfStore.sha256(source));
        try (Stream<Path> files = Files.list(h.paths.filesDir())) {
            assertThat(files).containsExactly(h.pdfs.file(stored.fileSha256()));
        }
        Job job = h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED).getFirst();
        assertThat(job.subjectId()).isEqualTo(summary.id());
        assertThat(job.priority()).isEqualTo(JobSpec.USER_PRIORITY);
        assertThat(Json.read(job.payloadJson(), DocumentServiceImpl.IngestPayload.class))
                .isEqualTo(new DocumentServiceImpl.IngestPayload(summary.id(), Reason.IMPORT));
        assertThat(h.t.repos.audit().listForEntity(EntityType.DOCUMENT, summary.id()))
                .extracting(AuditLogEntry::action, AuditLogEntry::detail)
                .containsExactly(tuple("import", "Cell Biology.pdf"));
        assertThat(h.events(DocumentChanged.class)).extracting(DocumentChanged::documentId, DocumentChanged::type)
                .containsExactly(tuple(summary.id(), ChangeType.CREATED));
        assertThat(documents.list(course.id())).containsExactly(summary);
        assertThat(documents.get(summary.id())).isEqualTo(summary);
    }

    @Test
    void refusesFilesThatAreNotPdfsAndPdfsAlreadyInTheCourse() throws IOException {
        Path text = dir.resolve("notes.pdf");
        Files.writeString(text, "Just some notes");
        assertMessage(() -> documents.importPdf(course.id(), null, text), DocumentServiceImpl.NOT_A_PDF);
        assertMessage(() -> documents.importPdf(course.id(), null, dir.resolve("missing.pdf")),
                DocumentServiceImpl.FILE_UNREADABLE);
        assertMessage(() -> documents.importPdf(course.id(), null, dir), DocumentServiceImpl.FILE_UNREADABLE);

        Path source = pdf("Cells.pdf", "cells");
        Path renamed = Files.copy(source, dir.resolve("Cells again.pdf"));
        documents.importPdf(course.id(), null, source);
        assertMessage(() -> documents.importPdf(course.id(), null, renamed), DocumentServiceImpl.ALREADY_IN_COURSE);

        Course other = h.t.course("Chemistry");
        DocumentSummary copy = documents.importPdf(other.id(), null, renamed);
        assertThat(copy.title()).isEqualTo("Cells again");
        try (Stream<Path> files = Files.list(h.paths.filesDir())) {
            assertThat(files).hasSize(1);
        }
        assertMessage(() -> documents.importPdf(h.importedCourse("Geology").id(), null, source), ServiceSupport.SHARED_COURSE);
        assertMessage(() -> documents.importPdf(course.id(), h.t.module(other, "Atoms").id(), source),
                UserMessage.MODULE_NOT_FOUND);
    }

    @Test
    void recordsAPdfOverTheSizeLimitAsFailedWithoutCopyingIt() throws IOException {
        documents = documents(16);
        Path source = pdf("Huge.pdf", "x".repeat(64));

        DocumentSummary summary = documents.importPdf(course.id(), null, source);

        assertThat(summary.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(summary.error()).isEqualTo(DocumentError.TOO_LARGE.userMessage());
        assertThat(h.pdfs.contains(h.t.repos.documents().get(summary.id()).fileSha256())).isFalse();
        assertThat(h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED)).isEmpty();
        documents.retry(summary.id());
        assertThat(documents.get(summary.id()).error()).isEqualTo(DocumentError.TOO_LARGE.userMessage());
    }

    @Test
    void retriesAFailedImportWhileItsFileIsStored() throws IOException {
        DocumentSummary imported = documents.importPdf(course.id(), null, pdf("Cells.pdf", "cells"));
        h.jobs.cancel(h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED).getFirst().id());
        h.t.repos.documents().markFailed(imported.id(), DocumentError.READ_FAILED, null);
        Document lost = h.t.document(course, null, "Lost");
        h.t.repos.documents().markFailed(lost.id(), DocumentError.UNEXPECTED, null);

        documents.retry(imported.id());
        documents.retry(lost.id());

        assertThat(documents.get(imported.id()).status()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED)).extracting(Job::subjectId)
                .containsExactly(imported.id());
        assertThat(documents.get(lost.id()).error()).isEqualTo(DocumentError.MISSING_FILE.userMessage());
    }

    @Test
    void removingADocumentKeepsAFileAnotherCourseStillUses() throws IOException {
        Path source = pdf("Cells.pdf", "cells");
        DocumentSummary first = documents.importPdf(course.id(), cells.id(), source);
        DocumentSummary second = documents.importPdf(h.t.course("Chemistry").id(), null, source);
        Path stored = h.pdfs.file(h.t.repos.documents().get(first.id()).fileSha256());
        documents.renderPage(first.id(), 1, 1);
        Chunk chunk = h.t.chunk(h.t.repos.documents().get(first.id()), 0, "The cell.");
        h.index.upsert(List.of(new IndexedChunk(chunk.id(), first.id(), course.id(), cells.id(), "Cells", 1, 1, null,
                "The cell.", new float[] {1f, 0f})));
        h.index.commit();
        LearningItem item = LearningItem.draft(course.id(), cells.id(), ItemType.FLASHCARD, "What?", "A cell", "", List.of(),
                Difficulty.MEDIUM, chunk.id(), 1, ItemOrigin.AI, h.t.now());
        h.t.repos.items().insert(item);
        h.clearEvents();

        documents.remove(first.id());

        assertThat(h.t.repos.documents().find(first.id())).isEmpty();
        assertThat(stored).exists();
        assertThat(h.paths.rendersDir().resolve(first.id())).doesNotExist();
        assertThat(h.index.chunks()).isEmpty();
        assertThat(h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.CANCELLED)).extracting(Job::subjectId)
                .containsExactly(first.id());
        assertThat(h.t.repos.audit().listForEntity(EntityType.DOCUMENT, first.id())).extracting(AuditLogEntry::action)
                .containsExactlyInAnyOrder("import", "remove");
        assertThat(h.events(DocumentChanged.class)).extracting(DocumentChanged::type).containsExactly(ChangeType.DELETED);
        assertThat(h.events(ItemsChanged.class)).extracting(ItemsChanged::courseId).containsExactly(course.id());

        documents.remove(second.id());
        assertThat(stored).doesNotExist();
    }

    @Test
    void renamingOrMovingAReadyDocumentUpdatesItsSearchPassages() {
        Document document = h.t.document(course, cells, "Cells");
        Chunk chunk = h.t.chunk(document, 0, "The cell.");
        h.t.repos.chunks().updateEmbeddings(Map.of(chunk.id(), new float[] {1f, 0f}), "embed-v1");
        h.t.repos.documents().markReady(document.id());
        CourseModule tissues = h.t.module(course, "Tissues");

        documents.rename(document.id(), "  Cell notes ");
        assertThat(h.index.chunks().get(chunk.id()).documentTitle()).isEqualTo("Cell notes");
        documents.moveToModule(document.id(), tissues.id());
        assertThat(h.index.chunks().get(chunk.id()).moduleId()).isEqualTo(tissues.id());
        assertThat(documents.get(document.id()).moduleTitle()).isEqualTo("Tissues");

        Document queued = h.t.document(course, null, "Queued");
        documents.rename(queued.id(), "Still queued");
        assertThat(h.index.chunks()).containsOnlyKeys(chunk.id());

        assertMessage(() -> documents.rename(document.id(), " "), DocumentServiceImpl.TITLE_REQUIRED);
        assertMessage(() -> documents.rename(document.id(), "x".repeat(201)), DocumentServiceImpl.TITLE_TOO_LONG);
        assertThat(h.events(DocumentChanged.class)).extracting(DocumentChanged::type).containsOnly(ChangeType.UPDATED);
    }

    @Test
    void reviewingTheLastScannedPageContinuesTheImport() {
        Document document = h.t.document(course, null, "Scans");
        h.t.repos.documents().updateStatus(document.id(), DocumentStatus.NEEDS_REVIEW, 0.4, null);
        page(document, 1, PageSource.AI_READ, "Teh cell");
        page(document, 2, PageSource.AI_READ, "The membrane");
        page(document, 3, PageSource.TEXT_LAYER, "The nucleus");
        assertThat(documents.pagesNeedingReview(document.id())).extracting(DocumentPage::pageNumber).containsExactly(1, 2);

        assertMessage(() -> documents.editPageText(document.id(), 3, "x"), DocumentServiceImpl.ONLY_SCANNED_PAGES);
        assertMessage(() -> documents.editPageText(document.id(), 9, "x"), DocumentServiceImpl.PAGE_NOT_FOUND);
        assertMessage(() -> documents.approvePages(document.id(), List.of(2, 9)), DocumentServiceImpl.PAGE_NOT_FOUND);

        documents.editPageText(document.id(), 1, " The cell ");
        assertThat(documents.page(document.id(), 1).text()).isEqualTo("The cell");
        assertThat(documents.get(document.id()).status()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        assertThat(documents.get(document.id()).pagesNeedingReview()).isEqualTo(1);
        assertThat(h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED)).isEmpty();

        documents.approvePages(document.id(), List.of(2));
        assertThat(documents.get(document.id()).status()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED)).singleElement().satisfies(job -> {
            assertThat(job.subjectId()).isEqualTo(document.id());
            assertThat(payload(job)).isEqualTo(new IngestPayload(document.id(), Reason.CONTINUE_AFTER_REVIEW));
        });
    }

    @Test
    void correctingAPageOfAReadyDocumentKeepsItsPassagesAndItemSourcesUntilTheImportReplacesThem() {
        Document document = h.t.document(course, cells, "Scans");
        page(document, 1, PageSource.AI_READ, "Teh cell");
        page(document, 2, PageSource.TEXT_LAYER, "The membrane");
        h.t.repos.documents().markPagesReviewed(document.id(), List.of(1));
        Chunk first = h.t.chunk(document, 0, "Teh cell");
        Chunk second = h.t.chunk(document, 1, "The membrane");
        h.t.repos.chunks().updateEmbeddings(Map.of(first.id(), new float[] {1f, 0f}, second.id(), new float[] {0f, 1f}),
                "embed-v1");
        h.t.repos.documents().markReady(document.id());
        h.index.upsert(h.t.repos.chunks().listByDocument(document.id()).stream()
                .map(c -> new IndexedChunk(c.id(), document.id(), course.id(), cells.id(), "Scans", c.pageStart(),
                        c.pageEnd(), null, c.text(), c.embedding()))
                .toList());
        h.index.commit();
        LearningItem fromFirst = sourcedItem(first, 1);
        LearningItem fromSecond = sourcedItem(second, 2);
        h.t.clock.advance(Duration.ofMillis(1));

        documents.approvePages(document.id(), List.of(1));
        documents.editPageText(document.id(), 1, " Teh cell ");
        assertThat(documents.get(document.id()).status()).isEqualTo(DocumentStatus.READY);
        assertThat(h.t.repos.chunks().isOutdated(document.id())).isFalse();
        assertThat(h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED)).isEmpty();
        h.clearEvents();

        documents.editPageText(document.id(), 1, "The cell");

        assertThat(documents.get(document.id()).status()).isEqualTo(DocumentStatus.QUEUED);
        assertThat(h.t.repos.chunks().isOutdated(document.id())).isTrue();
        assertThat(h.t.repos.chunks().listByDocument(document.id())).extracting(Chunk::id, Chunk::text)
                .containsExactly(tuple(first.id(), "Teh cell"), tuple(second.id(), "The membrane"));
        assertThat(h.index.chunks()).containsOnlyKeys(first.id(), second.id());
        assertThat(sourceOf(fromFirst)).isEqualTo(first.id());
        assertThat(sourceOf(fromSecond)).isEqualTo(second.id());
        Job job = h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED).getFirst();
        assertThat(payload(job)).isEqualTo(new IngestPayload(document.id(), Reason.PAGES_CHANGED));
        assertThat(h.events(ItemsChanged.class)).isEmpty();
        assertThat(h.events(DocumentChanged.class)).extracting(DocumentChanged::status)
                .containsExactly(DocumentStatus.QUEUED);

        // An import handler that keeps the INGEST_DOCUMENT contract makes the passages again from the pages.
        h.t.clock.advance(Duration.ofMillis(1));
        h.jobs.register(JobKind.INGEST_DOCUMENT, context -> {
            String id = context.payload(IngestPayload.class).documentId();
            if (h.t.repos.chunks().isOutdated(id)) {
                Document doc = h.t.repos.documents().get(id);
                h.t.repos.chunks().replaceForDocument(id, h.t.repos.documents().listUsablePages(id).stream()
                        .map(p -> Chunk.create(id, doc.courseId(), doc.moduleId(), p.pageNumber() - 1, p.pageNumber(),
                                p.pageNumber(), null, p.text(), 2, h.t.now()))
                        .toList());
            }
            h.t.repos.documents().markReady(id);
        });
        JobQueueImplTest.await(() -> h.t.repos.jobs().find(job.id()).orElseThrow().status() == JobStatus.DONE);

        List<Chunk> remade = h.t.repos.chunks().listByDocument(document.id());
        assertThat(remade).extracting(Chunk::text).containsExactly("The cell", "The membrane");
        assertThat(remade).extracting(Chunk::id).doesNotContain(first.id(), second.id());
        assertThat(sourceOf(fromFirst)).isEqualTo(remade.get(0).id());
        assertThat(sourceOf(fromSecond)).isEqualTo(remade.get(1).id());
        assertThat(h.t.repos.chunks().isOutdated(document.id())).isFalse();
        assertThat(documents.get(document.id()).status()).isEqualTo(DocumentStatus.READY);
    }

    @Test
    void retryingADocumentStillImportingHurriesItsJob() {
        Document document = h.t.document(course, null, "Waiting");
        assertThat(documents.resumeInterrupted()).isEqualTo(1);
        Job job = h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED).getFirst();
        assertThat(job.priority()).isEqualTo(JobSpec.BACKGROUND_PRIORITY);
        assertThat(payload(job)).isEqualTo(new IngestPayload(document.id(), Reason.RESUME));

        documents.retry(document.id());

        assertThat(h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED)).singleElement().satisfies(raised -> {
            assertThat(raised.id()).isEqualTo(job.id());
            assertThat(raised.priority()).isEqualTo(JobSpec.USER_PRIORITY);
        });
    }

    @Test
    void rendersEachPageOnceAndKeepsTheImage() throws IOException {
        DocumentSummary summary = documents.importPdf(course.id(), null, pdf("Cells.pdf", "cells"));
        h.t.repos.documents().setPageCount(summary.id(), 3);

        RenderedPage page = documents.renderPage(summary.id(), 2, 1.5);
        RenderedPage again = documents.renderPage(summary.id(), 2, 1.5);

        assertThat(renders).hasValue(1);
        assertThat(page.widthPx()).isEqualTo(20);
        assertThat(page.heightPx()).isEqualTo(30);
        assertThat(again.png()).isEqualTo(page.png());
        documents.renderPage(summary.id(), 2, 2);
        assertThat(renders).hasValue(2);

        assertThatThrownBy(() -> documents.renderPage(summary.id(), 2, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> documents.renderPage(summary.id(), 2, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertMessage(() -> documents.renderPage(summary.id(), 4, 1), DocumentServiceImpl.PAGE_NOT_FOUND);
        assertMessage(() -> documents.renderPage(summary.id(), 0, 1), DocumentServiceImpl.PAGE_NOT_FOUND);
        Document lost = h.t.document(course, null, "Lost");
        assertThatThrownBy(() -> documents.renderPage(lost.id(), 1, 1)).isInstanceOf(NotFoundException.class);

        renderer = (pdf, number, scale) -> {
            throw new IOException("broken");
        };
        documents = documents(Long.MAX_VALUE);
        assertMessage(() -> documents.renderPage(summary.id(), 3, 1), DocumentServiceImpl.PAGE_NOT_SHOWN);
    }

    @Test
    void locatesPassagesOnAPageAndShowsNothingWhenItCannot() throws IOException {
        DocumentSummary summary = documents.importPdf(course.id(), null, pdf("Cells.pdf", "cells"));

        assertThat(documents.locatePassage(summary.id(), 1, "The cell")).containsExactly(RECT);
        assertThat(documents.locatePassage(summary.id(), 1, "The atom")).isEmpty();
        assertThat(documents.locatePassage(summary.id(), 1, " ")).isEmpty();
        assertThat(documents.locatePassage(summary.id(), 0, "The cell")).isEmpty();

        locator = (pdf, page, passage) -> {
            throw new IOException("broken");
        };
        documents = documents(Long.MAX_VALUE);
        assertThat(documents.locatePassage(summary.id(), 1, "The cell")).isEmpty();
    }

    @Test
    void queuesDocumentsWhoseImportWasInterrupted() {
        Document waiting = h.t.document(course, null, "Waiting");
        Document carried = h.t.document(course, null, "Carried");
        h.jobs.enqueue(JobSpec.of(JobKind.INGEST_DOCUMENT, carried.id(), null));
        Document ready = h.t.document(course, null, "Ready");
        h.t.repos.documents().markReady(ready.id());

        assertThat(documents.resumeInterrupted()).isEqualTo(1);
        assertThat(documents.resumeInterrupted()).isZero();

        assertThat(h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED)).extracting(Job::subjectId, Job::priority)
                .containsExactlyInAnyOrder(tuple(carried.id(), JobSpec.USER_PRIORITY),
                        tuple(waiting.id(), JobSpec.BACKGROUND_PRIORITY));
    }

    @Test
    void namesDocumentsAfterTheirFile() {
        assertThat(DocumentServiceImpl.titleOf("Cell Biology.PDF")).isEqualTo("Cell Biology");
        assertThat(DocumentServiceImpl.titleOf(".pdf")).isEqualTo(".pdf");
        assertThat(DocumentServiceImpl.titleOf("x".repeat(300) + ".pdf")).hasSize(200);
        assertThat(DocumentServiceImpl.pngSize(new byte[] {1, 2, 3})).containsExactly(0, 0);
    }

    private DocumentServiceImpl documents(long maxBytes) {
        return new DocumentServiceImpl(h.t.repos, h.events, h.t.clock, h.jobs, h.index, h.pdfs,
                (pdf, page, scale) -> renderer.renderPng(pdf, page, scale),
                (pdf, page, passage) -> locator.locate(pdf, page, passage), maxBytes);
    }

    private Path pdf(String name, String body) throws IOException {
        Path folder = Files.createDirectories(dir.resolve("sources"));
        return Files.writeString(folder.resolve(name), "%PDF-1.7\n" + body + "\n%%EOF\n", StandardCharsets.US_ASCII);
    }

    private LearningItem sourcedItem(Chunk source, int page) {
        LearningItem item = LearningItem.draft(course.id(), cells.id(), ItemType.FLASHCARD, "What is on page " + page + "?",
                "A cell", "", List.of(), Difficulty.MEDIUM, source.id(), page, ItemOrigin.AI, h.t.now());
        h.t.repos.items().insert(item);
        return item;
    }

    private String sourceOf(LearningItem item) {
        return h.t.repos.items().get(item.id()).sourceChunkId();
    }

    private static IngestPayload payload(Job job) {
        return Json.read(job.payloadJson(), IngestPayload.class);
    }

    private void page(Document document, int number, PageSource source, String text) {
        h.t.repos.documents().upsertPage(new DocumentPage(document.id(), number, text, source, false, null, false,
                h.t.now(), h.t.now()));
    }

    private static byte[] png(int width, int height) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }
}
