package edu.learnsphere.pdf;

/**
 * One character shown on a page, in two coordinate frames:
 *
 * <ul>
 *   <li>the <em>layout frame</em> ({@link #x}, {@link #baseline}, {@link #width}, {@link #size}): PDFBox's
 *       direction-adjusted coordinates, in which the glyph's text runs left to right with y growing
 *       downwards. Lines, blocks and reading order are worked out here.</li>
 *   <li>the <em>display frame</em> ({@link #boxLeft} … {@link #boxBottom}): the glyph's box on the page as
 *       rendered (rotation applied, origin top-left), used for highlight rectangles and block bounds.</li>
 * </ul>
 */
final class Glyph {

    /** Share of the font size above the baseline in the synthetic glyph box. */
    static final float ASCENT = 0.78f;
    /** Share of the font size below the baseline in the synthetic glyph box. */
    static final float DESCENT = 0.22f;

    final String text;
    final boolean space;
    final float x;
    final float baseline;
    final float width;
    final float size;
    final float spaceWidth;
    final boolean bold;
    final int direction;
    final float boxLeft;
    final float boxTop;
    final float boxRight;
    final float boxBottom;

    Glyph(String text, float x, float baseline, float width, float size, float spaceWidth, boolean bold, int direction,
          float boxLeft, float boxTop, float boxRight, float boxBottom) {
        this.text = text;
        this.space = text.isBlank();
        this.x = x;
        this.baseline = baseline;
        this.width = Math.max(0, width);
        this.size = size;
        this.spaceWidth = spaceWidth;
        this.bold = bold;
        this.direction = direction;
        this.boxLeft = boxLeft;
        this.boxTop = boxTop;
        this.boxRight = boxRight;
        this.boxBottom = boxBottom;
    }

    float right() {
        return x + width;
    }

    float top() {
        return baseline - ASCENT * size;
    }

    float bottom() {
        return baseline + DESCENT * size;
    }

    /** Characters that count as text (non-whitespace). */
    int charCount() {
        if (space) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    @Override
    public String toString() {
        return "Glyph[" + text + " @" + x + "," + baseline + " size " + size + "]";
    }
}
