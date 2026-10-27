package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.spi.ChunkDraft;
import edu.learnsphere.core.spi.NormalizedRect;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.TextBlock;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.pdf.testing.TestPdf;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfBoxTextExtractorTest {

    @TempDir
    static Path dir;

    static Path lecture;
    static List<PageText> pages;

    private final PdfBoxTextExtractor extractor = new PdfBoxTextExtractor();

    @BeforeAll
    static void extractLecture() throws Exception {
        lecture = Lecture.write(dir.resolve("lecture.pdf"));
        pages = new PdfBoxTextExtractor().extractAll(lecture, CancellationToken.NONE);
    }

    @Test
    void deliversEveryPageInOrder() {
        assertEquals(IntStream.rangeClosed(1, Lecture.PAGES).boxed().toList(),
                pages.stream().map(PageText::pageNumber).toList());
    }

    @Test
    void readsTitleHeadingsAndBodyWithTheirFontSizes() {
        PageText first = pages.get(0);
        assertEquals(11f, first.bodyFontSize(), 0.01f);

        TextBlock title = first.blocks().get(0);
        assertEquals(Lecture.TITLE, title.text());
        assertEquals(20f, title.fontSize(), 0.01f);
        assertTrue(title.bold());
        assertEquals(1, title.lineCount());

        TextBlock heading = block(first, "1 The Cell Membrane");
        assertEquals(15f, heading.fontSize(), 0.01f);
        assertTrue(heading.bold());

        TextBlock subheading = block(first, "1.1 Lipid Bilayer");
        assertEquals(12.5f, subheading.fontSize(), 0.01f);
        assertTrue(subheading.bold());

        TextBlock intro = block(first, Lecture.INTRO);
        assertEquals(11f, intro.fontSize(), 0.01f);
        assertFalse(intro.bold());
        assertTrue(intro.lineCount() > 3, "the intro wraps over several lines");
    }

    @Test
    void keepsEveryParagraphWholeAndInReadingOrder() {
        String text = Lecture.flatText(pages);
        List<String> inOrder = List.of(Lecture.TITLE, Lecture.INTRO, "1 The Cell Membrane", Lecture.MEMBRANE,
                "1.1 Lipid Bilayer", Lecture.HYPHENATED_READ, Lecture.BILAYER, "2 Transport", Lecture.TRANSPORT_1,
                Lecture.TRANSPORT_2, Lecture.CLOSING);
        int from = 0;
        for (String part : inOrder) {
            int at = text.indexOf(part, from);
            assertTrue(at >= 0, () -> "missing or out of order: " + part.substring(0, Math.min(60, part.length())));
            from = at + part.length();
        }
        assertEquals(from, text.length(), "nothing but the paragraphs: " + text.substring(Math.min(from, text.length())));
    }

    @Test
    void removesRunningHeadersFootersAndPageNumbers() {
        for (PageText page : pages) {
            for (TextBlock block : page.blocks()) {
                assertFalse(block.text().contains(Lecture.HEADER), () -> "header on page " + page.pageNumber());
                assertFalse(block.text().contains("Lecture Notes"), () -> "footer on page " + page.pageNumber());
                assertFalse(block.text().matches("\\d+"), () -> "page number on page " + page.pageNumber());
            }
        }
    }

    @Test
    void readsTwoColumnPagesColumnByColumn() {
        PageText twoColumns = pages.get(2);
        assertEquals("2 Transport", twoColumns.blocks().get(0).text());
        // The first paragraph fills the left column and continues at the top of the right one.
        TextBlock left = twoColumns.blocks().get(1);
        TextBlock right = twoColumns.blocks().get(2);
        assertTrue(Lecture.TRANSPORT_1.startsWith(left.text()));
        assertTrue(Lecture.TRANSPORT_1.endsWith(right.text()));
        assertTrue(left.bounds().x() + left.bounds().width() < 0.5);
        assertTrue(right.bounds().x() > 0.5);
        assertTrue(right.bounds().y() < left.bounds().y() + 0.05, "the right column starts at the top");
    }

    @Test
    void keepsListItemsAndTableRowsWhole(@TempDir Path temp) throws Exception {
        List<String> steps = List.of("Put on goggles and a lab coat.", "Measure 50 ml of water into the beaker.",
                "Add one spoon of salt and stir until it dissolves, then write down the temperature of the solution"
                        + " every minute for ten minutes.",
                "Wash and dry all equipment.");
        List<String> facts = List.of("Mitochondria release energy.", "Ribosomes build proteins.", "The nucleus holds the DNA.",
                "Vacuoles store water.");
        String[] terms = {"Osmosis", "The movement of water across a partially permeable membrane.",
                "Diffusion", "The spreading of particles from high to low concentration.",
                "Active transport", "Movement against a concentration gradient, using energy released by respiration."};
        String closing = TestPdf.sentences(5, 3);
        Path pdf = TestPdf.builder().heading("Practical 3").paragraph("Follow these steps.")
                .numberedList(steps.toArray(String[]::new)).paragraph("Remember:").bulletList(facts.toArray(String[]::new))
                .heading("Key terms").glossary(128, terms).paragraph(closing).write(temp.resolve("practical.pdf"));

        List<String> expected = new ArrayList<>(List.of("Practical 3", "Follow these steps."));
        for (int i = 0; i < steps.size(); i++) {
            expected.add((i + 1) + ". " + steps.get(i));
        }
        expected.add("Remember:");
        facts.forEach(fact -> expected.add("\u2022 " + fact));
        expected.add("Key terms");
        for (int i = 0; i < terms.length; i += 2) {
            expected.add(terms[i] + " " + terms[i + 1]);
        }
        expected.add(closing);
        PageText page = extractor.extractPage(pdf, 1);
        assertEquals(expected, page.blocks().stream().map(TextBlock::text).toList());

        String passages = new SectionChunker().chunk(List.of(page)).stream().map(ChunkDraft::text)
                .collect(Collectors.joining("\n\n"));
        assertTrue(passages.contains("1. Put on goggles and a lab coat.\n\n2. Measure 50 ml"), passages);
        assertTrue(passages.contains("Diffusion The spreading of particles from high to low concentration.\n\nActive transport"),
                passages);
    }

    @Test
    void joinsWordsHyphenatedAtLineEnds() {
        String text = Lecture.flat(pages.get(0).text());
        assertTrue(text.contains("through photosynthesis in"), "photo- + synthesis is one word");
        assertTrue(text.contains("A well-known result"), "keeps the hyphen of a word written with one elsewhere");
        assertTrue(text.contains("Cases of COVID-19 are"), "keeps the hyphen before a number");
    }

    @Test
    void givesScannedPagesNoText() {
        PageText scanned = pages.get(Lecture.SCANNED_PAGE - 1);
        assertEquals(Lecture.SCANNED_PAGE, scanned.pageNumber());
        assertTrue(scanned.blocks().isEmpty());
        assertEquals(0, scanned.charCount());
    }

    @Test
    void placesBlockBoundsWhereTheTextIs() {
        TextBlock title = pages.get(0).blocks().get(0);
        NormalizedRect bounds = title.bounds();
        assertNotNull(bounds);
        assertTrue(bounds.y() < 0.15, "title near the top: " + bounds);
        assertTrue(bounds.height() > 0.015 && bounds.height() < 0.04, "about one 20 pt line: " + bounds);
        assertTrue(bounds.x() > 0.1 && bounds.x() < 0.13, "starts at the 72 pt margin: " + bounds);
        for (PageText page : pages) {
            for (TextBlock block : page.blocks()) {
                NormalizedRect rect = block.bounds();
                assertNotNull(rect);
                assertTrue(rect.x() + rect.width() <= 1 && rect.y() + rect.height() <= 1);
            }
        }
    }

    @Test
    void extractsSinglePagesExactlyAsTheWholeDocument() throws Exception {
        for (int page = 1; page <= Lecture.PAGES; page++) {
            assertEquals(pages.get(page - 1), extractor.extractPage(lecture, page), "page " + page);
        }
    }

    @Test
    void readsTextOnRotatedPagesAndPlacesItAsDisplayed(@TempDir Path temp) throws Exception {
        String text = TestPdf.sentences(11, 12);
        Path pdf = TestPdf.builder().rotatedPage(90).heading("Rotated").paragraph(text).write(temp.resolve("rotated.pdf"));
        PageText page = extractor.extractPage(pdf, 1);
        assertEquals("Rotated", page.blocks().get(0).text());
        assertEquals(text, Lecture.flat(page.blocks().get(1).text()));
        // Turned a quarter clockwise, the page's top-left corner shows at the top right and lines run downwards.
        NormalizedRect heading = page.blocks().get(0).bounds();
        assertTrue(heading.x() > 0.8 && heading.y() < 0.2, "heading at the top right as displayed: " + heading);
        assertTrue(heading.height() > heading.width(), "a line running downwards: " + heading);
    }

    @Test
    void keepsHeaderLinesThatDoNotRepeat(@TempDir Path temp) throws Exception {
        Path pdf = TestPdf.builder().runningHeader("Week 3 Reading").headerOnFirstPage(true)
                .paragraph(TestPdf.sentences(12, 120)).write(temp.resolve("short.pdf"));
        List<PageText> read = extractor.extractAll(pdf, CancellationToken.NONE);
        assertTrue(read.size() >= 3, "needs three pages: " + read.size());
        for (PageText page : read) {
            assertFalse(page.text().contains("Week"), "running header on page " + page.pageNumber());
        }

        Path single = TestPdf.builder().paragraph("Week 3 Reading").paragraph(TestPdf.sentences(13, 3))
                .write(temp.resolve("single.pdf"));
        assertTrue(extractor.extractPage(single, 1).text().startsWith("Week 3 Reading"));
    }

    @Test
    void stopsWhenCancelled() {
        CancellationToken token = CancellationToken.create();
        List<Integer> seen = new ArrayList<>();
        assertThrows(CancellationException.class, () -> extractor.extract(lecture, page -> {
            seen.add(page.pageNumber());
            if (page.pageNumber() == 2) {
                token.cancel();
            }
        }, token));
        assertEquals(List.of(1, 2), seen);
    }

    @Test
    void passesFailuresOfTheConsumerThrough() {
        IllegalStateException failure = new IllegalStateException("consumer failed");
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> extractor.extract(lecture, page -> {
                    throw failure;
                }, CancellationToken.NONE));
        assertSame(failure, thrown);
    }

    @Test
    void readsFilesWithOnlyAnOwnerPassword(@TempDir Path temp) throws Exception {
        Path restricted = TestPdf.encrypt(lecture, temp.resolve("restricted.pdf"), "");
        assertEquals(pages.get(0), extractor.extractPage(restricted, 1));
    }

    @Test
    void reportsFilesItCannotRead(@TempDir Path temp) throws Exception {
        Path locked = TestPdf.encrypt(lecture, temp.resolve("locked.pdf"), "open-sesame");
        Path damaged = Files.write(temp.resolve("damaged.pdf"), TestPdf.damagedBytes());
        Path missing = temp.resolve("missing.pdf");

        assertReason(PdfReadException.Reason.ENCRYPTED, DocumentError.ENCRYPTED,
                assertThrows(PdfReadException.class, () -> extractor.extractAll(locked, CancellationToken.NONE)));
        assertReason(PdfReadException.Reason.DAMAGED, DocumentError.DAMAGED,
                assertThrows(PdfReadException.class, () -> extractor.extractAll(damaged, CancellationToken.NONE)));
        assertReason(PdfReadException.Reason.MISSING, DocumentError.MISSING_FILE,
                assertThrows(PdfReadException.class, () -> extractor.extractPage(missing, 1)));
        assertEquals(PdfReadException.Reason.NO_SUCH_PAGE,
                assertThrows(PdfReadException.class, () -> extractor.extractPage(lecture, 0)).reason());
        assertEquals(PdfReadException.Reason.NO_SUCH_PAGE,
                assertThrows(PdfReadException.class, () -> extractor.extractPage(lecture, Lecture.PAGES + 1)).reason());
    }

    private static void assertReason(PdfReadException.Reason reason, DocumentError error, PdfReadException e) {
        assertEquals(reason, e.reason());
        assertEquals(error, e.documentError());
        assertEquals(error.userMessage(), e.userMessage());
    }

    private static TextBlock block(PageText page, String text) {
        return page.blocks().stream().filter(b -> b.text().equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("no block '" + text + "' in " + page.text()));
    }
}
