package edu.learnsphere.pdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A block of lines (a heading, a paragraph or a list item) in the layout frame. Grows while a page is laid
 * out; treat it as read-only afterwards.
 */
final class LayoutBlock {

    private final List<TextLine> lines = new ArrayList<>();
    private float left = Float.MAX_VALUE;
    private float right = -Float.MAX_VALUE;
    private float top = Float.MAX_VALUE;
    private float bottom = -Float.MAX_VALUE;
    private float minLeading = Float.MAX_VALUE;

    LayoutBlock(TextLine first) {
        add(first);
    }

    LayoutBlock(List<TextLine> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("A block needs a line");
        }
        lines.forEach(this::add);
    }

    void add(TextLine line) {
        if (!lines.isEmpty()) {
            float step = line.baseline - lastLine().baseline;
            if (step > 0) {
                minLeading = Math.min(minLeading, step);
            }
        }
        lines.add(line);
        left = Math.min(left, line.left);
        right = Math.max(right, line.right);
        top = Math.min(top, line.top);
        bottom = Math.max(bottom, line.bottom);
    }

    List<TextLine> lines() {
        return Collections.unmodifiableList(lines);
    }

    TextLine firstLine() {
        return lines.getFirst();
    }

    TextLine lastLine() {
        return lines.getLast();
    }

    int lineCount() {
        return lines.size();
    }

    float left() {
        return left;
    }

    float right() {
        return right;
    }

    float top() {
        return top;
    }

    float bottom() {
        return bottom;
    }

    float width() {
        return right - left;
    }

    /** Smallest baseline-to-baseline distance between consecutive lines, or 0 for a one-line block. */
    float leading() {
        return minLeading == Float.MAX_VALUE ? 0 : minLeading;
    }

    /** The most common font size, weighted by characters. */
    float size() {
        SizeHistogram sizes = new SizeHistogram();
        for (TextLine line : lines) {
            for (Glyph glyph : line.glyphs) {
                if (!glyph.space) {
                    sizes.add(glyph.size, Math.max(1, glyph.charCount()));
                }
            }
        }
        return sizes.mode();
    }

    /** True when at least half of the characters are bold. */
    boolean bold() {
        int chars = 0;
        int bold = 0;
        for (TextLine line : lines) {
            for (Glyph glyph : line.glyphs) {
                int count = glyph.charCount();
                chars += count;
                if (glyph.bold) {
                    bold += count;
                }
            }
        }
        return chars > 0 && bold * 2 >= chars;
    }

    int charCount() {
        int count = 0;
        for (TextLine line : lines) {
            count += line.charCount;
        }
        return count;
    }

    @Override
    public String toString() {
        return "LayoutBlock[" + lines.size() + " lines @" + left + "," + top + "-" + right + "," + bottom + ": "
                + firstLine().text() + "]";
    }
}
