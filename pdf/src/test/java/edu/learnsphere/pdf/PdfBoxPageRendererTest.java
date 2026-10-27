package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.learnsphere.pdf.testing.TestPdf;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxPageRendererTest {

    @TempDir
    static Path dir;

    static Path lecture;

    private final PdfBoxPageRenderer renderer = new PdfBoxPageRenderer();

    @BeforeAll
    static void writeLecture() throws IOException {
        lecture = Lecture.write(dir.resolve("lecture.pdf"));
    }

    @Test
    void rendersAtTheRequestedScale() throws Exception {
        BufferedImage actual = decode(renderer.renderPng(lecture, 1, 1f));
        assertEquals(612, actual.getWidth());
        assertEquals(792, actual.getHeight());

        BufferedImage retina = decode(renderer.renderPngAtDpi(lecture, 1, 144));
        assertEquals(1224, retina.getWidth());
        assertEquals(1584, retina.getHeight());
    }

    @Test
    void drawsTextAndScannedPages() throws Exception {
        assertTrue(darkShare(decode(renderer.renderPng(lecture, 1, 1f))) > 0.01, "text page has ink");
        assertTrue(darkShare(decode(renderer.renderPng(lecture, Lecture.SCANNED_PAGE, 1f))) > 0.05, "scan has ink");
    }

    @Test
    void rendersRotatedPagesAsDisplayed() throws Exception {
        Path pdf = TestPdf.builder().rotatedPage(90).paragraph(TestPdf.sentences(3, 5)).write(dir.resolve("rotated.pdf"));
        BufferedImage image = decode(renderer.renderPng(pdf, 1, 0.5f));
        assertEquals(396, image.getWidth());
        assertEquals(306, image.getHeight());
    }

    @Test
    void scalesHugeRequestsDownToThePixelLimit() throws Exception {
        PdfBoxPageRenderer small = new PdfBoxPageRenderer(1, 250_000);
        BufferedImage image = decode(small.renderPng(lecture, 1, 10f));
        assertTrue((long) image.getWidth() * image.getHeight() <= 250_000 * 1.01, image.getWidth() + "x" + image.getHeight());
        assertTrue(image.getWidth() > 400, "uses most of the budget: " + image.getWidth());
        assertEquals(612.0 / 792.0, (double) image.getWidth() / image.getHeight(), 0.01);
    }

    @Test
    void producesTheSameImageFromManyThreads() throws Exception {
        byte[] expected = renderer.renderPng(lecture, 3, 0.75f);
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<byte[]>> renders = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                renders.add(pool.submit(() -> renderer.renderPng(lecture, 3, 0.75f)));
            }
            for (Future<byte[]> render : renders) {
                assertArrayEquals(expected, render.get());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rejectsBadRequests() throws Exception {
        for (float scale : new float[] {0f, -1f, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> renderer.renderPng(lecture, 1, scale), "scale " + scale);
        }
        assertEquals(PdfReadException.Reason.NO_SUCH_PAGE,
                assertThrows(PdfReadException.class, () -> renderer.renderPng(lecture, Lecture.PAGES + 1, 1f)).reason());
        assertEquals(PdfReadException.Reason.MISSING,
                assertThrows(PdfReadException.class, () -> renderer.renderPng(dir.resolve("gone.pdf"), 1, 1f)).reason());
        Path locked = TestPdf.encrypt(lecture, dir.resolve("locked.pdf"), "open-sesame");
        assertEquals(PdfReadException.Reason.ENCRYPTED,
                assertThrows(PdfReadException.class, () -> renderer.renderPng(locked, 1, 1f)).reason());
        assertThrows(IllegalArgumentException.class, () -> new PdfBoxPageRenderer(0, 1_000_000));
        assertThrows(IllegalArgumentException.class, () -> new PdfBoxPageRenderer(1, 10));
    }

    static BufferedImage decode(byte[] png) throws IOException {
        assertEquals((byte) 0x89, png[0]);
        assertEquals('P', png[1]);
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    static double darkShare(BufferedImage image) {
        return darkShare(image, 0, 0, image.getWidth(), image.getHeight());
    }

    /** Share of pixels in the region that are clearly darker than paper. */
    static double darkShare(BufferedImage image, int x0, int y0, int x1, int y1) {
        int dark = 0;
        int total = 0;
        for (int y = Math.max(0, y0); y < Math.min(image.getHeight(), y1); y++) {
            for (int x = Math.max(0, x0); x < Math.min(image.getWidth(), x1); x++) {
                int rgb = image.getRGB(x, y);
                int luma = ((rgb >> 16 & 0xff) * 3 + (rgb >> 8 & 0xff) * 6 + (rgb & 0xff)) / 10;
                if (luma < 192) {
                    dark++;
                }
                total++;
            }
        }
        return total == 0 ? 0 : (double) dark / total;
    }
}
