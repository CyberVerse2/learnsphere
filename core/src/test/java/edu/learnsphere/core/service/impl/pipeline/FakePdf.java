package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.spi.ChunkDraft;
import edu.learnsphere.core.spi.Chunker;
import edu.learnsphere.core.spi.PageRenderer;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.PdfInspection;
import edu.learnsphere.core.spi.PdfInspector;
import edu.learnsphere.core.spi.PdfTextExtractor;
import edu.learnsphere.core.spi.TextBlock;
import edu.learnsphere.core.util.CancellationToken;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * PDF stand-ins for pipeline tests: a small text format read by an inspector, text extractor and page renderer.
 *
 * <pre>
 * %FAKEPDF [encrypted] [pages=N]
 * %%page image=0.0
 * # Heading
 * A paragraph.
 * %%page image=0.9
 * </pre>
 *
 * Lines starting with {@code "# "} become bold heading blocks; other lines are joined into paragraphs split at
 * blank lines. {@code image} is the share of the page covered by images. A file without the header is damaged.
 */
final class FakePdf implements PdfInspector, PdfTextExtractor, PageRenderer {

    static final String HEADER = "%FAKEPDF";
    static final String PAGE = "%%page";
    static final float BODY = 10f;
    static final float HEADING = 16f;

    final AtomicInteger inspections = new AtomicInteger();
    final AtomicInteger fullExtractions = new AtomicInteger();
    final AtomicInteger pageExtractions = new AtomicInteger();
    final AtomicInteger renders = new AtomicInteger();

    /** A page of a fake PDF. */
    record Page(String text, double imageCoverage) {
    }

    /** Builds a fake PDF file. */
    static final class Builder {

        private final List<Page> pages = new ArrayList<>();
        private boolean encrypted;
        private int declaredPages;

        Builder page(String text) {
            return page(text, 0);
        }

        Builder page(String text, double imageCoverage) {
            pages.add(new Page(text, imageCoverage));
            return this;
        }

        /** A page that is a picture of text: no text layer, covered by an image. */
        Builder scannedPage() {
            return page("", 0.95);
        }

        Builder encrypted() {
            encrypted = true;
            return this;
        }

        /** Claims {@code count} pages in the header (for the page limit). */
        Builder declaredPages(int count) {
            declaredPages = count;
            return this;
        }

        byte[] bytes() {
            StringBuilder out = new StringBuilder(HEADER);
            if (encrypted) {
                out.append(" encrypted");
            }
            if (declaredPages > 0) {
                out.append(" pages=").append(declaredPages);
            }
            out.append('\n');
            for (Page page : pages) {
                out.append(PAGE).append(" image=").append(page.imageCoverage()).append('\n');
                if (!page.text().isEmpty()) {
                    out.append(page.text().strip()).append('\n');
                }
            }
            return out.toString().getBytes(StandardCharsets.UTF_8);
        }

        /** Writes the file and returns it. */
        Path write(Path file) {
            try {
                Files.createDirectories(file.getParent());
                return Files.write(file, bytes());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    static Builder document() {
        return new Builder();
    }

    /** Lowercase hex SHA-256 of {@code bytes}. */
    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- reading -----------------------------------------------------------------------------------

    private record Parsed(boolean valid, boolean encrypted, int declaredPages, List<Page> pages) {
    }

    private static Parsed parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || !lines.getFirst().startsWith(HEADER)) {
            return new Parsed(false, false, 0, List.of());
        }
        String header = lines.getFirst();
        boolean encrypted = header.contains("encrypted");
        int declared = 0;
        for (String part : header.split(" ")) {
            if (part.startsWith("pages=")) {
                declared = Integer.parseInt(part.substring("pages=".length()));
            }
        }
        List<Page> pages = new ArrayList<>();
        double coverage = -1;
        StringBuilder text = new StringBuilder();
        for (String line : lines.subList(1, lines.size())) {
            if (line.startsWith(PAGE)) {
                if (coverage >= 0) {
                    pages.add(new Page(text.toString().strip(), coverage));
                }
                coverage = Double.parseDouble(line.substring(line.indexOf("image=") + "image=".length()));
                text.setLength(0);
            } else {
                text.append(line).append('\n');
            }
        }
        if (coverage >= 0) {
            pages.add(new Page(text.toString().strip(), coverage));
        }
        return new Parsed(true, encrypted, declared, pages);
    }

    private static PageText pageText(int number, String text) {
        List<TextBlock> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        int lines = 0;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("# ")) {
                if (paragraph.length() > 0) {
                    blocks.add(new TextBlock(paragraph.toString(), lines, BODY, false, null));
                    paragraph.setLength(0);
                    lines = 0;
                }
                if (line.startsWith("# ")) {
                    blocks.add(new TextBlock(line.substring(2).strip(), 1, HEADING, true, null));
                }
                continue;
            }
            if (paragraph.length() > 0) {
                paragraph.append(' ');
            }
            paragraph.append(line);
            lines++;
        }
        if (paragraph.length() > 0) {
            blocks.add(new TextBlock(paragraph.toString(), lines, BODY, false, null));
        }
        return new PageText(number, BODY, blocks);
    }

    @Override
    public PdfInspection inspect(Path pdf) throws IOException {
        inspections.incrementAndGet();
        Parsed parsed = parse(pdf);
        if (!parsed.valid()) {
            return PdfInspection.damagedFile();
        }
        if (parsed.encrypted()) {
            return PdfInspection.encryptedFile();
        }
        List<PdfInspection.PageInfo> info = new ArrayList<>();
        for (int i = 0; i < parsed.pages().size(); i++) {
            Page page = parsed.pages().get(i);
            int chars = pageText(i + 1, page.text()).charCount();
            info.add(new PdfInspection.PageInfo(i + 1, chars > 0, chars, page.imageCoverage(), 612, 792));
        }
        int count = parsed.declaredPages() > 0 ? parsed.declaredPages() : parsed.pages().size();
        return new PdfInspection(count, false, false, info);
    }

    @Override
    public void extract(Path pdf, Consumer<PageText> sink, CancellationToken token) throws IOException {
        fullExtractions.incrementAndGet();
        List<Page> pages = parse(pdf).pages();
        for (int i = 0; i < pages.size(); i++) {
            token.throwIfCancelled();
            sink.accept(pageText(i + 1, pages.get(i).text()));
        }
    }

    @Override
    public PageText extractPage(Path pdf, int pageNumber) throws IOException {
        pageExtractions.incrementAndGet();
        return pageText(pageNumber, parse(pdf).pages().get(pageNumber - 1).text());
    }

    @Override
    public byte[] renderPng(Path pdf, int pageNumber, float scale) {
        renders.incrementAndGet();
        return ("PNG page " + pageNumber + " at " + scale).getBytes(StandardCharsets.UTF_8);
    }

    /** One passage per page: the first heading is its section title, its text is the page's other blocks. */
    static final class PageChunker implements Chunker {

        @Override
        public List<ChunkDraft> chunk(List<PageText> pages, Options options) {
            List<ChunkDraft> drafts = new ArrayList<>();
            for (PageText page : pages) {
                String section = page.blocks().stream().filter(TextBlock::bold).map(TextBlock::text).findFirst()
                        .orElse(null);
                List<String> body = page.blocks().stream().filter(block -> !block.bold()).map(TextBlock::text).toList();
                String text = body.isEmpty() ? page.text() : String.join("\n\n", body);
                drafts.add(new ChunkDraft(drafts.size(), page.pageNumber(), page.pageNumber(), section, text,
                        text.split("\\s+").length));
            }
            return drafts;
        }
    }
}
