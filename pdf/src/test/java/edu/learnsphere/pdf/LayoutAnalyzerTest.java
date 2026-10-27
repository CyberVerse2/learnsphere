package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LayoutAnalyzerTest {

    @Test
    void keepsSuperscriptsOnTheirLine() {
        List<TextLine> lines = LayoutAnalyzer.buildLines(new SyntheticPage()
                .text("Energy is E = mc", 72, 200, 11)
                .text("2", 72 + 16 * 5.5f, 196, 7)
                .text(" in this model.", 72 + 16 * 5.5f + 3.5f, 200, 11)
                .glyphs());
        assertEquals(1, lines.size());
        assertEquals("Energy is E = mc2 in this model.", lines.get(0).text());
        assertEquals(11f, lines.get(0).size, 0.01f);
        assertEquals(200f, lines.get(0).baseline, 0.01f);
    }

    @Test
    void keepsWideGapsOfASingleLineInOneLine() {
        List<TextLine> lines = LayoutAnalyzer.buildLines(new SyntheticPage()
                .text("Melting point", 72, 200, 11)
                .text("41 C", 400, 200, 11)
                .text("The next line is ordinary text without gaps.", 72, 214, 11)
                .glyphs());
        assertEquals(2, lines.size());
        assertEquals("Melting point 41 C", lines.get(0).text());
    }

    @Test
    void readsSideBySideColumnsOneAfterTheOther() {
        SyntheticPage page = new SyntheticPage();
        for (int row = 0; row < 8; row++) {
            float baseline = 150 + row * 14;
            page.text("left column line " + row + " goes on", 72, baseline, 11);
            page.text("right column line " + row + " goes on", 330, baseline, 11);
        }
        List<String> blocks = SyntheticPage.blockTexts(page.layout(1));
        assertEquals(2, blocks.size(), blocks.toString());
        assertTrue(blocks.get(0).startsWith("left column line 0") && blocks.get(0).endsWith("left column line 7 goes on"));
        assertTrue(blocks.get(1).startsWith("right column line 0") && blocks.get(1).endsWith("right column line 7 goes on"));
    }

    @Test
    void readsAFullWidthTitleBeforeTheColumnsAndAFootnoteAfterThem() {
        SyntheticPage page = new SyntheticPage().text("A Title Across Both Columns", 150, 100, 18, true);
        for (int row = 0; row < 6; row++) {
            float baseline = 150 + row * 14;
            page.text("left " + row + " of the first column text", 72, baseline, 11);
            page.text("right " + row + " of the second column text", 330, baseline, 11);
        }
        page.text("A footnote that runs across the whole width of the page below both columns.", 72, 700, 8);
        List<String> blocks = SyntheticPage.blockTexts(page.layout(1));
        assertEquals(4, blocks.size(), blocks.toString());
        assertEquals("A Title Across Both Columns", blocks.get(0));
        assertTrue(blocks.get(1).startsWith("left 0"));
        assertTrue(blocks.get(2).startsWith("right 0"));
        assertTrue(blocks.get(3).startsWith("A footnote"));
    }

    @Test
    void separatesHeadingsFromParagraphs() {
        SyntheticPage page = new SyntheticPage().text("2.1 Diffusion", 72, 100, 14, true);
        for (int row = 0; row < 4; row++) {
            page.text("Molecules move from high to low concentration line " + row, 72, 120 + row * 14, 11);
        }
        page.text("Osmosis is the diffusion of water across a membrane line 0", 90, 190, 11);
        page.text("and it needs no energy from the cell at all line 1", 72, 204, 11);
        List<String> blocks = SyntheticPage.blockTexts(page.layout(1));
        assertEquals(3, blocks.size(), "heading, paragraph, indented paragraph: " + blocks);
        assertEquals("2.1 Diffusion", blocks.get(0));
    }
}
