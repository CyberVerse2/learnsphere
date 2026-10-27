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
 * time limit is deliberately loose so a busy machine does not fail the build; the printed numbers are the
 * measurement.
 */
class LargeDocumentTest {

    @TempDir
    Path dir;

    @Test
    void handlesAThreeHundredPageBook() throws Exception {
        long t0 = System.nanoTime();
        TestPdf.Builder book = TestPdf.builder().runningHeader("Foundations of Biology").runningFooter("Course Reader")
                .pageNumbers().title("Foundations of Biology");
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
        Path pdf = book.write(dir.resolve("book.pdf"));
        long written = System.nanoTime();

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
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
        PageText middle = new PdfBoxTextExtractor().extractPage(pdf, 150);
        long pageRead = System.nanoTime();
        String passage = middle.blocks().getLast().text();
        List<NormalizedRect> rects = new PdfBoxPassageLocator().locate(pdf, 150, passage);
        long located = System.nanoTime();
        byte[] png = new PdfBoxPageRenderer().renderPngAtDpi(pdf, 150, 144);
        long rendered = System.nanoTime();

        int pageCount = inspection.pageCount();
        long chars = pages.stream().mapToLong(PageText::charCount).sum();
        System.out.printf(Locale.ROOT, "%d pages, %.1f MB, %,d characters, %d passages%n", pageCount,
                Files.size(pdf) / 1e6, chars, chunks.size());
        System.out.printf(Locale.ROOT, "write %d ms | inspect %d ms | extract %d ms (%.1f ms/page) | chunk %d ms"
                        + " | one page %d ms | locate %d ms | render 144 dpi %d ms%n",
                ms(t0, written), ms(written, inspected), ms(inspected, extracted),
                (extracted - inspected) / 1e6 / pageCount, ms(extracted, chunked), ms(chunked, pageRead),
                ms(pageRead, located), ms(located, rendered));
        System.out.printf(Locale.ROOT, "cpu: inspect %d ms | extract %d ms (%.1f ms/page) | chunk %d ms%n",
                ms(cpu0, cpu1), ms(cpu1, cpu2), (cpu2 - cpu1) / 1e6 / pageCount, ms(cpu2, cpu3));

        assertTrue(pageCount >= 300, "pages: " + pageCount);
        assertEquals(pageCount, pages.size());
        assertTrue(inspection.pages().stream().allMatch(PdfInspection.PageInfo::hasTextLayer));
        assertTrue(pages.stream().noneMatch(p -> p.text().contains("Course Reader") || p.text().contains("Foundations of Biology")
                && p.pageNumber() > 1));
        assertEquals("30.3 Topic 3", chunks.getLast().sectionTitle());
        assertEquals(pageCount, chunks.getLast().pageEnd());
        assertFalse(rects.isEmpty());
        assertTrue(png.length > 10_000);
        assertTrue(ms(inspected, extracted) < 30_000, "extraction took " + ms(inspected, extracted) + " ms");
    }

    private static long ms(long from, long to) {
        return (to - from) / 1_000_000;
    }
}
