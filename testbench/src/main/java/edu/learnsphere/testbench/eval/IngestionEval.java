package edu.learnsphere.testbench.eval;

import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.spi.ChunkDraft;
import edu.learnsphere.testbench.material.Material;
import edu.learnsphere.testbench.metrics.TextAccuracy;
import edu.learnsphere.testbench.probe.Recorder;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Imports the material through the app's document service and measures the import: wall time per page, passages
 * per page, the work done by each AI step, and how accurately the scanned pages were transcribed.
 *
 * <p>The scanned copy goes through the app's review flow: the import stops with its pages waiting for review,
 * the transcriptions are scored against the authored text of the source pages, then every page is approved and
 * the import continues to the end.
 */
public final class IngestionEval {

    private static final Logger LOG = LoggerFactory.getLogger(IngestionEval.class);
    private static final Duration IMPORT_TIMEOUT = Duration.ofMinutes(20);

    /**
     * One imported document.
     *
     * @param wallMs        from handing the file to the app until it was ready to answer questions (for the scanned
     *                      copy: excluding the time it waited for review)
     * @param pagesRead     pages transcribed by the study assistant (no text layer)
     * @param figurePages   pages whose figures were described
     * @param embedMs       time spent embedding the document's passages
     */
    public record DocumentResult(String key, String title, int pages, long wallMs, int chunks, double meanChunkWords,
                                 int maxChunkWords, int pagesRead, long readMs, int figurePages, int figureNotes,
                                 long figureMs, int embedBatches, long embedMs) {

        /** Seconds of import per page. */
        public double secondsPerPage() {
            return pages == 0 ? 0 : wallMs / 1000.0 / pages;
        }

        /** Passages per page. */
        public double chunksPerPage() {
            return pages == 0 ? 0 : chunks / (double) pages;
        }
    }

    /** One transcribed scanned page, scored against the authored text of the page it shows. */
    public record ScannedPage(int page, int sourcePage, double characterAccuracy, double wordAccuracy, long readMs,
                              int illegibleSpans, int referenceChars, String transcription) {
    }

    /** Figure notes written for one page. */
    public record FigureNotes(String document, int page, List<String> notes) {
    }

    /** Everything the ingestion evaluation measured. */
    public record Result(List<DocumentResult> documents, List<ScannedPage> scanned, int pagesWaitingForReview,
                         List<FigureNotes> figures) {

        public Result {
            documents = List.copyOf(documents);
            scanned = List.copyOf(scanned);
            figures = List.copyOf(figures);
        }

        /** Mean character accuracy over the scanned pages, weighted by their length. */
        public double scannedCharacterAccuracy() {
            long chars = scanned.stream().mapToLong(ScannedPage::referenceChars).sum();
            return chars == 0 ? 0
                    : scanned.stream().mapToDouble(p -> p.characterAccuracy() * p.referenceChars()).sum() / chars;
        }

        /** Mean word accuracy over the scanned pages, weighted by their length. */
        public double scannedWordAccuracy() {
            long chars = scanned.stream().mapToLong(ScannedPage::referenceChars).sum();
            return chars == 0 ? 0 : scanned.stream().mapToDouble(p -> p.wordAccuracy() * p.referenceChars()).sum() / chars;
        }

        /** The text-layer documents (the chapters). */
        public List<DocumentResult> textDocuments() {
            return documents.stream().filter(d -> !d.key().equals(Material.SCANNED)).toList();
        }

        /** Import seconds per page over the text-layer documents. */
        public double textSecondsPerPage() {
            int pages = textDocuments().stream().mapToInt(DocumentResult::pages).sum();
            long ms = textDocuments().stream().mapToLong(DocumentResult::wallMs).sum();
            return pages == 0 ? 0 : ms / 1000.0 / pages;
        }

        /** Passages per page over the text-layer documents. */
        public double textChunksPerPage() {
            int pages = textDocuments().stream().mapToInt(DocumentResult::pages).sum();
            int chunks = textDocuments().stream().mapToInt(DocumentResult::chunks).sum();
            return pages == 0 ? 0 : chunks / (double) pages;
        }

        /** The scanned copy, if it was imported. */
        public java.util.Optional<DocumentResult> scannedDocument() {
            return documents.stream().filter(d -> d.key().equals(Material.SCANNED)).findFirst();
        }
    }

    private IngestionEval() {
    }

    /** Creates the courses, imports the material and measures it. */
    public static Result run(Session session) throws InterruptedException {
        Workspace workspace = session.workspace();
        workspace.settings().set(SettingKeys.DESCRIBE_FIGURES, true);
        String main = workspace.courses().create(edu.learnsphere.core.service.CourseDraft.titled(
                "Operating Systems and Research Methods")).id();
        String scan = workspace.courses().create(edu.learnsphere.core.service.CourseDraft.titled(
                "Research Methods (scanned pages)")).id();
        session.courses(main, scan);
        session.module(Material.OS, workspace.courses().addModule(main, "Processes, threads and memory").id());
        session.module(Material.RESEARCH_METHODS, workspace.courses().addModule(main, "Research methods").id());
        session.module(Material.SCANNED, workspace.courses().addModule(scan, "Scanned chapter pages").id());

        Material material = session.material();
        List<DocumentResult> documents = new ArrayList<>();
        documents.add(importText(session, Material.OS, material.files().get(Material.OS)));
        documents.add(importText(session, Material.RESEARCH_METHODS, material.files().get(Material.RESEARCH_METHODS)));

        String phase = "ingest:" + Material.SCANNED;
        session.phase(phase);
        LOG.info("Importing the scanned copy ({} pages)", material.scannedPages().size());
        long start = System.nanoTime();
        DocumentSummary summary = workspace.documents().importPdf(scan, session.moduleId(Material.SCANNED), material.scanned());
        session.document(Material.SCANNED, summary.id());
        summary = session.awaitDocument(summary.id(),
                s -> s.status() == DocumentStatus.NEEDS_REVIEW || s.status() == DocumentStatus.READY, IMPORT_TIMEOUT);
        long untilReview = elapsedMs(start);
        List<DocumentPage> waiting = workspace.documents().pagesNeedingReview(summary.id());
        List<ScannedPage> scanned = scoreTranscriptions(session, summary.id(), phase);
        long approved = System.nanoTime();
        workspace.documents().approvePages(summary.id(), waiting.stream().map(DocumentPage::pageNumber).toList());
        summary = session.awaitDocument(summary.id(), DocumentSummary::isReady, IMPORT_TIMEOUT);
        long wall = untilReview + elapsedMs(approved);
        documents.add(describe(session, Material.SCANNED, summary, wall, phase));
        LOG.info("Scanned copy ready: character accuracy {}", String.format("%.3f",
                new Result(documents, scanned, waiting.size(), List.of()).scannedCharacterAccuracy()));

        List<FigureNotes> figures = session.recorder().figureCalls().stream()
                .filter(call -> !call.notes().isEmpty())
                .map(call -> new FigureNotes(call.label().replace("ingest:", ""), call.page(), call.notes()))
                .toList();
        return new Result(documents, scanned, waiting.size(), figures);
    }

    private static DocumentResult importText(Session session, String key, Path file) throws InterruptedException {
        String phase = "ingest:" + key;
        session.phase(phase);
        LOG.info("Importing {}", file.getFileName());
        long start = System.nanoTime();
        DocumentSummary summary = session.workspace().documents().importPdf(session.mainCourseId(), session.moduleId(key), file);
        session.document(key, summary.id());
        summary = session.awaitDocument(summary.id(), DocumentSummary::isReady, IMPORT_TIMEOUT);
        long wall = elapsedMs(start);
        DocumentResult result = describe(session, key, summary, wall, phase);
        LOG.info("{} ready: {} pages, {} passages, {} s", summary.title(), result.pages(), result.chunks(),
                String.format("%.1f", wall / 1000.0));
        return result;
    }

    private static DocumentResult describe(Session session, String key, DocumentSummary summary, long wallMs, String phase) {
        Recorder recorder = session.recorder();
        List<ChunkDraft> chunks = recorder.chunkCalls().stream().filter(c -> c.label().equals(phase))
                .flatMap(c -> c.chunks().stream()).toList();
        List<Integer> words = chunks.stream().map(c -> c.text().strip().split("\\s+").length).toList();
        List<Recorder.PageRead> reads = recorder.pageReads().stream().filter(r -> r.label().equals(phase)).toList();
        List<Recorder.FigureCall> figures = recorder.figureCalls().stream().filter(f -> f.label().equals(phase)).toList();
        List<Recorder.EmbedCall> embeds = recorder.embedCalls().stream().filter(e -> e.label().equals(phase)).toList();
        return new DocumentResult(key, summary.title(), summary.pageCount(), wallMs, chunks.size(),
                words.stream().mapToInt(Integer::intValue).average().orElse(0),
                words.stream().mapToInt(Integer::intValue).max().orElse(0),
                reads.size(), reads.stream().mapToLong(Recorder.PageRead::millis).sum(),
                (int) figures.stream().filter(f -> f.error() == null).count(),
                figures.stream().mapToInt(f -> f.notes().size()).sum(),
                figures.stream().mapToLong(Recorder.FigureCall::millis).sum(),
                embeds.size(), embeds.stream().mapToLong(Recorder.EmbedCall::millis).sum());
    }

    private static List<ScannedPage> scoreTranscriptions(Session session, String documentId, String phase) {
        Material material = session.material();
        List<Recorder.PageRead> reads = session.recorder().pageReads().stream().filter(r -> r.label().equals(phase)).toList();
        return IntStream.rangeClosed(1, material.scannedPages().size()).mapToObj(page -> {
            DocumentPage stored = session.workspace().documents().page(documentId, page);
            String reference = material.scannedReference(page);
            Recorder.PageRead read = reads.stream().filter(r -> r.page() == page).reduce((a, b) -> b).orElse(null);
            String text = stored.text();
            return new ScannedPage(page, material.scannedPages().get(page - 1),
                    TextAccuracy.characterAccuracy(reference, text), TextAccuracy.wordAccuracy(reference, text),
                    read == null ? 0 : read.millis(),
                    read == null || read.result() == null ? 0 : read.result().illegibleSpans().size(),
                    TextAccuracy.normalize(reference).length(), text);
        }).toList();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
