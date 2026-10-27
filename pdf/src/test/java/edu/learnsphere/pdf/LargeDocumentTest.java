package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.learnsphere.core.spi.ChunkDraft;
import edu.learnsphere.core.spi.NormalizedRect;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.PdfInspection;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.pdf.testing.TestPdf;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A 300-page text book through every step, with timings printed to the test output: wall-clock time and the
 * CPU time of the calling thread (all of the work runs on it), which stays meaningful on a busy machine. The
 * book is written twice: in the standard PDF fonts, which a PDF reader supplies itself and which are the
 * cheapest to read, and in a TrueType font embedded as a subset with its own character map, as most real
 * documents are. The limits, on CPU time, are deliberately loose and only catch runaway slowness; the printed
 * numbers are the measurement. {@link RealPdfBenchmark} measures real files.
 */
class LargeDocumentTest {

    /** Extraction CPU time above which something has gone badly wrong, in milliseconds. */
    private static final long CPU_LIMIT_MS = 20_000;

    @TempDir
    Path dir;

    @Test
    void handlesAThreeHundredPageBook() throws Exception {
        Path pdf = book(TestPdf.builder()).write(dir.resolve("book.pdf"));
        Measured measured = measure(pdf, "standard fonts");

        long t0 = System.nanoTime();
        PageText middle = new PdfBoxTextExtractor().extractPage(pdf, 150);
        long pageRead = System.nanoTime();
        String passage = middle.blocks().getLast().text();
        List<NormalizedRect> rects = new PdfBoxPassageLocator().locate(pdf, 150, passage);
        long located = System.nanoTime();
        byte[] png = new PdfBoxPageRenderer().renderPngAtDpi(pdf, 150, 144);
        long rendered = System.nanoTime();
        System.out.printf(Locale.ROOT, "one page %d ms | locate %d ms | render 144 dpi %d ms%n",
                ms(t0, pageRead), ms(pageRead, located), ms(located, rendered));

        measured.check();
        assertTrue(measured.pages.stream().noneMatch(p -> p.text().contains("Course Reader")
                || p.text().contains("Foundations of Biology") && p.pageNumber() > 1));
        assertFalse(rects.isEmpty());
        assertTrue(png.length > 10_000);
    }

    @Test
    void handlesAThreeHundredPageBookInAnEmbeddedFont() throws Exception {
        Path pdf = book(TestPdf.builder().embeddedFonts()).write(dir.resolve("embedded.pdf"));
        Measured measured = measure(pdf, "embedded TrueType font");
        measured.check();
        assertTrue(measured.pages.stream().noneMatch(p -> p.text().contains("Course Reader")));
        String text = Lecture.flatText(measured.pages);
        for (long seed : new long[] {1, 100, 270}) {
            assertTrue(text.contains(TestPdf.sentences(seed, 48)), "paragraph " + seed + " reads back through the font's map");
        }
    }

    /** 30 chapters of 3 sections of 3 paragraphs of 48 sentences: a little over 300 pages. */
    private static TestPdf.Builder book(TestPdf.Builder book) {
        book.runningHeader("Foundations of Biology").runningFooter("Course Reader").pageNumbers().title("Foundations of Biology");
        long seed = 1;
        for (int chapter = 1; chapter <= 30; chapter++) {
            book.heading(chapter + " Chapter " + chapter);
            for (int section = 1; section <= 3; section++) {
                book.subheading(chapter + "." + section + " Topic " + section);
                for (int paragraph = 0; paragraph < 3; paragraph++) {
                    book.paragraph(TestPdf.sentences(seed++, 48));
                }
            }
        }
        return book;
    }

    private static Measured measure(Path pdf, String label) throws Exception {
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long t0 = System.nanoTime();
        long cpu0 = threads.getCurrentThreadCpuTime();
        PdfInspection inspection = new PdfBoxInspector().inspect(pdf);
        long inspected = System.nanoTime();
        long cpu1 = threads.getCurrentThreadCpuTime();
        List<PageText> pages = new PdfBoxTextExtractor().extractAll(pdf, CancellationToken.NONE);
        long extracted = System.nanoTime();
        long cpu2 = threads.getCurrentThreadCpuTime();
        List<ChunkDraft> chunks = new SectionChunker().chunk(pages);
        long chunked = System.nanoTime();
        long cpu3 = threads.getCurrentThreadCpuTime();

        int pageCount = inspection.pageCount();
        long chars = pages.stream().mapToLong(PageText::charCount).sum();
        System.out.printf(Locale.ROOT, "%s: %d pages, %.1f MB, %,d characters, %d passages%n", label, pageCount,
                Files.size(pdf) / 1e6, chars, chunks.size());
        System.out.printf(Locale.ROOT, "%s: wall: inspect %d ms | extract %d ms (%.1f ms/page) | chunk %d ms%n", label,
                ms(t0, inspected), ms(inspected, extracted), (extracted - inspected) / 1e6 / pageCount, ms(extracted, chunked));
        System.out.printf(Locale.ROOT, "%s: cpu:  inspect %d ms | extract %d ms (%.1f ms/page) | chunk %d ms%n", label,
                ms(cpu0, cpu1), ms(cpu1, cpu2), (cpu2 - cpu1) / 1e6 / pageCount, ms(cpu2, cpu3));
        return new Measured(inspection, pages, chunks, threads.isCurrentThreadCpuTimeSupported() ? ms(cpu1, cpu2) : -1);
    }

    private record Measured(PdfInspection inspection, List<PageText> pages, List<ChunkDraft> chunks, long extractCpuMs) {

        void check() {
            int pageCount = inspection.pageCount();
            assertTrue(pageCount >= 300, "pages: " + pageCount);
            assertEquals(pageCount, pages.size());
            assertTrue(inspection.pages().stream().allMatch(PdfInspection.PageInfo::hasTextLayer));
            assertEquals("30.3 Topic 3", chunks.getLast().sectionTitle());
            assertEquals(pageCount, chunks.getLast().pageEnd());
            // The title and "1 Chapter 1" have no text of their own: the first passage opens with all three headings.
            assertTrue(chunks.getFirst().text().startsWith("Foundations of Biology\n\n1 Chapter 1\n\n1.1 Topic 1\n\n"));
            assertEquals(List.of("1.1 Topic 1", "1.2 Topic 2", "1.3 Topic 3", "2.1 Topic 1"),
                    chunks.stream().map(ChunkDraft::sectionTitle).distinct().limit(4).toList());
            // CPU time, not wall-clock time: a machine busy with other work stretches the latter without limit.
            assertTrue(extractCpuMs < CPU_LIMIT_MS, "extraction used " + extractCpuMs + " ms of CPU time");
        }
    }

    static long ms(long fromNanos, long toNanos) {
        return (toNanos - fromNanos) / 1_000_000;
    }
}
