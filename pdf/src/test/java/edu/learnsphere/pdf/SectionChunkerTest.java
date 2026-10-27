package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.learnsphere.core.spi.ChunkDraft;
import edu.learnsphere.core.spi.Chunker.Options;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.TextBlock;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.pdf.testing.TestPdf;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SectionChunkerTest {

    private static final int LIMIT = Options.DEFAULT.targetTokens() * 5 / 4 + 4;

    @TempDir
    static Path dir;

    static List<PageText> pages;
    static List<ChunkDraft> chunks;

    private final SectionChunker chunker = new SectionChunker();

    @BeforeAll
    static void chunkLecture() throws Exception {
        pages = new PdfBoxTextExtractor().extractAll(Lecture.write(dir.resolve("lecture.pdf")), CancellationToken.NONE);
        chunks = new SectionChunker().chunk(pages);
    }

    @Test
    void numbersPassagesAndKeepsThemWithinTheTarget() {
        assertTrue(chunks.size() >= 8, "about 3,000 tokens in passages of 350: " + chunks.size());
        int previousStart = 1;
        for (int i = 0; i < chunks.size(); i++) {
            ChunkDraft chunk = chunks.get(i);
            assertEquals(i, chunk.ordinal());
            assertFalse(chunk.text().isBlank());
            assertEquals(TokenEstimator.estimate(chunk.text()), chunk.tokenEstimate());
            assertTrue(chunk.tokenEstimate() <= LIMIT, "passage " + i + " has " + chunk.tokenEstimate() + " tokens");
            assertTrue(chunk.pageStart() >= previousStart && chunk.pageStart() <= chunk.pageEnd()
                    && chunk.pageEnd() <= Lecture.PAGES, "pages " + chunk.pageStart() + "-" + chunk.pageEnd());
            previousStart = chunk.pageStart();
        }
        for (ChunkDraft chunk : chunks) {
            assertTrue(chunk.tokenEstimate() >= Options.DEFAULT.targetTokens() / 4, "no scraps: " + chunk.tokenEstimate());
        }
    }

    @Test
    void startsAPassageAtEachHeadingAndCarriesTheSectionTitle() {
        List<String> headings = List.of(Lecture.TITLE, "1 The Cell Membrane", "1.1 Lipid Bilayer", "2 Transport");
        for (String heading : headings) {
            List<ChunkDraft> containing = chunks.stream().filter(c -> c.text().contains(heading + "\n\n")).toList();
            assertEquals(1, containing.size(), heading);
            ChunkDraft first = containing.get(0);
            assertTrue(first.text().startsWith(heading + "\n\n"), heading + " starts its passage: " + first.text());
            assertEquals(heading, first.sectionTitle());
        }
        assertEquals("2 Transport", chunks.getLast().sectionTitle(), "the closing page has no heading of its own");
        assertEquals(List.of(Lecture.TITLE, "1 The Cell Membrane", "1.1 Lipid Bilayer", "2 Transport"),
                chunks.stream().map(ChunkDraft::sectionTitle).distinct().toList());
    }

    @Test
    void neverCutsASentence() {
        for (String paragraph : List.of(Lecture.INTRO, Lecture.MEMBRANE, Lecture.HYPHENATED_READ, Lecture.BILAYER,
                Lecture.TRANSPORT_1, Lecture.TRANSPORT_2, Lecture.CLOSING)) {
            for (String sentence : sentences(paragraph)) {
                assertTrue(chunks.stream().anyMatch(c -> Lecture.flat(c.text()).contains(sentence)), "whole somewhere: " + sentence);
            }
        }
        for (ChunkDraft chunk : chunks) {
            assertTrue(Character.isUpperCase(chunk.text().charAt(0)) || Character.isDigit(chunk.text().charAt(0)),
                    "starts a sentence: " + chunk.text());
            assertTrue(chunk.text().endsWith("."), "ends a sentence: " + chunk.text());
        }
    }

    @Test
    void repeatsTheLastSentencesOfThePreviousPassage() {
        int overlaps = 0;
        for (int i = 1; i < chunks.size(); i++) {
            ChunkDraft chunk = chunks.get(i);
            if (headingStarts(chunk)) {
                continue;
            }
            String firstSentence = sentences(Lecture.flat(chunk.text())).get(0);
            String previous = Lecture.flat(chunks.get(i - 1).text());
            assertTrue(previous.endsWith(firstSentence) || previous.contains(firstSentence + " "),
                    "passage " + i + " opens with text from passage " + (i - 1));
            int repeated = previous.length() - previous.indexOf(firstSentence);
            assertTrue(TokenEstimator.estimate(previous.substring(previous.length() - repeated)) <= 90,
                    "overlap stays near 60 tokens");
            overlaps++;
        }
        assertTrue(overlaps >= 4);
    }

    @Test
    void recordsThePagesAPassageDrawsFrom() {
        // One sentence of the long paragraph starts on page 1 and ends on page 2.
        String page1 = Lecture.flat(pages.get(0).text());
        String page2 = Lecture.flat(pages.get(1).text());
        String straddling = sentences(Lecture.BILAYER).stream()
                .filter(s -> !page1.contains(s) && !page2.contains(s)).findFirst().orElseThrow();
        ChunkDraft chunk = chunks.stream().filter(c -> Lecture.flat(c.text()).contains(straddling)).findFirst().orElseThrow();
        assertEquals(1, chunk.pageStart());
        assertEquals(2, chunk.pageEnd());

        // The scanned page 5 has no text, so the passage around it runs from page 4 to page 6.
        assertTrue(chunks.stream().noneMatch(c -> c.pageStart() == Lecture.SCANNED_PAGE));
        assertEquals(Lecture.PAGES, chunks.getLast().pageEnd());
    }

    @Test
    void handlesEmptyAndTinyDocuments() {
        assertEquals(List.of(), chunker.chunk(List.of()));
        assertEquals(List.of(), chunker.chunk(null, Options.DEFAULT));
        assertEquals(List.of(), chunker.chunk(List.of(new PageText(1, 0, List.of()), PageText.ofPlainText(2, "  \n\n "),
                new PageText(3, 11, List.of(new TextBlock("   ", 1, 11, false, null))))));

        List<ChunkDraft> one = chunker.chunk(List.of(new PageText(1, 0, List.of()), PageText.ofPlainText(2, "Cells divide.")));
        assertEquals(1, one.size());
        assertEquals(new ChunkDraft(0, 2, 2, null, "Cells divide.", TokenEstimator.estimate("Cells divide.")), one.get(0));

        List<ChunkDraft> titleOnly = chunker.chunk(List.of(new PageText(1, 11,
                List.of(new TextBlock("Lab Safety", 1, 24, true, null), new TextBlock("Wear goggles.", 1, 11, false, null)))));
        assertEquals(1, titleOnly.size());
        assertEquals("Lab Safety\n\nWear goggles.", titleOnly.get(0).text());
        assertEquals("Lab Safety", titleOnly.get(0).sectionTitle());
    }

    @Test
    void cutsSentencesLongerThanThePassage() {
        String endless = IntStream.range(0, 1_500).mapToObj(i -> i % 40 == 39 ? "membrane," : "membrane")
                .collect(Collectors.joining(" ")) + ".";
        String hugeWord = "x".repeat(6_000);
        List<ChunkDraft> result = chunker.chunk(List.of(PageText.ofPlainText(1, endless), PageText.ofPlainText(2, hugeWord)));
        assertTrue(result.size() >= 8, "1,500 words and 6,000 letters need several passages: " + result.size());
        for (ChunkDraft chunk : result) {
            assertFalse(chunk.text().isBlank());
            assertTrue(chunk.tokenEstimate() <= LIMIT, chunk.tokenEstimate() + " tokens");
        }
        long words = result.stream().filter(c -> c.pageEnd() == 1)
                .flatMap(c -> Arrays.stream(c.text().split("\\s+"))).count();
        assertTrue(words >= 1_500, "every word kept: " + words);
        String letters = result.stream().filter(c -> c.pageStart() == 2).map(ChunkDraft::text)
                .collect(Collectors.joining()).replaceAll("\\s", "");
        assertTrue(letters.length() >= 6_000, "the whole word kept: " + letters.length());
    }

    @Test
    void readsHeadingsOfPagesWithoutTypeSizes() {
        String body = TestPdf.sentences(31, 30);
        List<PageText> plain = List.of(
                PageText.ofPlainText(1, "# Photosynthesis\n\n" + body),
                PageText.ofPlainText(2, "3.2 Light Reactions\n\n" + TestPdf.sentences(32, 30)));
        List<ChunkDraft> result = chunker.chunk(plain);
        assertEquals("Photosynthesis", result.get(0).sectionTitle());
        assertTrue(result.get(0).text().startsWith("Photosynthesis\n\n"));
        assertEquals("3.2 Light Reactions", result.getLast().sectionTitle());
    }

    @Test
    void letsAShortSectionRunIntoTheNextOne() {
        List<PageText> doc = List.of(new PageText(1, 11, List.of(
                new TextBlock("Overview", 1, 16, true, null),
                new TextBlock("This part is short.", 1, 11, false, null),
                new TextBlock("Details", 1, 16, true, null),
                new TextBlock(TestPdf.sentences(41, 12), 8, 11, false, null))));
        List<ChunkDraft> result = chunker.chunk(doc);
        assertEquals(1, result.size());
        assertTrue(result.get(0).text().startsWith("Overview\n\nThis part is short.\n\nDetails\n\n"));
        assertEquals("Details", result.get(0).sectionTitle(), "named after the section filling most of it");
    }

    @Test
    void mendsWordsBrokenAcrossPages() {
        List<ChunkDraft> result = chunker.chunk(List.of(
                PageText.ofPlainText(1, "The membrane controls the trans-"),
                PageText.ofPlainText(2, "port of molecules into the cell. Water moves freely.")));
        assertEquals(1, result.size());
        assertEquals("The membrane controls the transport of molecules into the cell. Water moves freely.", result.get(0).text());
        assertEquals(1, result.get(0).pageStart());
        assertEquals(2, result.get(0).pageEnd());
    }

    @Test
    void followsTheOptions() {
        List<ChunkDraft> small = chunker.chunk(pages, new Options(120, 0));
        assertTrue(small.size() > chunks.size() * 2, small.size() + " vs " + chunks.size());
        List<String> all = new ArrayList<>();
        for (ChunkDraft chunk : small) {
            assertTrue(chunk.tokenEstimate() <= 120 * 5 / 4 + 4, chunk.tokenEstimate() + " tokens");
            all.addAll(sentences(Lecture.flat(chunk.text())));
        }
        assertEquals(all.stream().distinct().count(), all.size(), "no overlap: every sentence once");
    }

    @Test
    void chunksStoredPlainTextLikeTheExtractedPages() {
        List<PageText> plain = pages.stream().map(p -> PageText.ofPlainText(p.pageNumber(), p.text())).toList();
        List<ChunkDraft> fromPlain = chunker.chunk(plain);
        // Without type sizes the numbered headings are still recognised; the unnumbered title is not.
        assertNull(fromPlain.get(0).sectionTitle());
        assertEquals(Arrays.asList(null, "1 The Cell Membrane", "1.1 Lipid Bilayer", "2 Transport"),
                fromPlain.stream().map(ChunkDraft::sectionTitle).distinct().toList());
        assertEquals(chunks.size(), fromPlain.size());
    }

    private static boolean headingStarts(ChunkDraft chunk) {
        return chunk.text().startsWith(chunk.sectionTitle() + "\n\n");
    }

    /** Sentences of filler text, which only uses full stops. */
    private static List<String> sentences(String text) {
        return Arrays.stream(text.split("(?<=\\.) ")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
