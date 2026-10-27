package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;

class MarginFilterTest {

    @Test
    void recognisesPageNumbers() {
        for (String number : List.of("12", "- 12 -", "Page 12", "Page 12 of 40", "12 / 40", "xii", "XIV", "p. 7", "(3)", "[4]")) {
            assertTrue(MarginFilter.isPageNumber(number), number);
        }
        for (String text : List.of("12 Angry Men", "Chapter 3", "2024 Annual Report", "Figure 3", "", "Summary")) {
            assertFalse(MarginFilter.isPageNumber(text), text);
        }
    }

    @Test
    void comparesRunningLinesWithoutTheirNumbers() {
        assertEquals(MarginFilter.key("Chapter 3  \u00b7 41"), MarginFilter.key("chapter 3 \u00b7 42"));
        assertEquals(MarginFilter.key("BIO 101 Lecture Notes 3"), MarginFilter.key("BIO 101 Lecture Notes"));
        assertEquals("bio lecture notes", MarginFilter.key("3 BIO 101 Lecture Notes"));
    }

    @Test
    void removesChapterHeadersThatChangeAndPageNumbers() {
        List<PageLayout> out = run(12, p -> page(p)
                .text(p <= 6 ? "Chapter 1 Cells" : "Chapter 2 Energy", 72, 50, 9)
                .text(String.valueOf(p), 300, 760, 9));
        assertEquals(12, out.size());
        for (PageLayout page : out) {
            String text = String.join(" ", SyntheticPage.blockTexts(page));
            assertFalse(text.contains("Chapter"), "page " + page.pageNumber() + ": " + text);
            assertFalse(text.matches(".*\\b" + page.pageNumber() + "$"), text);
            assertTrue(text.startsWith("Body of page " + page.pageNumber()), text);
        }
    }

    @Test
    void removesHeadersThatAlternateBetweenLeftAndRightPages() {
        List<PageLayout> out = run(10, p -> page(p).text(p % 2 == 0 ? p + "  Biology Basics" : "Chapter 1 Cells  " + p,
                p % 2 == 0 ? 72 : 400, 50, 9));
        for (PageLayout page : out) {
            String text = String.join(" ", SyntheticPage.blockTexts(page));
            assertFalse(text.contains("Biology Basics") || text.contains("Chapter 1"), "page " + page.pageNumber() + ": " + text);
        }
    }

    @Test
    void keepsLinesNearTheEdgeThatDoNotRepeat() {
        List<PageLayout> out = run(6, p -> page(p)
                .text(p == 3 ? "Summary of the results" : "Notes", 72, 50, 11)
                .text(p == 4 ? "Continued on the next page" : "", 72, 760, 9));
        String third = String.join(" ", SyntheticPage.blockTexts(out.get(2)));
        assertTrue(third.startsWith("Summary of the results"), third);
        String fourth = String.join(" ", SyntheticPage.blockTexts(out.get(3)));
        assertTrue(fourth.endsWith("Continued on the next page"), fourth);
        assertFalse(fourth.contains("Notes"));
    }

    @Test
    void keepsLargeTitlesAtTheTopOfEveryPage() {
        List<PageLayout> out = run(4, p -> page(p).text("Worksheet", 72, 50, 20, true));
        for (PageLayout page : out) {
            assertTrue(SyntheticPage.blockTexts(page).get(0).equals("Worksheet"));
        }
    }

    @Test
    void passesEveryPageOnOnceInOrder() {
        List<PageLayout> out = new ArrayList<>();
        MarginFilter filter = new MarginFilter(out::add);
        for (int p = 1; p <= 9; p++) {
            filter.accept(page(p).layout(p));
            assertEquals(Math.max(0, p - MarginFilter.WINDOW), out.size(), "streams with a short delay");
        }
        filter.finish();
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), out.stream().map(PageLayout::pageNumber).toList());
    }

    /** A page with a few lines of body text that differ from page to page. */
    private static SyntheticPage page(int p) {
        SyntheticPage page = new SyntheticPage();
        for (int row = 0; row < 5; row++) {
            page.text("Body of page " + p + " line " + row + " with some words", 72, 200 + row * 14, 11);
        }
        return page;
    }

    private static List<PageLayout> run(int pages, IntFunction<SyntheticPage> page) {
        List<PageLayout> out = new ArrayList<>();
        MarginFilter filter = new MarginFilter(out::add);
        for (int p = 1; p <= pages; p++) {
            filter.accept(page.apply(p).layout(p));
        }
        filter.finish();
        return out;
    }
}
