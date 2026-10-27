package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.PageSource;
import edu.learnsphere.core.service.AssistantService;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.JobContext;
import edu.learnsphere.core.service.JobHandler;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.ProfileContext;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.SettingsService;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.core.spi.ChunkDraft;
import edu.learnsphere.core.spi.Chunker;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.spi.FigureDescriber;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.PageReader;
import edu.learnsphere.core.spi.PageRenderer;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.PageTranscription;
import edu.learnsphere.core.spi.PdfInspection;
import edu.learnsphere.core.spi.PdfInspector;
import edu.learnsphere.core.spi.PdfTextExtractor;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.util.CancellationToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * The {@link JobKind#INGEST_DOCUMENT} job handler: turns an imported PDF into passages that can answer
 * questions. Register it with {@code jobs.register(JobKind.INGEST_DOCUMENT, ingestion)} and enqueue imports
 * with {@link #jobSpec(String)}.
 *
 * <p>Stages, each recorded in {@code documents.status} with a step counter ({@code "14/120"},
 * {@link DocumentSummary#formatStep}) and announced with {@link DocumentChanged}; the job's progress line
 * says the same in words ("Reading page 14 of 120"):
 *
 * <ol>
 *   <li><b>Check</b> ({@code checking}): the stored copy {@code files/<sha256>.pdf} exists (copied from the
 *       payload's source path when it does not), is at most 300 MB, opens, is not password-protected and has at
 *       most 2,000 pages.</li>
 *   <li><b>Extract</b> ({@code extracting}): the text layer of every page into {@code document_pages}
 *       ({@code text_layer}).</li>
 *   <li><b>Read</b> ({@code reading}): pages with under 25 characters of text and an image are rendered at
 *       {@value #READ_DPI} DPI (at most {@value #READ_MAX_PIXELS} pixels) and transcribed by the
 *       {@link PageReader} ({@code ai_read}, not reviewed). While
 *       any such page is unreviewed the document waits in {@code needs_review} and the job ends: unreviewed
 *       pages are never chunked, embedded or indexed. Approving the last page enqueues this job again, which
 *       continues.</li>
 *   <li><b>Figures</b> ({@code reading}, only when {@link SettingKeys#DESCRIBE_FIGURES} is on): text-layer
 *       pages whose images cover at least {@value #FIGURE_MIN_COVERAGE} of the page are rendered at
 *       {@value #READ_DPI} DPI and get {@link FigureDescriber} notes, added to their passages as
 *       {@code "Figure: …"} lines. Best effort: a page that cannot be described is left without notes.</li>
 *   <li><b>Chunk, embed, index</b> ({@code indexing}): {@link Chunker} over the usable pages, embeddings in
 *       batches of {@value #EMBED_BATCH} stored in {@code chunks}, then {@link SearchIndex#upsert} and
 *       {@code ready}.</li>
 * </ol>
 *
 * <p>Every stage is idempotent and resumable: the handler works out what is left from what is stored
 * (pages, AI-read pages, figure notes, chunks, embeddings), so a job interrupted at any point continues where
 * it stopped. Failures mark the document {@code failed} with a {@link DocumentError} (encrypted, damaged, too
 * large, too many pages, no readable text, …) and fail the job with the same sentence.
 *
 * <p>Study-assistant requests (reading pages, describing figures, embedding) wait while the assistant is
 * starting; reading and describing run at {@link edu.learnsphere.core.spi.ChatPriority#BACKGROUND}, which their
 * implementations use. Thread-safe and stateless between jobs.
 */
public final class IngestionService implements JobHandler {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionService.class);

    /** Pages with fewer non-whitespace characters than this and an image are read from the page image. */
    public static final int MIN_TEXT_CHARS = 25;

    /**
     * Resolution pages are rendered at for the study assistant, both scanned pages to read and pages with
     * figures to describe. The assistant's work on an image grows faster than its pixel count; at this
     * resolution scanned pages read as accurately as at 150 DPI and figure labels stay legible, for about
     * two thirds of the image tokens.
     */
    public static final int READ_DPI = 120;

    /**
     * Largest page image shown to the study assistant, in pixels: an A4 or Letter page at {@link #READ_DPI}.
     * Larger pages, such as scans saved at poster or tabloid size, are rendered at a lower resolution, so every
     * page costs the assistant about the same.
     */
    public static final long READ_MAX_PIXELS = 1_400_000L;

    /** Share of a page images must cover for its figures to be described. */
    public static final double FIGURE_MIN_COVERAGE = 0.10;

    /** Passages embedded per request. */
    public static final int EMBED_BATCH = 16;

    private static final int PAGE_WRITE_BATCH = 16;
    private static final int INDEX_BATCH = 64;
    /** When resuming with at most this many pages missing, extract them one by one instead of the whole file. */
    private static final int SINGLE_PAGE_RESUME_LIMIT = 24;
    private static final int STEP_REPORTS = 100;

    // Share of the job's overall progress each stage covers.
    private static final double CHECK_END = 0.02;
    private static final double EXTRACT_END = 0.30;
    private static final double READ_END = 0.55;
    private static final double FIGURES_END = 0.65;
    private static final double CHUNK_END = 0.70;
    private static final double EMBED_END = 0.95;

    /**
     * The job's payload.
     *
     * @param documentId the document to import (the job's subject is used when null)
     * @param sourcePath where the original PDF is, used to store a copy under {@code files/} when it is not
     *                   there yet; null when the copy was stored before the job was queued
     */
    public record Payload(String documentId, String sourcePath) {
    }

    /**
     * Size limits of an import.
     *
     * @param maxBytes largest file accepted
     * @param maxPages most pages accepted
     */
    public record Limits(long maxBytes, int maxPages) {

        /** 300 MB and 2,000 pages. */
        public static final Limits DEFAULT = new Limits(300L * 1024 * 1024, 2_000);

        public Limits {
            if (maxBytes <= 0 || maxPages <= 0) {
                throw new IllegalArgumentException("Limits must be positive");
            }
        }
    }

    private final Repositories repos;
    private final EventBus events;
    private final Clock clock;
    private final SettingsService settings;
    private final BackgroundAi ai;
    private final AppPaths.ProfilePaths paths;
    private final SearchIndex index;
    private final PdfInspector inspector;
    private final PdfTextExtractor extractor;
    private final Chunker chunker;
    private final PageRenderer renderer;
    private final Embedder embedder;
    private final PageReader pageReader;
    private final FigureDescriber figureDescriber;
    private final Limits limits;

    /** An ingestion handler with the default {@link Limits}. */
    public IngestionService(Repositories repos, EventBus events, SettingsService settings, AssistantService assistant,
                            AppPaths.ProfilePaths paths, SearchIndex index, PdfInspector inspector,
                            PdfTextExtractor extractor, Chunker chunker, PageRenderer renderer, Embedder embedder,
                            PageReader pageReader, FigureDescriber figureDescriber) {
        this(repos, events, settings, new BackgroundAi(assistant), paths, index, inspector, extractor, chunker, renderer,
                embedder, pageReader, figureDescriber, Limits.DEFAULT);
    }

    IngestionService(Repositories repos, EventBus events, SettingsService settings, BackgroundAi ai,
                     AppPaths.ProfilePaths paths, SearchIndex index, PdfInspector inspector, PdfTextExtractor extractor,
                     Chunker chunker, PageRenderer renderer, Embedder embedder, PageReader pageReader,
                     FigureDescriber figureDescriber, Limits limits) {
        this.repos = Objects.requireNonNull(repos, "repos");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = repos.clock();
        this.settings = Objects.requireNonNull(settings, "settings");
        this.ai = Objects.requireNonNull(ai, "ai");
        this.paths = Objects.requireNonNull(paths, "paths");
        this.index = Objects.requireNonNull(index, "index");
        this.inspector = Objects.requireNonNull(inspector, "inspector");
        this.extractor = Objects.requireNonNull(extractor, "extractor");
        this.chunker = Objects.requireNonNull(chunker, "chunker");
        this.renderer = Objects.requireNonNull(renderer, "renderer");
        this.embedder = Objects.requireNonNull(embedder, "embedder");
        this.pageReader = Objects.requireNonNull(pageReader, "pageReader");
        this.figureDescriber = Objects.requireNonNull(figureDescriber, "figureDescriber");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** The handler of an opened profile, from the workspace's capabilities, index, settings and assistant. */
    public static IngestionService create(ProfileContext context, Capabilities capabilities, SearchIndex index,
                                          SettingsService settings, AssistantService assistant) {
        return new IngestionService(context.repositories(), context.events(), settings, assistant, context.paths(), index,
                capabilities.pdfInspector(), capabilities.textExtractor(), capabilities.chunker(),
                capabilities.pageRenderer(), capabilities.embedder(), capabilities.pageReader(),
                capabilities.figureDescriber());
    }

    /** The job that imports (or continues importing) a document whose copy is already under {@code files/}. */
    public static JobSpec jobSpec(String documentId) {
        return jobSpec(documentId, null);
    }

    /**
     * The job that imports a document, storing a copy of {@code sourcePath} under {@code files/} first when it
     * is not there yet. Unique per document, so asking again while it runs returns the running job.
     */
    public static JobSpec jobSpec(String documentId, Path sourcePath) {
        Objects.requireNonNull(documentId, "documentId");
        return JobSpec.of(JobKind.INGEST_DOCUMENT, documentId,
                new Payload(documentId, sourcePath == null ? null : sourcePath.toAbsolutePath().toString()));
    }

    @Override
    public void run(JobContext context) {
        Payload payload = payload(context);
        String documentId = payload.documentId() != null ? payload.documentId() : context.job().subjectId();
        if (documentId == null) {
            LOG.warn("Import job {} names no document", context.job().id());
            return;
        }
        Optional<Document> document = repos.documents().find(documentId);
        if (document.isEmpty()) {
            LOG.info("Document {} was removed before its import ran", documentId);
            return;
        }
        new Run(context, document.get(), payload).execute();
    }

    private static Payload payload(JobContext context) {
        try {
            Payload payload = context.payload(Payload.class);
            return payload == null ? new Payload(null, null) : payload;
        } catch (UncheckedIOException | IllegalArgumentException e) {
            LOG.warn("Unreadable payload for import job {}", context.job().id(), e);
            return new Payload(null, null);
        }
    }

    /** One import run. Not shared between threads. */
    private final class Run {

        private final JobContext context;
        private final CancellationToken token;
        private final Payload payload;
        private final String documentId;
        private final String courseId;
        private Document document;
        private Map<Integer, PdfInspection.PageInfo> pageInfo = Map.of();
        private int pageCount;
        /** Structured text-layer pages extracted during this run, by page number. */
        private final Map<Integer, PageText> structured = new HashMap<>();

        Run(JobContext context, Document document, Payload payload) {
            this.context = context;
            this.token = context.token();
            this.payload = payload;
            this.document = document;
            this.documentId = document.id();
            this.courseId = document.courseId();
        }

        void execute() {
            if (document.status() == DocumentStatus.READY) {
                return;
            }
            if (document.status() == DocumentStatus.NEEDS_REVIEW && repos.documents().countPagesNeedingReview(documentId) > 0) {
                return;
            }
            try {
                Path file = check();
                extract(file);
                read(file);
                requireSomeText();
                if (repos.documents().countPagesNeedingReview(documentId) > 0) {
                    waitForReview();
                    return;
                }
                describeFigures(file);
                List<Chunk> chunks = chunk(file);
                chunks = embed(chunks);
                index(chunks);
                ready();
            } catch (CancellationException e) {
                throw e;
            } catch (StageFailure failure) {
                fail(failure.error, failure);
            } catch (RuntimeException e) {
                if (token.isCancelled()) {
                    throw new CancellationException("Import stopped");
                }
                if (repos.documents().find(documentId).isEmpty()) {
                    LOG.info("Document {} was removed while it was imported", documentId);
                    return;
                }
                fail(isDiskFull(e) ? DocumentError.DISK_FULL : DocumentError.UNEXPECTED, e);
            }
        }

        // ---- check ------------------------------------------------------------------------------

        private Path check() {
            report(DocumentStatus.CHECKING, 0, 0, "Checking your PDF…", 0);
            Path file = paths.pdfFile(document.fileSha256());
            if (!Files.isRegularFile(file)) {
                storeCopy(file);
            }
            long size;
            try {
                size = Files.size(file);
            } catch (IOException e) {
                throw new StageFailure(DocumentError.MISSING_FILE, "Cannot read the size of " + file, e);
            }
            if (size > limits.maxBytes()) {
                throw new StageFailure(DocumentError.TOO_LARGE, "File has " + size + " bytes", null);
            }
            PdfInspection inspection;
            try {
                inspection = inspector.inspect(file);
            } catch (NoSuchFileException e) {
                throw new StageFailure(DocumentError.MISSING_FILE, "File disappeared: " + file, e);
            } catch (IOException e) {
                throw new StageFailure(fileError(e), "Cannot inspect " + file, e);
            }
            if (inspection.encrypted()) {
                throw new StageFailure(DocumentError.ENCRYPTED, "File is encrypted", null);
            }
            if (inspection.damaged() || inspection.pageCount() <= 0) {
                throw new StageFailure(DocumentError.DAMAGED, "File cannot be parsed", null);
            }
            if (inspection.pageCount() > limits.maxPages()) {
                throw new StageFailure(DocumentError.TOO_MANY_PAGES, "File has " + inspection.pageCount() + " pages", null);
            }
            pageCount = inspection.pageCount();
            Map<Integer, PdfInspection.PageInfo> info = new HashMap<>();
            for (PdfInspection.PageInfo page : inspection.pages()) {
                info.put(page.pageNumber(), page);
            }
            pageInfo = info;
            if (document.pageCount() != pageCount) {
                repos.documents().setPageCount(documentId, pageCount);
            }
            return file;
        }

        /** Stores the payload's source file as {@code target}, checking it is the file that was imported. */
        private void storeCopy(Path target) {
            Path source = payload.sourcePath() == null ? null : Path.of(payload.sourcePath());
            if (source == null || !Files.isRegularFile(source)) {
                throw new StageFailure(DocumentError.MISSING_FILE, "No stored copy and no source for " + documentId, null);
            }
            Path temp = null;
            try {
                if (Files.size(source) > limits.maxBytes()) {
                    throw new StageFailure(DocumentError.TOO_LARGE, "Source has " + Files.size(source) + " bytes", null);
                }
                temp = Files.createTempFile(paths.filesDir(), "import-", ".tmp");
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream in = new DigestInputStream(Files.newInputStream(source), digest);
                     OutputStream out = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buffer)) >= 0) {
                        token.throwIfCancelled();
                        out.write(buffer, 0, read);
                    }
                }
                String sha = HexFormat.of().formatHex(digest.digest());
                if (!sha.equals(document.fileSha256())) {
                    throw new StageFailure(DocumentError.MISSING_FILE, "Source changed since it was imported", null);
                }
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                throw new StageFailure(isDiskFull(e) ? DocumentError.DISK_FULL : DocumentError.MISSING_FILE,
                        "Cannot copy " + source, e);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is not available", e);
            } finally {
                deleteQuietly(temp);
            }
        }

        // ---- extract ----------------------------------------------------------------------------

        private void extract(Path file) {
            Set<Integer> stored = new HashSet<>(repos.documents().storedPageNumbers(documentId));
            List<Integer> missing = new ArrayList<>();
            for (int n = 1; n <= pageCount; n++) {
                if (!stored.contains(n)) {
                    missing.add(n);
                }
            }
            if (missing.isEmpty()) {
                return;
            }
            Progress progress = new Progress(DocumentStatus.EXTRACTING, pageCount, CHECK_END, EXTRACT_END,
                    (done, total) -> "Reading page " + done + " of " + total);
            progress.start(pageCount - missing.size());
            List<DocumentPage> buffer = new ArrayList<>();
            try {
                if (!stored.isEmpty() && missing.size() <= SINGLE_PAGE_RESUME_LIMIT) {
                    for (int n : missing) {
                        token.throwIfCancelled();
                        buffer.add(textLayerPage(extractor.extractPage(file, n)));
                        flushPages(buffer, progress, stored);
                    }
                } else {
                    extractor.extract(file, page -> {
                        if (page.pageNumber() >= 1 && page.pageNumber() <= pageCount && !stored.contains(page.pageNumber())) {
                            buffer.add(textLayerPage(page));
                            if (buffer.size() >= PAGE_WRITE_BATCH) {
                                flushPages(buffer, progress, stored);
                            }
                        }
                    }, token);
                }
                flushPages(buffer, progress, stored);
            } catch (NoSuchFileException e) {
                throw new StageFailure(DocumentError.MISSING_FILE, "File disappeared: " + file, e);
            } catch (IOException e) {
                throw new StageFailure(fileError(e), "Cannot extract " + file, e);
            }
            // Pages the extractor skipped are stored empty, so the stage counts as done next time.
            for (int n = 1; n <= pageCount; n++) {
                if (!stored.contains(n)) {
                    buffer.add(page(n, "", PageSource.TEXT_LAYER));
                }
            }
            flushPages(buffer, progress, stored);
            progress.finish();
        }

        private DocumentPage textLayerPage(PageText page) {
            structured.put(page.pageNumber(), page);
            return page(page.pageNumber(), page.text(), PageSource.TEXT_LAYER);
        }

        private DocumentPage page(int number, String text, PageSource source) {
            long now = clock.millis();
            return new DocumentPage(documentId, number, text, source, false, null, coverage(number) > 0, now, now);
        }

        private void flushPages(List<DocumentPage> buffer, Progress progress, Set<Integer> stored) {
            if (buffer.isEmpty()) {
                return;
            }
            repos.documents().upsertPages(buffer);
            for (DocumentPage page : buffer) {
                stored.add(page.pageNumber());
            }
            buffer.clear();
            progress.update(Math.min(stored.size(), pageCount));
        }

        // ---- read scanned pages -----------------------------------------------------------------

        private void read(Path file) {
            List<DocumentPage> pages = repos.documents().listPages(documentId);
            List<DocumentPage> todo = new ArrayList<>();
            int alreadyRead = 0;
            for (DocumentPage page : pages) {
                if (page.source() == PageSource.AI_READ) {
                    alreadyRead++;
                } else if (looksScanned(page)) {
                    todo.add(page);
                }
            }
            if (todo.isEmpty()) {
                return;
            }
            int total = alreadyRead + todo.size();
            Progress progress = new Progress(DocumentStatus.READING, total, EXTRACT_END, READ_END,
                    (done, all) -> "Reading scanned page " + Math.min(done + 1, all) + " of " + all);
            progress.start(alreadyRead);
            int done = alreadyRead;
            for (DocumentPage page : todo) {
                token.throwIfCancelled();
                int number = page.pageNumber();
                byte[] png = render(file, number);
                PageTranscription transcription;
                try {
                    transcription = ai.call(() -> pageReader.read(png, number, token), token);
                } catch (LearnSphereException e) {
                    throw new StageFailure(DocumentError.READ_FAILED, "Page " + number + " could not be read", e);
                }
                String text = transcription.markdown().strip();
                if (transcription.hasIllegibleParts()) {
                    LOG.debug("Page {} of document {} has parts that could not be read: {}", number, documentId,
                            transcription.illegibleSpans());
                }
                repos.documents().upsertPage(new DocumentPage(documentId, number, text, PageSource.AI_READ, false, null,
                        true, page.createdAt(), clock.millis()));
                progress.update(++done);
            }
            progress.finish();
        }

        private boolean looksScanned(DocumentPage page) {
            return page.source() == PageSource.TEXT_LAYER && page.hasImages()
                    && PipelineText.visibleChars(page.text()) < MIN_TEXT_CHARS;
        }

        private byte[] render(Path file, int pageNumber) {
            try {
                return renderer.renderPngAtDpi(file, pageNumber, READ_DPI, READ_MAX_PIXELS);
            } catch (NoSuchFileException e) {
                throw new StageFailure(DocumentError.MISSING_FILE, "File disappeared: " + file, e);
            } catch (IOException e) {
                throw new StageFailure(fileError(e), "Cannot render page " + pageNumber, e);
            }
        }

        private void requireSomeText() {
            boolean anyText = repos.documents().listPages(documentId).stream()
                    .anyMatch(page -> PipelineText.visibleChars(page.text()) > 0);
            if (!anyText) {
                throw new StageFailure(DocumentError.NO_TEXT, "No page has text", null);
            }
        }

        private void waitForReview() {
            repos.documents().updateStatus(documentId, DocumentStatus.NEEDS_REVIEW, 1, null);
            events.publish(DocumentChanged.of(documentId, courseId, DocumentStatus.NEEDS_REVIEW, 1, ChangeType.UPDATED));
            events.publish(new CourseChanged(courseId, ChangeType.UPDATED));
            context.progress(READ_END, "Some scanned pages need your check");
            LOG.info("Document {} waits for scanned pages to be reviewed", documentId);
        }

        // ---- figures ----------------------------------------------------------------------------

        private void describeFigures(Path file) {
            if (!settings.get(SettingKeys.DESCRIBE_FIGURES)) {
                return;
            }
            List<DocumentPage> eligible = repos.documents().listPages(documentId).stream()
                    .filter(page -> page.source() == PageSource.TEXT_LAYER && coverage(page.pageNumber()) >= FIGURE_MIN_COVERAGE)
                    .toList();
            List<DocumentPage> todo = eligible.stream().filter(page -> page.figureNotes() == null).toList();
            if (todo.isEmpty()) {
                return;
            }
            Progress progress = new Progress(DocumentStatus.READING, eligible.size(), READ_END, FIGURES_END,
                    (done, all) -> "Looking at figures… " + Math.min(done + 1, all) + " of " + all);
            int done = eligible.size() - todo.size();
            progress.start(done);
            for (DocumentPage page : todo) {
                token.throwIfCancelled();
                int number = page.pageNumber();
                List<String> notes;
                try {
                    byte[] png = renderer.renderPngAtDpi(file, number, READ_DPI, READ_MAX_PIXELS);
                    notes = ai.call(() -> figureDescriber.describe(png, number, token), token);
                } catch (UnavailableException e) {
                    LOG.warn("Figures of document {} were not described: the study assistant is not available", documentId, e);
                    return;
                } catch (IOException | LearnSphereException e) {
                    LOG.warn("Figures on page {} of document {} could not be described", number, documentId, e);
                    notes = List.of();
                }
                String joined = String.join("\n", notes.stream().map(String::strip).filter(n -> !n.isEmpty()).toList());
                repos.documents().setFigureNotes(documentId, number, joined);
                progress.update(++done);
            }
            progress.finish();
        }

        // ---- chunk, embed, index ----------------------------------------------------------------

        private List<Chunk> chunk(Path file) {
            List<Chunk> existing = repos.chunks().listByDocument(documentId);
            if (!existing.isEmpty()) {
                return existing;
            }
            report(DocumentStatus.INDEXING, 0, 0, "Getting your PDF ready for questions…", FIGURES_END);
            document = repos.documents().get(documentId);
            List<DocumentPage> usable = repos.documents().listUsablePages(documentId);
            Map<Integer, PageText> layout = textLayerStructure(file, usable);
            List<PageText> pages = new ArrayList<>();
            for (DocumentPage page : usable) {
                PageText text = page.source() == PageSource.AI_READ
                        ? PipelineText.fromMarkdown(page.pageNumber(), page.text())
                        : layout.getOrDefault(page.pageNumber(), PageText.ofPlainText(page.pageNumber(), page.text()));
                text = PipelineText.withFigures(text, page.figureNotes());
                if (!text.blocks().isEmpty()) {
                    pages.add(text);
                }
            }
            List<ChunkDraft> drafts;
            try {
                drafts = pages.isEmpty() ? List.of() : chunker.chunk(pages);
            } catch (RuntimeException e) {
                throw new StageFailure(DocumentError.PREPARE_FAILED, "Chunking failed", e);
            }
            long now = clock.millis();
            List<Chunk> chunks = new ArrayList<>();
            for (ChunkDraft draft : drafts) {
                if (draft.text().isBlank()) {
                    continue;
                }
                chunks.add(Chunk.create(documentId, courseId, document.moduleId(), chunks.size(), draft.pageStart(),
                        draft.pageEnd(), draft.sectionTitle(), draft.text().strip(), Math.max(0, draft.tokenEstimate()), now));
            }
            if (chunks.isEmpty()) {
                throw new StageFailure(DocumentError.NO_TEXT, "No passages", null);
            }
            repos.chunks().insertAll(chunks);
            context.progress(CHUNK_END, "Getting your PDF ready for questions…");
            return chunks;
        }

        /**
         * The text-layer pages among {@code usable} with their layout (block font sizes, so headings are found),
         * from this run's extraction or, when resuming, a fresh extraction. A page whose stored text differs from
         * the extracted text keeps its stored text.
         */
        private Map<Integer, PageText> textLayerStructure(Path file, List<DocumentPage> usable) {
            Map<Integer, String> wanted = new LinkedHashMap<>();
            for (DocumentPage page : usable) {
                if (page.source() == PageSource.TEXT_LAYER && !page.text().isBlank()) {
                    wanted.put(page.pageNumber(), page.text());
                }
            }
            if (!structured.keySet().containsAll(wanted.keySet())) {
                try {
                    extractor.extract(file, page -> {
                        if (wanted.containsKey(page.pageNumber())) {
                            structured.put(page.pageNumber(), page);
                        }
                    }, token);
                } catch (IOException e) {
                    LOG.warn("Page layout of document {} could not be read again; using plain text", documentId, e);
                }
            }
            Map<Integer, PageText> result = new HashMap<>();
            wanted.forEach((number, text) -> {
                PageText page = structured.get(number);
                if (page != null && page.text().equals(text)) {
                    result.put(number, page);
                }
            });
            return result;
        }

        private List<Chunk> embed(List<Chunk> chunks) {
            String model = embedder.modelName();
            List<Chunk> todo = chunks.stream()
                    .filter(chunk -> !chunk.hasEmbedding() || !model.equals(chunk.embeddingModel())
                            || chunk.embedding().length != index.dimensions())
                    .toList();
            if (todo.isEmpty()) {
                return chunks;
            }
            Progress progress = new Progress(DocumentStatus.INDEXING, chunks.size(), CHUNK_END, EMBED_END,
                    (done, all) -> "Getting your PDF ready for questions… " + done + " of " + all);
            int done = chunks.size() - todo.size();
            progress.start(done);
            for (int from = 0; from < todo.size(); from += EMBED_BATCH) {
                token.throwIfCancelled();
                List<Chunk> batch = todo.subList(from, Math.min(todo.size(), from + EMBED_BATCH));
                List<String> texts = batch.stream().map(Chunk::text).toList();
                List<float[]> vectors;
                try {
                    vectors = ai.call(() -> embedder.embed(texts, EmbedKind.DOCUMENT, token), token);
                } catch (LearnSphereException e) {
                    throw new StageFailure(DocumentError.PREPARE_FAILED, "Embedding failed", e);
                }
                if (vectors.size() != batch.size()) {
                    throw new StageFailure(DocumentError.PREPARE_FAILED,
                            "Expected " + batch.size() + " vectors, got " + vectors.size(), null);
                }
                Map<String, float[]> byId = new LinkedHashMap<>();
                for (int i = 0; i < batch.size(); i++) {
                    float[] vector = vectors.get(i);
                    if (vector == null || vector.length != index.dimensions()) {
                        throw new StageFailure(DocumentError.PREPARE_FAILED, "Vector has "
                                + (vector == null ? "no" : vector.length) + " dimensions, the index " + index.dimensions(), null);
                    }
                    byId.put(batch.get(i).id(), vector);
                }
                repos.chunks().updateEmbeddings(byId, model);
                done += batch.size();
                progress.update(done);
            }
            progress.finish();
            return repos.chunks().listByDocument(documentId);
        }

        private void index(List<Chunk> chunks) {
            token.throwIfCancelled();
            report(DocumentStatus.INDEXING, 0, 0, "Making your PDF searchable…", EMBED_END);
            document = repos.documents().get(documentId);
            List<IndexedChunk> indexed = chunks.stream()
                    .map(chunk -> new IndexedChunk(chunk.id(), documentId, courseId, chunk.moduleId(), document.title(),
                            chunk.pageStart(), chunk.pageEnd(), chunk.sectionTitle(), chunk.text(), chunk.embedding()))
                    .toList();
            try {
                index.deleteByDocument(documentId);
                for (int from = 0; from < indexed.size(); from += INDEX_BATCH) {
                    index.upsert(indexed.subList(from, Math.min(indexed.size(), from + INDEX_BATCH)));
                }
                index.commit();
            } catch (RuntimeException e) {
                throw new StageFailure(isDiskFull(e) ? DocumentError.DISK_FULL : DocumentError.PREPARE_FAILED,
                        "Indexing failed", e);
            }
        }

        private void ready() {
            repos.documents().markReady(documentId);
            events.publish(DocumentChanged.of(documentId, courseId, DocumentStatus.READY, 1, ChangeType.UPDATED));
            events.publish(new CourseChanged(courseId, ChangeType.UPDATED));
            context.progress(1, "Your PDF is ready");
            LOG.info("Document {} is ready", documentId);
        }

        // ---- reporting and failure ----------------------------------------------------------------

        private double coverage(int pageNumber) {
            PdfInspection.PageInfo info = pageInfo.get(pageNumber);
            return info == null ? 0 : info.imageCoverage();
        }

        /** Moves to {@code status} with a step counter, announces it and reports the job's overall progress. */
        private void report(DocumentStatus status, int done, int total, String message, double overall) {
            double fraction = total > 0 ? Math.min(1, done / (double) total) : 0;
            repos.documents().updateStatus(documentId, status, fraction,
                    total > 0 ? DocumentSummary.formatStep(done, total) : null);
            events.publish(new DocumentChanged(documentId, courseId, status, fraction, total > 0 ? done : 0, total, null,
                    ChangeType.UPDATED));
            context.progress(overall, message);
        }

        private void fail(DocumentError error, Throwable cause) {
            LOG.warn("Import of document {} failed: {}", documentId, error.code(), cause);
            repos.documents().markFailed(documentId, error, null);
            Document failed = repos.documents().find(documentId).orElse(document);
            events.publish(new DocumentChanged(documentId, courseId, DocumentStatus.FAILED, failed.progress(), 0, 0,
                    error.userMessage(), ChangeType.UPDATED));
            events.publish(new CourseChanged(courseId, ChangeType.UPDATED));
            throw new LearnSphereException(error.userMessage(),
                    "Import of document " + documentId + " failed (" + error.code() + ")", cause);
        }

        /** Step progress of one stage: stored counter, events and the job's line, at most ~100 times per stage. */
        private final class Progress {

            private final DocumentStatus status;
            private final int total;
            private final double jobFrom;
            private final double jobTo;
            private final StepLine line;
            private final int every;
            private int last = -1;

            Progress(DocumentStatus status, int total, double jobFrom, double jobTo, StepLine line) {
                this.status = status;
                this.total = Math.max(total, 1);
                this.jobFrom = jobFrom;
                this.jobTo = jobTo;
                this.line = line;
                this.every = Math.max(1, this.total / STEP_REPORTS);
            }

            void start(int done) {
                emit(done);
            }

            void update(int done) {
                if (done == total || done - last >= every) {
                    emit(done);
                }
            }

            void finish() {
                if (last != total) {
                    emit(total);
                }
            }

            private void emit(int done) {
                int clamped = Math.max(0, Math.min(done, total));
                last = clamped;
                report(status, clamped, total, line.text(clamped, total),
                        jobFrom + (jobTo - jobFrom) * clamped / total);
            }
        }
    }

    /** The user-facing progress line for {@code done} of {@code total} steps. */
    @FunctionalInterface
    private interface StepLine {
        String text(int done, int total);
    }

    /** A stage stopped for a reason the user can understand. */
    private static final class StageFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final transient DocumentError error;

        StageFailure(DocumentError error, String detail, Throwable cause) {
            super(detail, cause);
            this.error = error;
        }
    }

    private static DocumentError fileError(IOException e) {
        return isDiskFull(e) ? DocumentError.DISK_FULL : DocumentError.DAMAGED;
    }

    /** True when {@code error} (or a cause) says the disk or the database is full. */
    static boolean isDiskFull(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLiteException e && e.getResultCode() == SQLiteErrorCode.SQLITE_FULL) {
                return true;
            }
            String message = t.getMessage();
            if (t instanceof IOException && message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("no space left") || lower.contains("not enough space") || lower.contains("disk full")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.debug("Could not delete {}", file, e);
        }
    }
}
