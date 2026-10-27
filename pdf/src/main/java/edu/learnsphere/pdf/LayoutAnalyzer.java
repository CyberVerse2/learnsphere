package edu.learnsphere.pdf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Turns the glyphs of one page into lines, blocks and reading order using geometry only, so the result does
 * not depend on the order in which the PDF happens to draw its text.
 *
 * <ol>
 *   <li><b>Lines.</b> Glyphs whose boxes overlap vertically (and whose sizes are comparable, so superscripts
 *       stay in their line but a drop cap does not swallow three lines) form a row. A row is split into
 *       separate lines only at a <em>gutter</em>: a wide gap that lines up with wide gaps in at least two
 *       other rows, as between the columns of a two-column page. Other wide gaps (justified text, tabs)
 *       stay inside the line.</li>
 *   <li><b>Blocks.</b> Lines join the block above them when they follow at the block's line spacing, overlap
 *       it horizontally and share its size and weight. A new block starts at a heading-like line, a bullet,
 *       an indented first line or a gap wider than the line spacing.</li>
 *   <li><b>Reading order.</b> See {@link ReadingOrder}.</li>
 * </ol>
 */
final class LayoutAnalyzer {

    /** Gaps wider than this many font sizes may separate columns. */
    private static final float GUTTER_GAP_EM = 0.8f;
    /** Glyphs whose sizes differ by more than this factor never share a line. */
    private static final float MAX_SIZE_RATIO = 2.2f;
    private static final Pattern BULLET = Pattern.compile(
            "^([\u2022\u25E6\u25AA\u25AB\u25CF\u25CB\u25A0\u25A1\u25B8\u25BA\u2023\u2043\u2219]|[-\u2013*]\\s|\\(?(\\d{1,2}|[a-zA-Z])[.)]\\s)");
    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\d{1,2}(\\.\\d{1,2}){0,3}\\.?\\s+\\p{Lu}");
    private static final Comparator<Glyph> BY_BASELINE_THEN_X =
            Comparator.<Glyph>comparingDouble(g -> g.baseline).thenComparingDouble(g -> g.x);
    private static final Comparator<TextLine> LINES_BY_BASELINE_THEN_LEFT =
            Comparator.<TextLine>comparingDouble(l -> l.baseline).thenComparingDouble(l -> l.left);

    private LayoutAnalyzer() {
    }

    /** Lays out a page. Glyphs may come in any order. */
    static PageLayout analyze(int pageNumber, PageGeometry geometry, List<Glyph> glyphs) {
        if (glyphs.isEmpty()) {
            return PageLayout.empty(pageNumber, geometry);
        }
        Map<Integer, List<Glyph>> byDirection = new TreeMap<>();
        Map<Integer, Integer> charsByDirection = new TreeMap<>();
        for (Glyph glyph : glyphs) {
            byDirection.computeIfAbsent(glyph.direction, d -> new ArrayList<>()).add(glyph);
            charsByDirection.merge(glyph.direction, glyph.charCount(), Integer::sum);
        }
        int main = 0;
        int mainChars = -1;
        for (Map.Entry<Integer, Integer> entry : charsByDirection.entrySet()) {
            if (entry.getValue() > mainChars) {
                main = entry.getKey();
                mainChars = entry.getValue();
            }
        }
        List<LayoutBlock> mainBlocks = layout(byDirection.get(main));
        List<LayoutBlock> otherBlocks = new ArrayList<>();
        for (Map.Entry<Integer, List<Glyph>> entry : byDirection.entrySet()) {
            if (entry.getKey() != main) {
                otherBlocks.addAll(layout(entry.getValue()));
            }
        }
        return new PageLayout(pageNumber, geometry, main, mainBlocks, otherBlocks);
    }

    /** Blocks in reading order for glyphs that all run in one direction. */
    static List<LayoutBlock> layout(List<Glyph> glyphs) {
        return ReadingOrder.order(buildBlocks(buildLines(glyphs)));
    }

    // ---------------------------------------------------------------------------------------------- lines

    static List<TextLine> buildLines(List<Glyph> glyphs) {
        List<Glyph> sorted = new ArrayList<>(glyphs);
        sorted.sort(BY_BASELINE_THEN_X);
        List<Row> rows = new ArrayList<>();
        for (Glyph glyph : sorted) {
            Row target = null;
            float best = 0.5f;
            for (int i = rows.size() - 1; i >= 0; i--) {
                Row row = rows.get(i);
                if (row.bottom < glyph.top() - 3 * Math.max(row.size, glyph.size)) {
                    break;
                }
                float ratio = row.overlapRatio(glyph);
                boolean better = target == null ? ratio >= best : ratio > best;
                if (better && sizesCompatible(row.size, glyph.size)) {
                    best = ratio;
                    target = row;
                }
            }
            if (target == null) {
                rows.add(new Row(glyph));
            } else {
                target.add(glyph);
            }
        }
        List<Gap> gaps = new ArrayList<>();
        for (Row row : rows) {
            row.findGaps(gaps);
        }
        markGutters(gaps);
        List<TextLine> lines = new ArrayList<>();
        for (Row row : rows) {
            row.split(lines);
        }
        lines.sort(LINES_BY_BASELINE_THEN_LEFT);
        return lines;
    }

    private static boolean sizesCompatible(float a, float b) {
        float small = Math.min(a, b);
        return small > 0 && Math.max(a, b) / small <= MAX_SIZE_RATIO;
    }

    /** A wide gap is a gutter when wide gaps of at least two other rows overlap it. */
    private static void markGutters(List<Gap> gaps) {
        for (Gap gap : gaps) {
            int aligned = 0;
            Row lastCounted = null;
            for (Gap other : gaps) {
                if (other.row == gap.row || other.row == lastCounted) {
                    continue;
                }
                float overlap = Math.min(gap.end, other.end) - Math.max(gap.start, other.start);
                float needed = Math.min(0.4f * gap.row.size, 0.5f * Math.min(gap.end - gap.start, other.end - other.start));
                if (overlap >= needed) {
                    aligned++;
                    lastCounted = other.row;
                    if (aligned >= 2) {
                        break;
                    }
                }
            }
            gap.gutter = aligned >= 2;
        }
    }

    /** Glyphs whose boxes overlap vertically; split into lines at gutters. */
    private static final class Row {
        final List<Glyph> glyphs = new ArrayList<>();
        final List<Gap> gaps = new ArrayList<>();
        float top;
        float bottom;
        float size;

        Row(Glyph first) {
            glyphs.add(first);
            top = first.top();
            bottom = first.bottom();
            size = first.size;
        }

        void add(Glyph glyph) {
            glyphs.add(glyph);
            if (glyph.size > size * 1.05f && !glyph.space) {
                // The row's band follows its largest text, so a superscript that started the row does not
                // decide which glyphs belong to it.
                top = glyph.top();
                bottom = glyph.bottom();
                size = glyph.size;
            }
        }

        float overlapRatio(Glyph glyph) {
            float overlap = Math.min(bottom, glyph.bottom()) - Math.max(top, glyph.top());
            float smaller = Math.min(bottom - top, glyph.bottom() - glyph.top());
            return smaller <= 0 ? 0 : overlap / smaller;
        }

        void findGaps(List<Gap> all) {
            glyphs.sort(Comparator.comparingDouble(g -> g.x));
            float maxRight = Float.NaN;
            for (int i = 0; i < glyphs.size(); i++) {
                Glyph glyph = glyphs.get(i);
                if (glyph.space) {
                    continue;
                }
                if (!Float.isNaN(maxRight) && glyph.x - maxRight > GUTTER_GAP_EM * size) {
                    Gap gap = new Gap(this, i, maxRight, glyph.x);
                    gaps.add(gap);
                    all.add(gap);
                }
                maxRight = Float.isNaN(maxRight) ? glyph.right() : Math.max(maxRight, glyph.right());
            }
        }

        void split(List<TextLine> out) {
            int start = 0;
            for (Gap gap : gaps) {
                if (gap.gutter) {
                    emit(start, gap.index, out);
                    start = gap.index;
                }
            }
            emit(start, glyphs.size(), out);
        }

        private void emit(int from, int to, List<TextLine> out) {
            for (int i = from; i < to; i++) {
                if (!glyphs.get(i).space) {
                    out.add(new TextLine(glyphs.subList(from, to)));
                    return;
                }
            }
        }
    }

    private static final class Gap {
        final Row row;
        final int index;
        final float start;
        final float end;
        boolean gutter;

        Gap(Row row, int index, float start, float end) {
            this.row = row;
            this.index = index;
            this.start = start;
            this.end = end;
        }
    }

    // --------------------------------------------------------------------------------------------- blocks

    static List<LayoutBlock> buildBlocks(List<TextLine> lines) {
        float pageLeading = typicalLeading(lines);
        List<LayoutBlock> blocks = new ArrayList<>();
        for (TextLine line : lines) {
            LayoutBlock target = null;
            if (!BULLET.matcher(line.text()).find()) {
                float best = 0;
                for (int i = blocks.size() - 1; i >= 0; i--) {
                    LayoutBlock block = blocks.get(i);
                    if (!canJoin(block, line, pageLeading)) {
                        continue;
                    }
                    float overlap = horizontalOverlap(block.left(), block.right(), line.left, line.right)
                            / Math.max(1e-3f, Math.min(block.width(), line.width()));
                    if (overlap > best) {
                        best = overlap;
                        target = block;
                    }
                }
            }
            if (target == null) {
                blocks.add(new LayoutBlock(line));
            } else {
                target.add(line);
            }
        }
        return blocks;
    }

    private static boolean canJoin(LayoutBlock block, TextLine line, float pageLeading) {
        TextLine last = block.lastLine();
        float size = Math.max(line.size, last.size);
        float step = line.baseline - last.baseline;
        if (step <= 0.3f * size) {
            return false;
        }
        if (Math.abs(line.size - last.size) > 0.15f * size || line.bold != last.bold) {
            return false;
        }
        float maxStep;
        if (block.lineCount() >= 2) {
            maxStep = block.leading() * 1.3f + 0.5f;
        } else {
            maxStep = 1.6f * size;
            if (pageLeading > maxStep && pageLeading <= 3.2f * size) {
                maxStep = pageLeading * 1.15f; // double-spaced text
            }
        }
        if (step > maxStep) {
            return false;
        }
        if (horizontalOverlap(block.left(), block.right(), line.left, line.right) <= 0) {
            return false;
        }
        if (block.lineCount() == 1 && looksLikeNumberedHeading(last, line)) {
            return false;
        }
        return !startsParagraph(block, line);
    }

    /** "3.2 Cell membranes" on its own short line above a paragraph line in the same font. */
    private static boolean looksLikeNumberedHeading(TextLine heading, TextLine next) {
        String text = heading.text();
        if (text.length() > 80 || heading.width() >= 0.75f * next.width()) {
            return false;
        }
        char end = text.charAt(text.length() - 1);
        return end != '.' && end != ',' && end != ';' && end != ':' && NUMBERED_HEADING.matcher(text).find();
    }

    /**
     * True when the block's first line is indented (or hanging) relative to its other lines and {@code line}
     * starts at the first line's position: the start of the next paragraph or list item.
     */
    private static boolean startsParagraph(LayoutBlock block, TextLine line) {
        if (block.lineCount() < 2) {
            return false;
        }
        float em = Math.max(line.size, 1);
        float firstLeft = block.firstLine().left;
        float otherLeft = block.lines().get(1).left;
        if (Math.abs(firstLeft - otherLeft) <= 0.8f * em) {
            return false;
        }
        return Math.abs(line.left - firstLeft) <= 0.5f * em && Math.abs(line.left - otherLeft) > 0.8f * em;
    }

    /** The most common distance from a line to the nearest line above it in the same column. */
    private static float typicalLeading(List<TextLine> lines) {
        SizeHistogram steps = new SizeHistogram();
        for (int i = 1; i < lines.size(); i++) {
            TextLine line = lines.get(i);
            for (int j = i - 1; j >= Math.max(0, i - 8); j--) {
                TextLine above = lines.get(j);
                float step = line.baseline - above.baseline;
                if (step <= 0.3f * line.size || Math.abs(above.size - line.size) > 0.15f * line.size
                        || horizontalOverlap(above.left, above.right, line.left, line.right) <= 0) {
                    continue;
                }
                steps.add(step, 1);
                break;
            }
        }
        return steps.mode();
    }

    static float horizontalOverlap(float left1, float right1, float left2, float right2) {
        return Math.min(right1, right2) - Math.max(left1, left2);
    }
}
