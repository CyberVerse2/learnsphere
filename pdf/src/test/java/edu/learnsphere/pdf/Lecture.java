package edu.learnsphere.pdf;

import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.pdf.testing.TestPdf;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A six-page lecture handout shared by the tests:
 *
 * <ol>
 *   <li>title, intro, heading "1 The Cell Membrane", subheading "1.1 Lipid Bilayer" with hyphenated line
 *       ends, and the start of a long paragraph</li>
 *   <li>the rest of that paragraph</li>
 *   <li>two columns: heading "2 Transport" and two paragraphs flowing from the left column into the right</li>
 *   <li>the end of the second paragraph, still in two-column layout</li>
 *   <li>a scanned page (one image, no text)</li>
 *   <li>a closing paragraph</li>
 * </ol>
 *
 * <p>Every page but the first has the running header, every page has the running footer, and every text
 * page has a centred page number.
 */
final class Lecture {

    static final String HEADER = "Introduction to Cell Biology";
    static final String FOOTER = "BIO 101 Lecture Notes";
    static final String TITLE = "Cells and Membranes";
    static final String INTRO = TestPdf.sentences(1, 6);
    static final String MEMBRANE = TestPdf.sentences(2, 14);
    static final String HYPHENATED = "Plants capture light through photo-\nsynthesis in the chloroplast. The well-known "
            + "model is the fluid mosaic. A well-\nknown result follows. Cases of COVID-\n19 are not relevant here.";
    static final String HYPHENATED_READ = "Plants capture light through photosynthesis in the chloroplast. The well-known "
            + "model is the fluid mosaic. A well-known result follows. Cases of COVID-19 are not relevant here.";
    static final String BILAYER = TestPdf.sentences(3, 30);
    static final String TRANSPORT_1 = TestPdf.sentences(4, 25);
    static final String TRANSPORT_2 = TestPdf.sentences(5, 25);
    static final String CLOSING = TestPdf.sentences(6, 10);
    static final int PAGES = 6;
    static final int SCANNED_PAGE = 5;

    private Lecture() {
    }

    static Path write(Path file) throws IOException {
        return TestPdf.builder()
                .runningHeader(HEADER)
                .runningFooter(FOOTER)
                .pageNumbers()
                .title(TITLE)
                .paragraph(INTRO)
                .heading("1 The Cell Membrane")
                .paragraph(MEMBRANE)
                .subheading("1.1 Lipid Bilayer")
                .paragraph(HYPHENATED)
                .paragraph(BILAYER)
                .columns(2)
                .heading("2 Transport")
                .paragraph(TRANSPORT_1)
                .paragraph(TRANSPORT_2)
                .imagePage(1.0)
                .columns(1)
                .paragraph(CLOSING)
                .write(file);
    }

    /** All page texts as one string with single spaces. */
    static String flatText(List<PageText> pages) {
        return flat(pages.stream().map(PageText::text).collect(Collectors.joining(" ")));
    }

    static String flat(String text) {
        return text.replaceAll("\\s+", " ").strip();
    }
}
