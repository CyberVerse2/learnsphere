package edu.learnsphere.app.dev;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.PageSource;
import edu.learnsphere.core.service.DocumentService;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.RenderedPage;
import edu.learnsphere.core.spi.NormalizedRect;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;

/**
 * {@link DocumentService} over the {@link DemoStore}. Imports run as {@code INGEST_DOCUMENT} jobs that walk
 * through the real import steps ({@link DemoJobs}); pages are drawn as simple printed pages carrying the
 * passages the course cites, so the source viewer and highlights have something real to show.
 */
final class DemoDocuments implements DocumentService {

    /** Page size in points (US Letter), scaled by the render scale. */
    static final int PAGE_WIDTH = 612;
    static final int PAGE_HEIGHT = 792;
    private static final int MARGIN = 72;
    private static final int LINE_HEIGHT = 17;
    private static final int CHARS_PER_LINE = 74;
    private static final int BODY_TOP = 150;
    private static final UserMessage NOT_A_PDF = UserMessage.of("Choose a PDF file to import.");

    private final DemoStore store;
    private final JobQueue jobs;

    DemoDocuments(DemoStore store, JobQueue jobs) {
        this.store = store;
        this.jobs = jobs;
    }

    @Override
    public DocumentSummary importPdf(String courseId, String moduleId, Path pdf) {
        if (pdf == null || !Files.isRegularFile(pdf)) {
            throw new InvalidInputException(NOT_A_PDF, "Not a file: " + pdf);
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(pdf);
        } catch (IOException e) {
            throw new InvalidInputException(DocumentError.MISSING_FILE.userMessage(), "Cannot read " + pdf);
        }
        Document document;
        synchronized (store) {
            store.course(courseId);
            long now = store.now();
            String name = pdf.getFileName().toString();
            String title = name.replaceFirst("(?i)\\.pdf$", "").replace('_', ' ').replace('-', ' ').strip();
            document = new Document(DemoStore.newId(), courseId, moduleId, title, name, sha256(bytes), bytes.length,
                    countPages(bytes), DocumentStatus.QUEUED, 0, null, null, now, now);
            store.documents.put(document.id(), document);
        }
        publish(document, ChangeType.CREATED);
        jobs.enqueue(new JobSpec(JobKind.INGEST_DOCUMENT, document.id(), "{}", 10, true));
        synchronized (store) {
            return store.documentSummary(store.document(document.id()));
        }
    }

    /** Pages in a PDF, counted from its page objects (good enough for the demo). */
    static int countPages(byte[] bytes) {
        String text = new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
        int count = 0;
        int at = 0;
        while ((at = text.indexOf("/Type", at)) >= 0) {
            int next = at + 5;
            while (next < text.length() && text.charAt(next) == ' ') {
                next++;
            }
            if (text.startsWith("/Page", next) && !text.startsWith("/Pages", next)) {
                count++;
            }
            at = next;
        }
        return count > 0 ? count : 12;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public List<DocumentSummary> list(String courseId) {
        synchronized (store) {
            store.course(courseId);
            return store.documentsOf(courseId).stream().map(store::documentSummary).toList();
        }
    }

    @Override
    public DocumentSummary get(String documentId) {
        synchronized (store) {
            return store.documentSummary(store.document(documentId));
        }
    }

    @Override
    public void rename(String documentId, String title) {
        if (title == null || title.isBlank()) {
            throw new InvalidInputException(UserMessage.of("Give the PDF a name."), "blank title");
        }
        update(documentId, d -> new Document(d.id(), d.courseId(), d.moduleId(), title.strip(), d.fileName(),
                d.fileSha256(), d.byteSize(), d.pageCount(), d.status(), d.progress(), d.statusDetail(), d.errorCode(),
                d.createdAt(), store.now()));
    }

    @Override
    public void moveToModule(String documentId, String moduleId) {
        update(documentId, d -> new Document(d.id(), d.courseId(), moduleId, d.title(), d.fileName(), d.fileSha256(),
                d.byteSize(), d.pageCount(), d.status(), d.progress(), d.statusDetail(), d.errorCode(), d.createdAt(),
                store.now()));
    }

    @Override
    public void remove(String documentId) {
        Document document;
        synchronized (store) {
            document = store.document(documentId);
            store.documents.remove(documentId);
            store.pages.remove(documentId);
        }
        publish(document, ChangeType.DELETED);
    }

    @Override
    public void retry(String documentId) {
        update(documentId, d -> new Document(d.id(), d.courseId(), d.moduleId(), d.title(), d.fileName(),
                d.fileSha256(), d.byteSize(), Math.max(d.pageCount(), 12), DocumentStatus.QUEUED, 0, null, null,
                d.createdAt(), store.now()));
        jobs.enqueue(new JobSpec(JobKind.INGEST_DOCUMENT, documentId, "{}", 10, true));
    }

    @Override
    public List<DocumentPage> pagesNeedingReview(String documentId) {
        synchronized (store) {
            store.document(documentId);
            return store.pages.getOrDefault(documentId, Map.of()).values().stream()
                    .filter(p -> p.source() == PageSource.AI_READ && !p.reviewed())
                    .sorted(java.util.Comparator.comparingInt(DocumentPage::pageNumber)).toList();
        }
    }

    @Override
    public DocumentPage page(String documentId, int pageNumber) {
        synchronized (store) {
            Document document = store.document(documentId);
            requirePage(document, pageNumber);
            DocumentPage stored = store.pages.getOrDefault(documentId, Map.of()).get(pageNumber);
            if (stored != null) {
                return stored;
            }
            return new DocumentPage(documentId, pageNumber, String.join("\n\n", pageText(document, pageNumber)),
                    PageSource.TEXT_LAYER, true, null, false, document.createdAt(), document.updatedAt());
        }
    }

    private static void requirePage(Document document, int pageNumber) {
        if (pageNumber < 1 || pageNumber > Math.max(1, document.pageCount())) {
            throw new NotFoundException(UserMessage.of("This page isn't in the PDF."), "page " + pageNumber);
        }
    }

    @Override
    public void editPageText(String documentId, int pageNumber, String text) {
        synchronized (store) {
            DocumentPage page = page(documentId, pageNumber);
            store.pages.computeIfAbsent(documentId, k -> new HashMap<>()).put(pageNumber,
                    new DocumentPage(documentId, pageNumber, text, page.source(), page.reviewed(), page.figureNotes(),
                            page.hasImages(), page.createdAt(), store.now()));
        }
    }

    @Override
    public void approvePages(String documentId, Collection<Integer> pageNumbers) {
        Document document;
        synchronized (store) {
            document = store.document(documentId);
            Map<Integer, DocumentPage> pages = store.pages.computeIfAbsent(documentId, k -> new HashMap<>());
            for (int number : pageNumbers) {
                DocumentPage p = pages.get(number);
                if (p != null) {
                    pages.put(number, new DocumentPage(p.documentId(), p.pageNumber(), p.text(), p.source(), true,
                            p.figureNotes(), p.hasImages(), p.createdAt(), store.now()));
                }
            }
            boolean remaining = pages.values().stream().anyMatch(p -> p.source() == PageSource.AI_READ && !p.reviewed());
            if (!remaining && document.status() == DocumentStatus.NEEDS_REVIEW) {
                Document d = document;
                document = new Document(d.id(), d.courseId(), d.moduleId(), d.title(), d.fileName(), d.fileSha256(),
                        d.byteSize(), d.pageCount(), DocumentStatus.READY, 1, null, null, d.createdAt(), store.now());
                store.documents.put(documentId, document);
            }
        }
        publish(document, ChangeType.UPDATED);
    }

    @Override
    public RenderedPage renderPage(String documentId, int pageNumber, double scale) {
        Document document;
        List<String> paragraphs;
        boolean scanned;
        synchronized (store) {
            document = store.document(documentId);
            requirePage(document, pageNumber);
            DocumentPage stored = store.pages.getOrDefault(documentId, Map.of()).get(pageNumber);
            scanned = stored != null && stored.source() == PageSource.AI_READ;
            paragraphs = stored != null ? List.of(stored.text().split("\n+")) : pageText(document, pageNumber);
        }
        double s = Math.max(0.25, Math.min(4, scale));
        int width = (int) Math.round(PAGE_WIDTH * s);
        int height = (int) Math.round(PAGE_HEIGHT * s);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.scale(s, s);
            g.setColor(scanned ? new Color(0xF4, 0xF0, 0xE6) : Color.WHITE);
            g.fillRect(0, 0, PAGE_WIDTH, PAGE_HEIGHT);
            g.setColor(new Color(0x8A, 0x84, 0x7A));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
            g.drawString(document.title(), MARGIN, 44);
            g.drawString(Integer.toString(pageNumber), PAGE_WIDTH - MARGIN - 8, PAGE_HEIGHT - 40);
            g.setColor(new Color(0x22, 0x20, 0x1C));
            g.setFont(new Font(Font.SERIF, Font.BOLD, 17));
            g.drawString(heading(document, pageNumber), MARGIN, 110);
            g.setFont(new Font(Font.SERIF, Font.PLAIN, 11));
            int y = BODY_TOP;
            for (String paragraph : paragraphs) {
                for (String line : wrap(paragraph)) {
                    g.drawString(line, MARGIN, y);
                    y += LINE_HEIGHT;
                }
                y += LINE_HEIGHT;
            }
            g.setColor(scanned ? new Color(0xD8, 0xD2, 0xC4) : new Color(0xE6, 0xE2, 0xDA));
            while (y < PAGE_HEIGHT - 110) {
                int lineWidth = (PAGE_WIDTH - 2 * MARGIN) - ((y * 37) % 90);
                g.fillRoundRect(MARGIN, y - 8, lineWidth, 7, 4, 4);
                y += LINE_HEIGHT;
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new RenderedPage(documentId, pageNumber, out.toByteArray(), width, height);
    }

    @Override
    public List<NormalizedRect> locatePassage(String documentId, int pageNumber, String passage) {
        synchronized (store) {
            Document document = store.document(documentId);
            requirePage(document, pageNumber);
            DocumentPage stored = store.pages.getOrDefault(documentId, Map.of()).get(pageNumber);
            List<String> paragraphs = stored != null ? List.of(stored.text().split("\n+")) : pageText(document, pageNumber);
            String wanted = passage == null ? "" : passage.strip();
            int y = BODY_TOP;
            for (String paragraph : paragraphs) {
                List<String> lines = wrap(paragraph);
                if (!wanted.isEmpty() && (paragraph.contains(wanted) || wanted.contains(paragraph.strip()))) {
                    double top = (y - 13) / (double) PAGE_HEIGHT;
                    double height = (lines.size() * LINE_HEIGHT + 4) / (double) PAGE_HEIGHT;
                    return List.of(new NormalizedRect((MARGIN - 6) / (double) PAGE_WIDTH, top,
                            (PAGE_WIDTH - 2 * MARGIN + 12) / (double) PAGE_WIDTH, height));
                }
                y += (lines.size() + 1) * LINE_HEIGHT;
            }
            return List.of();
        }
    }

    /** The paragraphs printed on a page: the passages on it, or a line naming the page. Holds the store. */
    private List<String> pageText(Document document, int pageNumber) {
        List<String> texts = new ArrayList<>();
        for (DemoStore.Passage passage : store.passages.values()) {
            if (passage.documentId().equals(document.id()) && passage.page() == pageNumber) {
                texts.add(passage.text());
            }
        }
        if (texts.isEmpty()) {
            texts.add("This page continues the discussion from the previous pages of " + document.title() + ".");
        }
        return texts;
    }

    private String heading(Document document, int pageNumber) {
        Optional<DemoStore.Passage> passage = store.passages.values().stream()
                .filter(p -> p.documentId().equals(document.id()) && p.page() == pageNumber).findFirst();
        return passage.map(DemoStore.Passage::section).orElse(document.title());
    }

    static List<String> wrap(String paragraph) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : paragraph.strip().split("\\s+")) {
            if (line.length() > 0 && line.length() + word.length() + 1 > CHARS_PER_LINE) {
                lines.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    private void update(String documentId, java.util.function.UnaryOperator<Document> change) {
        Document next;
        synchronized (store) {
            next = change.apply(store.document(documentId));
            store.documents.put(documentId, next);
        }
        publish(next, ChangeType.UPDATED);
    }

    /** Publishes the document's state; callers outside the store's lock. */
    void publish(Document document, ChangeType type) {
        DocumentSummary summary;
        synchronized (store) {
            summary = store.documents.containsKey(document.id()) ? store.documentSummary(store.documents.get(document.id()))
                    : store.documentSummary(document);
        }
        store.events.publish(new DocumentChanged(document.id(), document.courseId(), summary.status(), summary.progress(),
                summary.stepDone(), summary.stepTotal(), summary.error(), type));
    }
}
