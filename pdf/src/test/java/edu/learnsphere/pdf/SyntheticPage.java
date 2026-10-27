package edu.learnsphere.pdf;

import java.util.ArrayList;
import java.util.List;

/** Builds the glyphs of an upright Letter page by hand, for layout tests without a PDF. */
final class SyntheticPage {

    static final PageGeometry LETTER = new PageGeometry(612, 792, 0);

    private final List<Glyph> glyphs = new ArrayList<>();

    /** Writes {@code text} with its baseline at {@code baseline}, every character half the size wide. */
    SyntheticPage text(String text, float x, float baseline, float size) {
        return text(text, x, baseline, size, false);
    }

    SyntheticPage text(String text, float x, float baseline, float size, boolean bold) {
        float width = size * 0.5f;
        float cx = x;
        for (int i = 0; i < text.length(); i++) {
            String c = String.valueOf(text.charAt(i));
            glyphs.add(new Glyph(c, cx, baseline, width, size, width, bold, 0,
                    cx, baseline - Glyph.ASCENT * size, cx + width, baseline + Glyph.DESCENT * size));
            cx += width;
        }
        return this;
    }

    List<Glyph> glyphs() {
        return List.copyOf(glyphs);
    }

    PageLayout layout(int pageNumber) {
        return LayoutAnalyzer.analyze(pageNumber, LETTER, glyphs);
    }

    /** Text of every block of a layout, in reading order. */
    static List<String> blockTexts(PageLayout layout) {
        List<String> texts = new ArrayList<>();
        for (LayoutBlock block : layout.blocks()) {
            StringBuilder text = new StringBuilder();
            for (TextLine line : block.lines()) {
                if (!text.isEmpty()) {
                    text.append(" | ");
                }
                text.append(line.text());
            }
            texts.add(text.toString());
        }
        return texts;
    }
}
