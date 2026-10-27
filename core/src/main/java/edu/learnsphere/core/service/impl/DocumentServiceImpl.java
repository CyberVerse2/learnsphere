package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.PageSource;
import edu.learnsphere.core.service.DocumentService;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.RenderedPage;
import edu.learnsphere.core.service.SourcePassage;
import edu.learnsphere.core.service.SourceRef;
import edu.learnsphere.core.spi.NormalizedRect;
import edu.learnsphere.core.spi.PageRenderer;
import edu.learnsphere.core.spi.PassageLocator;
import edu.learnsphere.core.spi.SearchIndex;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link DocumentService} over the profile database, its {@link PdfStore} and search index.
 *
 * <p><b>Import.</b> {@link #importPdf} checks that the file is a PDF, copies it content-addressed into the
 * profile ({@code files/<sha256>.pdf}; the same PDF imported again reuses the stored file), records the
 * document as queued and enqueues a {@link JobKind#INGEST_DOCUMENT} job whose subject is the document id
 * (payload {@link IngestPayload}). The import pipeline's handler does the rest and reports through
 * {@link DocumentChanged}. A PDF larger than {@link #MAX_BYTES} is recorded as failed without copying it, so
 * the creator sees why it was not imported.
 *
 * <p><b>Scanned pages.</b> Pages read from images wait for review. Editing or approving the last of them
 * moves a document waiting in {@link DocumentStatus#NEEDS_REVIEW} back to the queue so the import continues.
 * Changing the text of a page of a ready document queues the import again to make its passages from the
 * corrected text. Nothing is deleted here: the old passages stay searchable, and the items drafted from them
 * keep their source, until the handler swaps in the new passages with
 * {@link edu.learnsphere.core.db.ChunkRepository#replaceForDocument}, which links each item to the new passage
 * covering its page. Every re-queue puts the document back at {@link DocumentStatus#QUEUED}; the handler
 * continues from what is stored (see {@link JobKind#INGEST_DOCUMENT}).
 *
 * <p>Changes to documents of courses imported from a package are refused with a {@link ConflictException}.
 * Thread-safe.
 */
public final class DocumentServiceImpl implements DocumentService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentServiceImpl.class);

    /** Largest PDF the import accepts: 300 MB. */
    public static final long MAX_BYTES = 300L * 1024 * 1024;

    /** Longest document title. */
    static final int MAX_TITLE = 200;

    /** Longest page text a creator can save. */
    static final int MAX_PAGE_TEXT = 100_000;

    /** Render scales accepted by {@link #renderPage} (1 = 72 DPI). */
    static final double MIN_SCALE = 0.25;
    static final double MAX_SCALE = 6;

    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    /** The header may follow a little leading junk, as PDF readers allow. */
    private static final int HEADER_SEARCH_BYTES = 1024;

    private static final Set<DocumentStatus> PROCESSING = EnumSet.of(DocumentStatus.QUEUED, DocumentStatus.CHECKING,
            DocumentStatus.EXTRACTING, DocumentStatus.READING, DocumentStatus.INDEXING);

    static final UserMessage NOT_A_PDF = UserMessage.of("This file isn't a PDF. Choose a PDF file and try again.");
    static final UserMessage FILE_UNREADABLE =
            UserMessage.of("This file couldn't be opened. Check that it's still there and try again.");
    static final UserMessage ALREADY_IN_COURSE = UserMessage.of("This PDF is already in the course.");
    static final UserMessage TITLE_REQUIRED = UserMessage.of("Enter a title for this PDF.");
    static final UserMessage TITLE_TOO_LONG = UserMessage.of("This title is too long. Shorten it and try again.");
    static final UserMessage PAGE_NOT_FOUND = UserMessage.of("This page isn't in the PDF.");
    static final UserMessage PAGE_TEXT_TOO_LONG = UserMessage.of("This page's text is too long. Shorten it and try again.");
    static final UserMessage ONLY_SCANNED_PAGES = UserMessage.of("Only pages read from scanned images can be edited.");
    static final UserMessage PAGE_NOT_SHOWN = UserMessage.of("This page couldn't be shown. Try again.");

    /**
     * Payload of the {@link JobKind#INGEST_DOCUMENT} jobs this service queues; the job's subject is the same
     * document id. The handler contract is on {@link JobKind#INGEST_DOCUMENT}: whatever the reason, the job
     * continues from what is stored and never starts the document over.
     *
     * @param documentId the document to import
     * @param reason     why the job was queued; for logs and progress lines only, since the handler works out
     *                   what is left from what is stored (null in jobs queued by other code)
     */
    public record IngestPayload(String documentId, Reason reason) {

        /** Why an import job was queued. */
        public enum Reason {
            /** A PDF was just added to a course. */
            IMPORT,
            /** The creator finished checking the pages read from scanned images. */
            CONTINUE_AFTER_REVIEW,
            /** The creator asked to try a failed or unfinished import again. */
            RETRY,
            /**
             * The creator changed the text of a page of a ready document; its passages are outdated
             * ({@link edu.learnsphere.core.db.ChunkRepository#isOutdated}) and are made again.
             */
            PAGES_CHANGED,
            /** The app stopped while the document was being imported. */
            RESUME
        }
    }

    private final Repositories repos;
    private final EventBus events;
    private final Clock clock;
    private final JobQueue jobs;
    private final PdfStore pdfs;
    private final IndexUpdates index;
    private final PageRenderer renderer;
    private final PassageLocator locator;
    private final long maxBytes;

    /**
     * @param index    the profile's search index (passages of removed, renamed and moved documents)
     * @param pdfs     the profile's stored PDFs, shared with {@link CourseServiceImpl}
     * @param renderer renders pages for {@link #renderPage}
     * @param locator  finds passages for {@link #locatePassage}
     */
    public DocumentServiceImpl(Repositories repos, EventBus events, Clock clock, JobQueue jobs, SearchIndex index,
                               PdfStore pdfs, PageRenderer renderer, PassageLocator locator) {
        this(repos, events, clock, jobs, index, pdfs, renderer, locator, MAX_BYTES);
    }

    /** As the public constructor, with another size limit (for tests). */
    DocumentServiceImpl(Repositories repos, EventBus events, Clock clock, JobQueue jobs, SearchIndex index, PdfStore pdfs,
                        PageRenderer renderer, PassageLocator locator, long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be positive: " + maxBytes);
        }
        this.maxBytes = maxBytes;
        this.repos = Objects.requireNonNull(repos, "repos");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.index = new IndexUpdates(Objects.requireNonNull(index, "index"), repos, jobs);
        this.pdfs = Objects.requireNonNull(pdfs, "pdfs");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.locator = Objects.requireNonNull(locator, "locator");
    }

    // ---- import ------------------------------------------------------------------------------

    @Override
    public DocumentSummary importPdf(String courseId, String moduleId, Path pdf) {
        Objects.requireNonNull(pdf, "pdf");
        ServiceSupport.requireEditableCourse(repos, courseId);
        ServiceSupport.requireModuleOf(repos, courseId, moduleId);
        Path source = pdf.toAbsolutePath();
        long size = requirePdf(source);
        String fileName = source.getFileName().toString();
        String title = titleOf(fileName);

        Document document;
        if (size > maxBytes) {
            document = recordTooLarge(courseId, moduleId, title, fileName, source, size);
        } else {
            try (PdfStore.Staged staged = pdfs.stage(source)) {
                requireNotInCourse(courseId, staged.sha256());
                document = pdfs.commit(staged, () -> repos.database().inTransaction(h -> {
                    requireNotInCourse(courseId, staged.sha256());
                    Document queued = Document.queued(courseId, moduleId, title, fileName, staged.sha256(),
                            staged.byteSize(), clock.millis());
                    repos.documents().insert(queued);
                    AuditAction.IMPORT.record(repos.audit(), EntityType.DOCUMENT, queued.id(), fileName);
                    return queued;
                }));
            }
            enqueueIngestion(document.id(), IngestPayload.Reason.IMPORT, JobSpec.USER_PRIORITY);
        }
        publish(document, ChangeType.CREATED);
        return summary(document);
    }

    /** A PDF over the size limit: recorded as failed so the creator sees why, and never copied. */
    private Document recordTooLarge(String courseId, String moduleId, String title, String fileName, Path source,
                                    long size) {
        String sha;
        try {
            sha = PdfStore.sha256(source);
        } catch (IOException | UncheckedIOException e) {
            throw new InvalidInputException(FILE_UNREADABLE, "Cannot read " + source, e);
        }
        requireNotInCourse(courseId, sha);
        return repos.database().inTransaction(h -> {
            requireNotInCourse(courseId, sha);
            Document queued = Document.queued(courseId, moduleId, title, fileName, sha, size, clock.millis());
            repos.documents().insert(queued);
            repos.documents().markFailed(queued.id(), DocumentError.TOO_LARGE, null);
            AuditAction.IMPORT.record(repos.audit(), EntityType.DOCUMENT, queued.id(), fileName);
            return repos.documents().get(queued.id());
        });
    }

    /**
     * Queues ingestion for documents left in a processing stage without an active job (for example when the
     * app stopped between recording an import and queuing its job). Called when the workspace opens, after
     * the job queue resumed its own jobs; returns how many were queued.
     */
    public int resumeInterrupted() {
        int queued = 0;
        for (Document document : repos.documents().listByStatus(PROCESSING)) {
            if (repos.jobs().findActive(JobKind.INGEST_DOCUMENT, document.id()).isEmpty()) {
                enqueueIngestion(document.id(), IngestPayload.Reason.RESUME, JobSpec.BACKGROUND_PRIORITY);
                queued++;
            }
        }
        return queued;
    }

    /**
     * Queues the document's import job, or raises the priority of the one already carrying it (the queue keeps
     * one active import job per document).
     */
    private void enqueueIngestion(String documentId, IngestPayload.Reason reason, int priority) {
        jobs.enqueue(JobSpec.of(JobKind.INGEST_DOCUMENT, documentId, new IngestPayload(documentId, reason))
                .withPriority(priority));
    }

    // ---- reading -----------------------------------------------------------------------------

    @Override
    public List<DocumentSummary> list(String courseId) {
        repos.courses().get(courseId);
        Map<String, String> moduleTitles = moduleTitles(courseId);
        Map<String, Integer> reviewCounts = repos.documents().pagesNeedingReviewByDocument(courseId);
        return repos.documents().listByCourse(courseId).stream()
                .map(d -> summary(d, d.moduleId() == null ? null : moduleTitles.get(d.moduleId()),
                        reviewCounts.getOrDefault(d.id(), 0)))
                .toList();
    }

    @Override
    public DocumentSummary get(String documentId) {
        return summary(repos.documents().get(documentId));
    }

    @Override
    public List<DocumentPage> pagesNeedingReview(String documentId) {
        repos.documents().get(documentId);
        return repos.documents().listPagesNeedingReview(documentId);
    }

    @Override
    public DocumentPage page(String documentId, int pageNumber) {
        repos.documents().get(documentId);
        return requirePage(documentId, pageNumber);
    }

    @Override
    public List<SourcePassage> passages(Collection<String> chunkIds) {
        Objects.requireNonNull(chunkIds, "chunkIds");
        Set<String> ids = new LinkedHashSet<>();
        for (String id : chunkIds) {
            if (id != null) {
                ids.add(id);
            }
        }
        return repos.chunks().listWithTitles(ids).stream().map(found -> {
            var chunk = found.chunk();
            return new SourcePassage(new SourceRef(chunk.documentId(), found.documentTitle(), chunk.pageStart(),
                    chunk.pageEnd(), chunk.id()), chunk.sectionTitle(), chunk.text());
        }).toList();
    }

    // ---- changes -----------------------------------------------------------------------------

    @Override
    public void rename(String documentId, String title) {
        Document document = editable(documentId);
        String clean = ServiceSupport.requireText(title, MAX_TITLE, TITLE_REQUIRED, TITLE_TOO_LONG, "document title");
        if (clean.equals(document.title())) {
            return;
        }
        repos.documents().rename(documentId, clean);
        index.refreshDocument(documentId);
        publish(repos.documents().get(documentId), ChangeType.UPDATED);
    }

    @Override
    public void moveToModule(String documentId, String moduleId) {
        Document document = editable(documentId);
        ServiceSupport.requireModuleOf(repos, document.courseId(), moduleId);
        if (Objects.equals(document.moduleId(), moduleId)) {
            return;
        }
        repos.documents().setModule(documentId, moduleId);
        index.refreshDocument(documentId);
        publish(repos.documents().get(documentId), ChangeType.UPDATED);
    }

    @Override
    public void remove(String documentId) {
        Document document = editable(documentId);
        ServiceSupport.cancelJobsFor(jobs, repos, List.of(documentId));
        int itemsFromIt = repos.items().countBySourceDocument(documentId);
        pdfs.delete(List.of(document.fileSha256()), List.of(documentId), () -> {
            repos.database().useTransaction(h -> {
                repos.documents().delete(documentId);
                AuditAction.REMOVE.record(repos.audit(), EntityType.DOCUMENT, documentId, document.title());
            });
            return null;
        });
        index.removeDocument(documentId);
        events.publish(new DocumentChanged(document.id(), document.courseId(), document.status(), document.progress(), 0, 0,
                null, ChangeType.DELETED));
        if (itemsFromIt > 0) {
            events.publish(new ItemsChanged(document.courseId(), null, Set.of(), ChangeType.UPDATED));
        }
    }

    @Override
    public void retry(String documentId) {
        Document document = editable(documentId);
        if (document.status() == DocumentStatus.FAILED) {
            if (!pdfs.contains(document.fileSha256())) {
                DocumentError error = document.byteSize() > maxBytes ? DocumentError.TOO_LARGE : DocumentError.MISSING_FILE;
                repos.documents().markFailed(documentId, error, null);
                publish(repos.documents().get(documentId), ChangeType.UPDATED);
                return;
            }
            repos.documents().updateStatus(documentId, DocumentStatus.QUEUED, 0, null);
            enqueueIngestion(documentId, IngestPayload.Reason.RETRY, JobSpec.USER_PRIORITY);
            publish(repos.documents().get(documentId), ChangeType.UPDATED);
        } else if (PROCESSING.contains(document.status())) {
            // Still importing: make sure a job is carrying it, at the priority of work the user waits for.
            enqueueIngestion(documentId, IngestPayload.Reason.RETRY, JobSpec.USER_PRIORITY);
        }
    }

    @Override
    public void editPageText(String documentId, int pageNumber, String text) {
        Document document = editable(documentId);
        DocumentPage page = requirePage(documentId, pageNumber);
        if (page.source() != PageSource.AI_READ) {
            throw new ConflictException(ONLY_SCANNED_PAGES, "Page " + pageNumber + " of " + documentId + " has a text layer");
        }
        String clean = text == null ? "" : text.strip();
        if (clean.length() > MAX_PAGE_TEXT) {
            throw new InvalidInputException(PAGE_TEXT_TOO_LONG, "Page text of " + clean.length() + " characters");
        }
        boolean changed = !clean.equals(page.text());
        if (changed) {
            repos.documents().updatePageText(documentId, pageNumber, clean, true);
        } else if (!page.reviewed()) {
            repos.documents().markPagesReviewed(documentId, List.of(pageNumber));
        }
        continueAfterReview(document, changed);
    }

    @Override
    public void approvePages(String documentId, Collection<Integer> pageNumbers) {
        Document document = editable(documentId);
        Set<Integer> pages = new LinkedHashSet<>(pageNumbers);
        if (pages.isEmpty()) {
            return;
        }
        for (int pageNumber : pages) {
            requirePage(documentId, pageNumber);
        }
        repos.documents().markPagesReviewed(documentId, pages);
        continueAfterReview(document, false);
    }

    /**
     * After a review: a document waiting for review with no page left to check goes back to the queue so the
     * import continues; a ready document whose page text changed is queued so its passages are made again.
     * Its passages, search entries and item sources are left as they are: the handler replaces the passages
     * in one step (see {@link IngestPayload.Reason#PAGES_CHANGED}).
     */
    private void continueAfterReview(Document before, boolean textChanged) {
        String documentId = before.id();
        int left = repos.documents().countPagesNeedingReview(documentId);
        Document current = repos.documents().get(documentId);
        if (left == 0 && current.status() == DocumentStatus.NEEDS_REVIEW) {
            repos.documents().updateStatus(documentId, DocumentStatus.QUEUED, 0, null);
            enqueueIngestion(documentId, IngestPayload.Reason.CONTINUE_AFTER_REVIEW, JobSpec.USER_PRIORITY);
        } else if (textChanged && current.status() == DocumentStatus.READY) {
            repos.documents().updateStatus(documentId, DocumentStatus.QUEUED, 0, null);
            enqueueIngestion(documentId, IngestPayload.Reason.PAGES_CHANGED, JobSpec.USER_PRIORITY);
        }
        publish(repos.documents().get(documentId), ChangeType.UPDATED);
    }

    // ---- pages as images ---------------------------------------------------------------------

    @Override
    public RenderedPage renderPage(String documentId, int pageNumber, double scale) {
        if (!(scale >= MIN_SCALE && scale <= MAX_SCALE)) {
            throw new IllegalArgumentException("Scale must be " + MIN_SCALE + "-" + MAX_SCALE + ": " + scale);
        }
        Document document = repos.documents().get(documentId);
        requirePageInRange(document, pageNumber);
        Path pdf = requireStoredFile(document);
        float renderScale = (float) scale;
        Path cached = pdfs.renderFile(documentId, pageNumber, renderScale);
        byte[] png = readCached(cached);
        if (png == null) {
            try {
                png = renderer.renderPng(pdf, pageNumber, renderScale);
            } catch (IOException | RuntimeException e) {
                throw new LearnSphereException(PAGE_NOT_SHOWN,
                        "Rendering page " + pageNumber + " of " + documentId + " failed", e);
            }
            writeCached(cached, png);
        }
        int[] size = pngSize(png);
        return new RenderedPage(documentId, pageNumber, png, size[0], size[1]);
    }

    @Override
    public List<NormalizedRect> locatePassage(String documentId, int pageNumber, String passage) {
        Document document = repos.documents().get(documentId);
        if (passage == null || passage.isBlank() || pageNumber < 1
                || (document.pageCount() > 0 && pageNumber > document.pageCount())) {
            return List.of();
        }
        Path pdf = pdfs.file(document.fileSha256());
        if (!Files.isRegularFile(pdf)) {
            return List.of();
        }
        try {
            return List.copyOf(locator.locate(pdf, pageNumber, passage));
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not locate a passage on page {} of {}", pageNumber, documentId, e);
            return List.of();
        }
    }

    private static byte[] readCached(Path cached) {
        try {
            if (Files.isRegularFile(cached) && Files.size(cached) > 0) {
                return Files.readAllBytes(cached);
            }
        } catch (IOException e) {
            LOG.debug("Cached page image {} unreadable; rendering again", cached, e);
        }
        return null;
    }

    private static void writeCached(Path cached, byte[] png) {
        Path temp = null;
        try {
            temp = Files.createTempFile(cached.getParent(), PdfStore.RENDER_PREFIX, PdfStore.PARTIAL_SUFFIX);
            Files.write(temp, png);
            try {
                Files.move(temp, cached, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, cached, StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("Could not cache page image {}", cached, e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    LOG.debug("Could not delete {}", temp, e);
                }
            }
        }
    }

    /** Width and height from a PNG's header, or {@code {0, 0}} when the bytes are not a PNG. */
    static int[] pngSize(byte[] png) {
        byte[] signature = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (png.length < 24) {
            return new int[] {0, 0};
        }
        for (int i = 0; i < signature.length; i++) {
            if (png[i] != signature[i]) {
                return new int[] {0, 0};
            }
        }
        return new int[] {readInt(png, 16), readInt(png, 20)};
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFF) << 24) | ((bytes[offset + 1] & 0xFF) << 16) | ((bytes[offset + 2] & 0xFF) << 8)
                | (bytes[offset + 3] & 0xFF);
    }

    // ---- helpers -----------------------------------------------------------------------------

    /**
     * Checks that {@code source} is a readable file starting with a PDF header and returns its size.
     *
     * @throws InvalidInputException when it is missing, unreadable or not a PDF
     */
    private static long requirePdf(Path source) {
        if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
            throw new InvalidInputException(FILE_UNREADABLE, "Not a readable file: " + source);
        }
        byte[] head;
        long size;
        try (InputStream in = Files.newInputStream(source)) {
            size = Files.size(source);
            head = in.readNBytes(HEADER_SEARCH_BYTES);
        } catch (IOException e) {
            throw new InvalidInputException(FILE_UNREADABLE, "Cannot read " + source, e);
        }
        if (indexOf(head, PDF_MAGIC) < 0) {
            throw new InvalidInputException(NOT_A_PDF, "No PDF header in " + source);
        }
        return size;
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /** The file name without its {@code .pdf} extension, or the whole name when that leaves nothing. */
    static String titleOf(String fileName) {
        String title = fileName;
        if (title.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            title = title.substring(0, title.length() - 4);
        }
        title = title.strip();
        if (title.isEmpty()) {
            title = fileName.strip();
        }
        return title.length() > MAX_TITLE ? title.substring(0, MAX_TITLE).strip() : title;
    }

    private void requireNotInCourse(String courseId, String sha256) {
        if (repos.documents().findByCourseAndSha(courseId, sha256).isPresent()) {
            throw new ConflictException(ALREADY_IN_COURSE, "PDF " + sha256 + " is already in course " + courseId);
        }
    }

    /** The document, when its course was created in this profile. */
    private Document editable(String documentId) {
        Document document = repos.documents().get(documentId);
        ServiceSupport.requireEditableCourse(repos, document.courseId());
        return document;
    }

    private DocumentPage requirePage(String documentId, int pageNumber) {
        return repos.documents().findPage(documentId, pageNumber)
                .orElseThrow(() -> new NotFoundException(PAGE_NOT_FOUND, "Page " + pageNumber + " of " + documentId));
    }

    private static void requirePageInRange(Document document, int pageNumber) {
        if (pageNumber < 1 || (document.pageCount() > 0 && pageNumber > document.pageCount())) {
            throw new NotFoundException(PAGE_NOT_FOUND, "Page " + pageNumber + " of " + document.id());
        }
    }

    private Path requireStoredFile(Document document) {
        Path pdf = pdfs.file(document.fileSha256());
        if (!Files.isRegularFile(pdf)) {
            throw new NotFoundException(DocumentError.MISSING_FILE.userMessage(), "Missing file " + pdf);
        }
        return pdf;
    }

    private Map<String, String> moduleTitles(String courseId) {
        Map<String, String> titles = new HashMap<>();
        repos.modules().listByCourse(courseId).forEach(m -> titles.put(m.id(), m.title()));
        return titles;
    }

    private DocumentSummary summary(Document document) {
        String moduleTitle = document.moduleId() == null ? null
                : repos.modules().find(document.moduleId()).map(CourseModule::title).orElse(null);
        return summary(document, moduleTitle, repos.documents().countPagesNeedingReview(document.id()));
    }

    static DocumentSummary summary(Document d, String moduleTitle, int pagesNeedingReview) {
        int[] step = DocumentSummary.parseStep(d.statusDetail());
        return new DocumentSummary(d.id(), d.courseId(), d.moduleId(), moduleTitle, d.title(), d.fileName(), d.byteSize(),
                d.pageCount(), d.status(), d.progress(), step[0], step[1], error(d), pagesNeedingReview, d.createdAt(),
                d.updatedAt());
    }

    private static UserMessage error(Document d) {
        if (d.status() != DocumentStatus.FAILED) {
            return null;
        }
        return (d.errorCode() == null ? DocumentError.UNEXPECTED : d.errorCode()).userMessage();
    }

    private void publish(Document d, ChangeType type) {
        int[] step = DocumentSummary.parseStep(d.statusDetail());
        events.publish(new DocumentChanged(d.id(), d.courseId(), d.status(), d.progress(), Math.max(0, step[0]),
                Math.max(0, step[1]), error(d), type));
    }
}
