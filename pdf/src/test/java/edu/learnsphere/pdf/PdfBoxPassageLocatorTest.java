package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.learnsphere.core.spi.NormalizedRect;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.pdf.testing.TestPdf;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxPassageLocatorTest {

    @TempDir
    static Path dir;

    static Path lecture;
    static List<PageText> pages;

    private final PdfBoxPassageLocator locator = new PdfBoxPassageLocator();

    @BeforeAll
    static void writeLecture() throws IOException {
        lecture = Lecture.write(dir.resolve("lecture.pdf"));
        pages = new PdfBoxTextExtractor().extractAll(lecture, CancellationToken.NONE);
    }

    @Test
    void highlightsAPassageLineByLine() throws Exception {
        String sentence = sentence(Lecture.MEMBRANE, 3);
        List<NormalizedRect> rects = locator.locate(lecture, 1, sentence);

        assertFalse(rects.isEmpty());
        assertTrue(rects.size() <= 3, "a sentence of under 20 words spans at most three lines: " + rects);
        NormalizedRect block = pages.get(0).blocks().stream().filter(b -> b.text().equals(Lecture.MEMBRANE)).findFirst()
                .orElseThrow().bounds();
        double previousY = -1;
        for (NormalizedRect rect : rects) {
            assertTrue(rect.height() > 0.01 && rect.height() < 0.025, "one 11 pt line: " + rect);
            assertTrue(rect.y() > previousY, "top to bottom: " + rects);
            previousY = rect.y();
            assertTrue(within(rect, block), rect + " inside the paragraph " + block);
        }
        assertInked(lecture, 1, rects);
    }

    @Test
    void ignoresDifferencesInSpacingCaseAndPunctuation() throws Exception {
        String sentence = sentence(Lecture.MEMBRANE, 3);
        String messy = "  " + sentence.toUpperCase(Locale.ROOT).replace(" ", " \n  ").replace(".", "") + " ";
        assertEquals(locator.locate(lecture, 1, sentence), locator.locate(lecture, 1, messy));
    }

    @Test
    void findsWordsHyphenatedAtLineEnds() throws Exception {
        List<NormalizedRect> rects = locator.locate(lecture, 1, "Plants capture light through photosynthesis in the chloroplast.");
        assertEquals(2, rects.size(), "photo- ends the first line: " + rects);
        assertTrue(rects.get(1).width() < rects.get(0).width(), "the second rectangle stops after 'chloroplast.'");
        assertInked(lecture, 1, rects);
    }

    @Test
    void followsTheColumnsOfATwoColumnPage() throws Exception {
        PageText page = pages.get(2);
        String left = page.blocks().get(1).text();
        String right = page.blocks().get(2).text();
        String passage = lastWords(left, 25) + " " + firstWords(right, 25);

        List<NormalizedRect> rects = locator.locate(lecture, 3, passage);
        assertTrue(rects.size() >= 6, rects.toString());
        int firstRight = 0;
        while (firstRight < rects.size() && rects.get(firstRight).x() < 0.5) {
            NormalizedRect rect = rects.get(firstRight);
            assertTrue(rect.x() + rect.width() < 0.5, "left column: " + rect);
            assertTrue(rect.y() > 0.6, "end of the left column: " + rect);
            firstRight++;
        }
        assertTrue(firstRight > 0 && firstRight < rects.size(), "starts left, ends right: " + rects);
        for (NormalizedRect rect : rects.subList(firstRight, rects.size())) {
            assertTrue(rect.x() > 0.5, "right column: " + rect);
            assertTrue(rect.y() < 0.4, "top of the right column: " + rect);
        }
        assertInked(lecture, 3, rects);
    }

    @Test
    void findsThePartOfAPassageOnEachPage() throws Exception {
        List<String> pageOne = pages.get(0).blocks().stream().map(b -> b.text()).toList();
        String passage = lastWords(pageOne.get(pageOne.size() - 1), 30) + " " + firstWords(pages.get(1).blocks().get(0).text(), 30);

        List<NormalizedRect> onFirst = locator.locate(lecture, 1, passage);
        assertFalse(onFirst.isEmpty());
        assertTrue(onFirst.get(0).y() > 0.7, "bottom of page 1: " + onFirst);

        List<NormalizedRect> onSecond = locator.locate(lecture, 2, passage);
        assertFalse(onSecond.isEmpty());
        assertTrue(onSecond.get(onSecond.size() - 1).y() < 0.3, "top of page 2: " + onSecond);
    }

    @Test
    void toleratesSmallDifferencesInWording() throws Exception {
        String sentence = sentence(Lecture.TRANSPORT_2, 4);
        String[] words = sentence.split(" ");
        words[words.length / 2] = "something";
        List<NormalizedRect> exact = locator.locate(lecture, 3, sentence);
        List<NormalizedRect> reworded = locator.locate(lecture, 3, String.join(" ", words));
        assertFalse(reworded.isEmpty());
        assertEquals(exact.size(), reworded.size());
    }

    @Test
    void findsNothingForTextThatIsNotThere() throws Exception {
        assertEquals(List.of(), locator.locate(lecture, 1, "The quick brown fox jumps over the lazy dog by the river."));
        assertEquals(List.of(), locator.locate(lecture, 1, "   "));
        assertEquals(List.of(), locator.locate(lecture, 1, null));
        assertEquals(List.of(), locator.locate(lecture, Lecture.SCANNED_PAGE, sentence(Lecture.MEMBRANE, 1)));
        assertEquals(List.of(), locator.locate(lecture, 2, sentence(Lecture.CLOSING, 2)), "on another page");
    }

    @Test
    void highlightsTextOnRotatedPages() throws Exception {
        String text = TestPdf.sentences(21, 8);
        Path pdf = TestPdf.builder().rotatedPage(270).paragraph(text).write(dir.resolve("rotated.pdf"));
        List<NormalizedRect> rects = locator.locate(pdf, 1, sentence(text, 2));
        assertFalse(rects.isEmpty());
        for (NormalizedRect rect : rects) {
            assertTrue(rect.height() > rect.width() || rect.width() < 0.03, "lines run vertically: " + rect);
        }
        assertInked(pdf, 1, rects);
    }

    @Test
    void reportsFilesAndPagesItCannotRead() {
        assertEquals(PdfReadException.Reason.MISSING,
                assertThrows(PdfReadException.class, () -> locator.locate(dir.resolve("gone.pdf"), 1, "text")).reason());
        assertEquals(PdfReadException.Reason.NO_SUCH_PAGE,
                assertThrows(PdfReadException.class, () -> locator.locate(lecture, 9, "text")).reason());
        assertEquals(PdfReadException.Reason.NO_SUCH_PAGE,
                assertThrows(PdfReadException.class, () -> locator.locate(lecture, 0, "")).reason());
    }

    /** The {@code index}-th sentence (1-based) of filler text. */
    static String sentence(String text, int index) {
        return text.split("(?<=\\.) ")[index - 1];
    }

    static String firstWords(String text, int count) {
        String[] words = text.split("\\s+");
        return String.join(" ", Arrays.copyOfRange(words, 0, Math.min(count, words.length)));
    }

    static String lastWords(String text, int count) {
        String[] words = text.split("\\s+");
        return String.join(" ", Arrays.copyOfRange(words, Math.max(0, words.length - count), words.length));
    }

    private static boolean within(NormalizedRect inner, NormalizedRect outer) {
        double slack = 0.002;
        return inner.x() >= outer.x() - slack && inner.y() >= outer.y() - slack
                && inner.x() + inner.width() <= outer.x() + outer.width() + slack
                && inner.y() + inner.height() <= outer.y() + outer.height() + slack;
    }

    /** Every rectangle covers printed text on the rendered page. */
    private static void assertInked(Path pdf, int page, List<NormalizedRect> rects) throws IOException {
        BufferedImage image = PdfBoxPageRendererTest.decode(new PdfBoxPageRenderer().renderPng(pdf, page, 2f));
        for (NormalizedRect rect : rects) {
            int x0 = (int) (rect.x() * image.getWidth());
            int y0 = (int) (rect.y() * image.getHeight());
            int x1 = (int) Math.ceil((rect.x() + rect.width()) * image.getWidth());
            int y1 = (int) Math.ceil((rect.y() + rect.height()) * image.getHeight());
            double ink = PdfBoxPageRendererTest.darkShare(image, x0, y0, x1, y1);
            assertTrue(ink > 0.08, "ink under " + rect + ": " + ink);
        }
    }
}
