package edu.learnsphere.pdf;

import edu.learnsphere.core.spi.NormalizedRect;
import edu.learnsphere.core.spi.PassageLocator;
import edu.learnsphere.core.util.CancellationToken;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Finds where a passage sits on a PDF page, for highlighting a citation.
 *
 * <p>The page is laid out the same way as for extraction, so its text reads in the same order as the
 * passages made from it (two-column pages column by column). Page and passage are compared in
 * {@linkplain TextNormalizer#matchForm match form} with {@link PassageMatcher}: differences in whitespace,
 * case, punctuation, accents and line-end hyphenation never matter, small differences in wording are
 * tolerated, and a passage that continues on the next or previous page is found by the part on this page.
 * The result is one rectangle per matched line, in reading order. Stateless and thread-safe.
 */
public final class PdfBoxPassageLocator implements PassageLocator {

    /** Creates a locator. */
    public PdfBoxPassageLocator() {
    }

    /**
     * {@inheritDoc}
     *
     * @throws PdfReadException when the file is missing, password-protected or damaged, or has no such page
     */
    @Override
    public List<NormalizedRect> locate(Path pdf, int pageNumber, String passage) throws IOException {
        String wanted = passage == null ? "" : TextNormalizer.matchForm(passage);
        PageLayout layout;
        try (PDDocument document = PdfDocuments.open(pdf)) {
            PdfDocuments.checkPageNumber(document, pageNumber, pdf);
            if (wanted.isEmpty()) {
                return List.of();
            }
            PageLayout[] holder = new PageLayout[1];
            new GlyphCollector(CancellationToken.NONE, page -> holder[0] = page).collect(document, pdf, pageNumber, pageNumber);
            layout = holder[0];
        }
        if (layout == null || layout.geometry() == null) {
            return List.of();
        }
        return locate(layout, wanted);
    }

    /** Rectangles for {@code wanted} (in match form) on a laid-out page. */
    static List<NormalizedRect> locate(PageLayout layout, String wanted) {
        StringBuilder text = new StringBuilder();
        List<Glyph> glyphs = new ArrayList<>();
        List<Integer> lineOfGlyph = new ArrayList<>();
        int[] glyphOfChar = new int[256];
        int line = 0;
        for (LayoutBlock block : layout.blocks()) {
            for (TextLine textLine : block.lines()) {
                for (Glyph glyph : textLine.glyphs) {
                    if (glyph.space) {
                        continue;
                    }
                    int before = text.length();
                    TextNormalizer.appendMatchForm(glyph.text, text);
                    if (text.length() > glyphOfChar.length) {
                        glyphOfChar = Arrays.copyOf(glyphOfChar, Math.max(text.length(), glyphOfChar.length * 2));
                    }
                    for (int i = before; i < text.length(); i++) {
                        glyphOfChar[i] = glyphs.size();
                    }
                    glyphs.add(glyph);
                    lineOfGlyph.add(line);
                }
                line++;
            }
        }
        PassageMatcher.Match match = PassageMatcher.find(text.toString(), wanted);
        if (match == null) {
            return List.of();
        }
        int firstGlyph = glyphOfChar[match.start()];
        int lastGlyph = glyphOfChar[match.end() - 1];
        List<NormalizedRect> rects = new ArrayList<>();
        int currentLine = -1;
        float left = 0;
        float top = 0;
        float right = 0;
        float bottom = 0;
        for (int g = firstGlyph; g <= lastGlyph; g++) {
            Glyph glyph = glyphs.get(g);
            int glyphLine = lineOfGlyph.get(g);
            if (glyphLine != currentLine) {
                addRect(layout.geometry(), currentLine, left, top, right, bottom, rects);
                currentLine = glyphLine;
                left = glyph.boxLeft;
                top = glyph.boxTop;
                right = glyph.boxRight;
                bottom = glyph.boxBottom;
            } else {
                left = Math.min(left, glyph.boxLeft);
                top = Math.min(top, glyph.boxTop);
                right = Math.max(right, glyph.boxRight);
                bottom = Math.max(bottom, glyph.boxBottom);
            }
        }
        addRect(layout.geometry(), currentLine, left, top, right, bottom, rects);
        return List.copyOf(rects);
    }

    private static void addRect(PageGeometry geometry, int line, float left, float top, float right, float bottom,
                                List<NormalizedRect> rects) {
        if (line < 0) {
            return;
        }
        NormalizedRect rect = geometry.normalize(left, top, right, bottom);
        if (rect != null) {
            rects.add(rect);
        }
    }
}
