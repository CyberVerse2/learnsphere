package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
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
    void keepsNumbersOfAWordStyleListWithTheirItems() {
        // Numbers a quarter inch in, text half an inch in, as word processors lay lists out.
        SyntheticPage page = new SyntheticPage();
        List<String> items = List.of("Wear goggles in the lab at all times", "Tie back long hair before you start",
                "Keep bags out of the walkways", "Report every spill to the teacher");
        for (int i = 0; i < items.size(); i++) {
            page.text((i + 1) + ".", 86, 200 + i * 14, 11);
            page.text(items.get(i), 108, 200 + i * 14, 11);
        }
        page.text("and wipe the bench dry", 108, 200 + 4 * 14, 11);
        assertEquals(List.of("1. Wear goggles in the lab at all times", "2. Tie back long hair before you start",
                "3. Keep bags out of the walkways", "4. Report every spill to the teacher | and wipe the bench dry"),
                SyntheticPage.blockTexts(page.layout(1)));
    }

    @Test
    void keepsBulletsWithTheirItems() {
        SyntheticPage page = new SyntheticPage();
        List<String> items = List.of("Mitochondria release energy", "Ribosomes build proteins", "The nucleus holds the DNA");
        for (int i = 0; i < items.size(); i++) {
            page.text("\u2022", 90, 300 + i * 14, 11);
            page.text(items.get(i), 108, 300 + i * 14, 11);
        }
        assertEquals(List.of("\u2022 Mitochondria release energy", "\u2022 Ribosomes build proteins", "\u2022 The nucleus holds the DNA"),
                SyntheticPage.blockTexts(page.layout(1)));
    }

    @Test
    void readsATableWithANarrowColumnRowByRow() {
        SyntheticPage page = new SyntheticPage();
        page.text("Osmosis", 72, 200, 11).text("Water moving across a membrane", 200, 200, 11);
        page.text("Diffusion", 72, 214, 11).text("Particles spreading from high to low", 200, 214, 11);
        page.text("concentration until they are even", 200, 228, 11);
        page.text("Active transport", 72, 242, 11).text("Movement against a gradient using energy", 200, 242, 11);
        page.text("All three move substances in and out of cells.", 72, 256, 11);
        assertEquals(List.of("Osmosis Water moving across a membrane",
                "Diffusion Particles spreading from high to low | concentration until they are even",
                "Active transport Movement against a gradient using energy",
                "All three move substances in and out of cells."), SyntheticPage.blockTexts(page.layout(1)));

        List<TextLine> lines = LayoutAnalyzer.buildLines(page.glyphs());
        assertTrue(lines.get(0).tabular, "a table row read across: " + lines.get(0));
        assertFalse(lines.getLast().tabular);
    }

    @Test
    void readsATableOfValuesRowByRow() {
        SyntheticPage page = new SyntheticPage();
        List<String> names = List.of("Melting point of ice at sea level", "Boiling point of water at sea level",
                "Normal human body temperature");
        List<String> values = List.of("0 C", "100 C", "37 C");
        for (int i = 0; i < names.size(); i++) {
            page.text(names.get(i), 72, 400 + i * 14, 11).text(values.get(i), 330, 400 + i * 14, 11);
        }
        assertEquals(List.of("Melting point of ice at sea level 0 C", "Boiling point of water at sea level 100 C",
                "Normal human body temperature 37 C"), SyntheticPage.blockTexts(page.layout(1)));
    }

    @Test
    void readsColumnsThatHoldListsColumnByColumn() {
        SyntheticPage page = new SyntheticPage();
        for (int row = 0; row < 8; row++) {
            float baseline = 150 + row * 14;
            page.text("left column line " + row + " goes on", 72, baseline, 11);
            if (row < 4) {
                page.text((row + 1) + ".", 326, baseline, 11).text("item " + row + " of the right list", 348, baseline, 11);
            } else {
                page.text("right column line " + row + " goes on", 326, baseline, 11);
            }
        }
        List<String> blocks = SyntheticPage.blockTexts(page.layout(1));
        assertEquals(String.join(" | ", IntStream.range(0, 8).mapToObj(r -> "left column line " + r + " goes on").toList()),
                blocks.get(0));
        String right = String.join(" | ", blocks.subList(1, blocks.size()));
        assertEquals("1. item 0 of the right list | 2. item 1 of the right list | 3. item 2 of the right list"
                + " | 4. item 3 of the right list | right column line 4 goes on | right column line 5 goes on"
                + " | right column line 6 goes on | right column line 7 goes on", right);
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
