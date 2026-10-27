package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.learnsphere.core.spi.PdfInspection;
import edu.learnsphere.core.spi.PdfInspection.PageInfo;
import edu.learnsphere.pdf.testing.TestPdf;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxInspectorTest {

    @TempDir
    Path dir;

    private final PdfBoxInspector inspector = new PdfBoxInspector();

    @Test
    void describesEveryPage() throws Exception {
        PdfInspection inspection = inspector.inspect(Lecture.write(dir.resolve("lecture.pdf")));

        assertEquals(Lecture.PAGES, inspection.pageCount());
        assertFalse(inspection.encrypted());
        assertFalse(inspection.damaged());
        assertTrue(inspection.isReadable());
        assertEquals(Lecture.PAGES, inspection.pages().size());
        for (PageInfo page : inspection.pages()) {
            assertEquals(612f, page.widthPt(), 0.01f);
            assertEquals(792f, page.heightPt(), 0.01f);
            if (page.pageNumber() == Lecture.SCANNED_PAGE) {
                assertFalse(page.hasTextLayer());
                assertEquals(0, page.textChars());
                assertEquals(1.0, page.imageCoverage(), 0.01);
                assertTrue(page.looksScanned(20));
            } else {
                assertTrue(page.hasTextLayer());
                assertTrue(page.textChars() > 500, "page " + page.pageNumber() + ": " + page.textChars());
                assertEquals(0.0, page.imageCoverage(), 0.001);
                assertFalse(page.looksScanned(20));
            }
        }
    }

    @Test
    void countsTextCharactersWithoutSpaces() throws Exception {
        Path pdf = TestPdf.builder().paragraph("Two words. And three more.").write(dir.resolve("short.pdf"));
        assertEquals("Twowords.Andthreemore.".length(), inspector.inspect(pdf).pages().get(0).textChars());
    }

    @Test
    void measuresHowMuchOfAPageImagesCover() throws Exception {
        Path pdf = TestPdf.builder().imagePage(0.25).imagePage(0.64).write(dir.resolve("scans.pdf"));
        PdfInspection inspection = inspector.inspect(pdf);
        assertEquals(0.25, inspection.pages().get(0).imageCoverage(), 0.02);
        assertEquals(0.64, inspection.pages().get(1).imageCoverage(), 0.02);
    }

    @Test
    void reportsPageSizesAsDisplayed() throws Exception {
        Path pdf = TestPdf.builder().paragraph("Upright.").rotatedPage(90).paragraph("Turned.")
                .pageSize(595, 842).write(dir.resolve("rotated.pdf"));
        PdfInspection inspection = inspector.inspect(pdf);
        assertEquals(595f, inspection.pages().get(0).widthPt(), 0.01f);
        assertEquals(842f, inspection.pages().get(0).heightPt(), 0.01f);
        assertEquals(842f, inspection.pages().get(1).widthPt(), 0.01f);
        assertEquals(595f, inspection.pages().get(1).heightPt(), 0.01f);
    }

    @Test
    void reportsPasswordProtectedFiles() throws Exception {
        Path lecture = Lecture.write(dir.resolve("lecture.pdf"));
        PdfInspection locked = inspector.inspect(TestPdf.encrypt(lecture, dir.resolve("locked.pdf"), "open-sesame"));
        assertTrue(locked.encrypted());
        assertFalse(locked.isReadable());

        PdfInspection restricted = inspector.inspect(TestPdf.encrypt(lecture, dir.resolve("restricted.pdf"), ""));
        assertFalse(restricted.encrypted(), "opens without a password");
        assertTrue(restricted.isReadable());
        assertEquals(Lecture.PAGES, restricted.pageCount());
    }

    @Test
    void reportsDamagedFiles() throws Exception {
        byte[] valid = Files.readAllBytes(Lecture.write(dir.resolve("lecture.pdf")));
        Path junk = Files.write(dir.resolve("junk.pdf"), TestPdf.damagedBytes());
        Path empty = Files.write(dir.resolve("empty.pdf"), new byte[0]);
        Path notPdf = Files.writeString(dir.resolve("notes.pdf"), "These are my notes, not a PDF.", StandardCharsets.UTF_8);
        Path cut = Files.write(dir.resolve("cut.pdf"), Arrays.copyOf(valid, 400));

        for (Path file : new Path[] {junk, empty, notPdf, cut}) {
            PdfInspection inspection = inspector.inspect(file);
            assertTrue(inspection.damaged(), file.getFileName().toString());
            assertFalse(inspection.isReadable());
        }
    }

    @Test
    void readsWhatSurvivesInATruncatedFile() throws Exception {
        byte[] valid = Files.readAllBytes(Lecture.write(dir.resolve("lecture.pdf")));
        Path truncated = Files.write(dir.resolve("truncated.pdf"), Arrays.copyOf(valid, valid.length * 3 / 4));
        PdfInspection inspection = inspector.inspect(truncated);
        // Either the readable pages are reported or the file is reported as damaged; it never fails.
        assertTrue(inspection.damaged() || inspection.pageCount() > 0);
    }

    @Test
    void failsForMissingFiles() {
        PdfReadException e = assertThrows(PdfReadException.class, () -> inspector.inspect(dir.resolve("missing.pdf")));
        assertEquals(PdfReadException.Reason.MISSING, e.reason());
    }
}
