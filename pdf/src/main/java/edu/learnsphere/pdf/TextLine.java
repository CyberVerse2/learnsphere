package edu.learnsphere.pdf;

import java.util.ArrayList;
import java.util.List;

/** A line of text: glyphs sharing a baseline, left to right, in the layout frame. Immutable. */
final class TextLine {

    final List<Glyph> glyphs;
    final float left;
    final float right;
    final float top;
    final float bottom;
    final float baseline;
    final float size;
    final boolean bold;
    final int charCount;
    private String text;

    /** @param glyphs at least one non-space glyph, sorted by x */
    TextLine(List<Glyph> glyphs) {
        this.glyphs = List.copyOf(glyphs);
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minTop = Float.MAX_VALUE;
        float maxBottom = -Float.MAX_VALUE;
        int chars = 0;
        int boldChars = 0;
        SizeHistogram sizes = new SizeHistogram();
        for (Glyph glyph : this.glyphs) {
            if (glyph.space) {
                continue;
            }
            int count = glyph.charCount();
            minX = Math.min(minX, glyph.x);
            maxX = Math.max(maxX, glyph.right());
            minTop = Math.min(minTop, glyph.top());
            maxBottom = Math.max(maxBottom, glyph.bottom());
            chars += count;
            if (glyph.bold) {
                boldChars += count;
            }
            sizes.add(glyph.size, Math.max(1, count));
        }
        if (minX == Float.MAX_VALUE) {
            throw new IllegalArgumentException("A line needs a visible glyph");
        }
        this.left = minX;
        this.right = maxX;
        this.top = minTop;
        this.bottom = maxBottom;
        this.charCount = chars;
        this.bold = chars > 0 && boldChars * 2 >= chars;
        this.size = sizes.mode();
        this.baseline = dominantBaseline(this.glyphs, this.size);
    }

    float width() {
        return right - left;
    }

    /** The line's text with spaces between words; soft hyphens inside the line are dropped. */
    String text() {
        String result = text;
        if (result == null) {
            result = buildText(glyphs);
            text = result;
        }
        return result;
    }

    /** A line made of the glyphs of {@code a} and {@code b}. */
    static TextLine merge(TextLine a, TextLine b) {
        List<Glyph> all = new ArrayList<>(a.glyphs.size() + b.glyphs.size());
        all.addAll(a.glyphs);
        all.addAll(b.glyphs);
        all.sort((g1, g2) -> Float.compare(g1.x, g2.x));
        return new TextLine(all);
    }

    private static float dominantBaseline(List<Glyph> glyphs, float size) {
        List<Float> baselines = new ArrayList<>();
        for (Glyph glyph : glyphs) {
            if (!glyph.space && Math.abs(glyph.size - size) <= Math.max(0.6f, 0.1f * size)) {
                baselines.add(glyph.baseline);
            }
        }
        if (baselines.isEmpty()) {
            for (Glyph glyph : glyphs) {
                if (!glyph.space) {
                    baselines.add(glyph.baseline);
                }
            }
        }
        baselines.sort(null);
        return baselines.get(baselines.size() / 2);
    }

    private static String buildText(List<Glyph> glyphs) {
        StringBuilder out = new StringBuilder();
        Glyph previous = null;
        float previousRight = 0;
        boolean pendingSpace = false;
        for (Glyph glyph : glyphs) {
            if (glyph.space) {
                pendingSpace = previous != null;
                continue;
            }
            if (previous != null) {
                float gap = glyph.x - previousRight;
                if ((pendingSpace || gap > wordGap(previous, glyph)) && out.charAt(out.length() - 1) != ' ') {
                    out.append(' ');
                }
            }
            out.append(glyph.text);
            previousRight = previous == null ? glyph.right() : Math.max(previousRight, glyph.right());
            previous = glyph;
            pendingSpace = false;
        }
        return tidy(out);
    }

    /** Smallest gap between two glyphs that separates words (PDFBox's text stripper uses the same rule). */
    private static float wordGap(Glyph previous, Glyph next) {
        float size = Math.max(previous.size, next.size);
        float space = previous.spaceWidth > 0 ? previous.spaceWidth : next.spaceWidth > 0 ? next.spaceWidth : 0.25f * size;
        float averageChar = (averageCharWidth(previous) + averageCharWidth(next)) / 2;
        float gap = averageChar > 0 ? Math.min(0.5f * space, 0.3f * averageChar) : 0.5f * space;
        return Math.max(gap, 0.1f * size);
    }

    private static float averageCharWidth(Glyph glyph) {
        return glyph.width / Math.max(1, glyph.text.length());
    }

    /** Collapses whitespace runs and drops soft hyphens that are not at the end of the line. */
    private static String tidy(CharSequence raw) {
        StringBuilder out = new StringBuilder(raw.length());
        boolean space = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                space = out.length() > 0;
                continue;
            }
            if (c == TextNormalizer.SOFT_HYPHEN && !isLastVisible(raw, i)) {
                continue;
            }
            if (space) {
                out.append(' ');
                space = false;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static boolean isLastVisible(CharSequence raw, int index) {
        for (int i = index + 1; i < raw.length(); i++) {
            if (!Character.isWhitespace(raw.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String toString() {
        return "TextLine[" + text() + " @" + left + "," + baseline + " size " + size + (bold ? " bold" : "") + "]";
    }
}
