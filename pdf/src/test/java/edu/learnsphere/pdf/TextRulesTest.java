package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.learnsphere.core.spi.TextBlock;
import edu.learnsphere.pdf.testing.TestPdf;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Sentence splitting, token estimates, heading detection, match form and passage matching. */
class TextRulesTest {

    // ------------------------------------------------------------------------------------------ sentences

    @Test
    void splitsSentencesAtTheirEnds() {
        assertEquals(List.of("Cells divide.", "They grow!", "Do they?", "Yes."), texts("Cells divide. They grow! Do they? Yes."));
        assertEquals(List.of("He said \"Stop.\"", "Then he left."), texts("He said \"Stop.\" Then he left."));
        assertEquals(List.of("\u7ec6\u80de\u5206\u88c2\u3002", "\u7ec6\u80de\u751f\u957f\u3002"),
                texts("\u7ec6\u80de\u5206\u88c2\u3002\u7ec6\u80de\u751f\u957f\u3002"));
        assertEquals(List.of("A sentence without an end"), texts("A sentence without an end"));
        assertEquals(List.of(), texts("   "));
    }

    @Test
    void keepsAbbreviationsNumbersAndInitialsInsideSentences() {
        assertEquals(List.of("See Fig. 3 for details.", "Then stop."), texts("See Fig. 3 for details. Then stop."));
        assertEquals(List.of("J. Watson and F. Crick found it.", "Next."), texts("J. Watson and F. Crick found it. Next."));
        assertEquals(List.of("The value is 3.14 today.", "Done."), texts("The value is 3.14 today. Done."));
        assertEquals(List.of("Use e.g. salt or sugar.", "Visit example.com today."),
                texts("Use e.g. salt or sugar. Visit example.com today."));
        assertEquals(List.of("Smith et al. showed it in 2019."), texts("Smith et al. showed it in 2019."));
    }

    @Test
    void splitsAtLineBreaksAndRemembersThem() {
        List<SentenceSplitter.Sentence> sentences = SentenceSplitter.split("Step one\nStep two. Step three.");
        assertEquals(List.of(new SentenceSplitter.Sentence("Step one", false), new SentenceSplitter.Sentence("Step two.", true),
                new SentenceSplitter.Sentence("Step three.", false)), sentences);
    }

    // ------------------------------------------------------------------------------------------ tokens

    @Test
    void estimatesTokens() {
        assertEquals(0, TokenEstimator.estimate(null));
        assertEquals(0, TokenEstimator.estimate(" \n "));
        assertEquals(1, TokenEstimator.estimate("a"));
        assertEquals(1, TokenEstimator.estimate("cell"));
        assertEquals(2, TokenEstimator.estimate("membrane"));
        assertEquals(4, TokenEstimator.estimate("2024"));
        assertEquals(4, TokenEstimator.estimate("Hello, world."));
        assertEquals(2, TokenEstimator.estimate("\u7ec6\u80de"));
        String prose = "The cell is the basic unit of life. Every living thing is made of one or more cells, and each "
                + "cell comes from another cell. Cells take in food, release energy and get rid of waste. Inside the "
                + "nucleus, long strands of DNA carry the instructions that tell the cell how to grow and divide.";
        double perWord = TokenEstimator.estimate(prose) / (double) prose.split(" ").length;
        assertTrue(perWord > 1.1 && perWord < 1.5, "tokens per word: " + perWord);
    }

    // ------------------------------------------------------------------------------------------ headings

    @Test
    void findsHeadingsByTypeAndNumbering() {
        assertEquals("Introduction", heading("Introduction", 16, false));
        assertEquals("Results of the trial", heading("Results of the trial", 11, true));
        assertEquals("3.2 Results", heading("3.2 Results", 11, false));
        assertEquals("Chapter 4 Genetics", heading("Chapter 4 Genetics", 11, false));
        assertEquals("2 Phase 1", heading("2 Phase 1", 14, true));
        assertEquals("1.1 Topic 1", heading("1.1 Topic 1", 12.5f, true));
        assertEquals("Methods", HeadingDetector.headingText(TextBlock.plain("## Methods"), 0));
        assertEquals("Chapter 4 Genetics", HeadingDetector.headingText(TextBlock.plain("Chapter 4 Genetics"), 0));
        assertEquals("4 Genetics", HeadingDetector.headingText(TextBlock.plain("4 Genetics"), 0));
    }

    @Test
    void ignoresTextThatOnlyLooksLikeAHeading() {
        assertNull(heading("Cells take in water and nutrients through the membrane every second", 11, false), "body text");
        assertNull(heading("3.2 results are shown below", 11, false), "a sentence starting with a number");
        assertNull(heading("Figure 3. Cell structure", 9, false), "caption");
        assertNull(heading("Figure 2 Membrane transport", 11, true), "bold caption");
        assertNull(heading("3.2 Membranes ........ 47", 11, true), "contents entry with leaders");
        assertNull(heading("3.2 Membranes 47", 11, false), "contents entry");
        assertNull(heading("Note:", 11, true), "label");
        assertNull(heading("This bold sentence ends with a full stop.", 11, true), "bold sentence");
        assertNull(heading("Small print", 8, true), "smaller than the body");
        assertNull(HeadingDetector.headingText(new TextBlock("Long heading", 5, 16, true, null), 11), "five lines");
        assertNull(HeadingDetector.headingText(TextBlock.plain("This is text"), 0));
        assertNull(HeadingDetector.headingText(TextBlock.plain("3.2 Membranes 47"), 0));
    }

    // ------------------------------------------------------------------------------------------ matching

    @Test
    void comparesTextInMatchForm() {
        assertEquals("photosynthesiscafefi", TextNormalizer.matchForm("Photo-\nsynthesis, Caf\u00e9! \ufb01"));
        assertEquals("", TextNormalizer.matchForm(" .,;- "));
    }

    @Test
    void matchesPassagesExactlyOrClosely() {
        String page = TextNormalizer.matchForm(TestPdf.sentences(8, 12));
        String passage = page.substring(200, 320);

        PassageMatcher.Match exact = PassageMatcher.find(page, passage);
        assertEquals(200, exact.start());
        assertEquals(320, exact.end());

        String typo = passage.substring(0, 60) + "x" + passage.substring(61);
        PassageMatcher.Match close = PassageMatcher.find(page, typo);
        assertNotNull(close);
        assertEquals(200, close.start());
        assertEquals(320, close.end());

        String reworded = passage.substring(0, 50) + "somethingelse" + passage.substring(58);
        assertNotNull(PassageMatcher.find(page, reworded));
    }

    @Test
    void matchesThePartOfAPassageOnThePage() {
        String page = TextNormalizer.matchForm(TestPdf.sentences(9, 10));
        String continuing = page.substring(page.length() - 80) + TextNormalizer.matchForm(TestPdf.sentences(10, 3));
        PassageMatcher.Match tail = PassageMatcher.find(page, continuing);
        assertNotNull(tail);
        assertEquals(page.length() - 80, tail.start());
        assertEquals(page.length(), tail.end());
    }

    @Test
    void reportsTheRangesThatMatch() {
        String a = TextNormalizer.matchForm(TestPdf.sentences(11, 3));
        String b = TextNormalizer.matchForm(TestPdf.sentences(12, 3));
        String page = "introduction" + a + "biolecturenotes" + b + "end";
        PassageMatcher.Match match = PassageMatcher.find(page, a + b);
        assertNotNull(match);
        int footer = page.indexOf("biolecturenotes");
        for (int s = 0; s < match.segmentCount(); s++) {
            assertTrue(match.segmentEnd(s) <= footer + 2 || match.segmentStart(s) >= footer + 13,
                    "no segment covers the footer: " + match.segmentStart(s) + "-" + match.segmentEnd(s));
        }
        assertTrue(match.segmentCount() >= 2);
    }

    @Test
    void rejectsPassagesThatAreNotThere() {
        String page = TextNormalizer.matchForm(TestPdf.sentences(13, 12));
        assertNull(PassageMatcher.find(page, TextNormalizer.matchForm("The quick brown fox jumps over the lazy dog by the river.")));
        assertNull(PassageMatcher.find(page, "zzzzqqqq"), "short passages must occur exactly");
        assertNull(PassageMatcher.find("", "anything"));
        assertNull(PassageMatcher.find(page, ""));
    }

    private static List<String> texts(String text) {
        return SentenceSplitter.split(text).stream().map(SentenceSplitter.Sentence::text).toList();
    }

    private static String heading(String text, float size, boolean bold) {
        return HeadingDetector.headingText(new TextBlock(text, 1, size, bold, null), 11);
    }
}
