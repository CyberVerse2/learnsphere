package edu.learnsphere.pdf;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Orders the blocks of a page for reading with a recursive XY cut: cut the page into horizontal bands where
 * no block crosses, then cut each band into columns where no block crosses, and repeat inside each part.
 *
 * <p>A plain XY cut reads a two-column page wrongly whenever both columns happen to have a paragraph break at
 * the same height: it cuts across the page there and reads the top halves of both columns before the bottom
 * halves. So a band laid out as columns of text (paragraphs of two or more lines on both sides of a gutter)
 * pulls in the neighbouring bands that leave the same gutter free, and the whole run is cut into columns
 * first. A full-width block (a title, a wide figure caption) ends the run. Bands made only of one-line blocks,
 * such as the rows of a table with wide text cells set apart by more than the line spacing, start no run and
 * read row by row. Lists and tables with a narrow column never reach this point as columns:
 * {@link LayoutAnalyzer} keeps each of their rows in one line.
 */
final class ReadingOrder {

    /** Blocks closer than this (in points) still count as separated. */
    private static final float TOLERANCE = 0.5f;
    /** Narrowest gutter (in points) that still separates columns. */
    private static final float MIN_GUTTER = 1f;
    private static final Comparator<LayoutBlock> BY_TOP_THEN_LEFT =
            Comparator.comparingDouble(LayoutBlock::top).thenComparingDouble(LayoutBlock::left);
    private static final Comparator<LayoutBlock> BY_LEFT_THEN_TOP =
            Comparator.comparingDouble(LayoutBlock::left).thenComparingDouble(LayoutBlock::top);

    private ReadingOrder() {
    }

    static List<LayoutBlock> order(List<LayoutBlock> blocks) {
        List<LayoutBlock> out = new ArrayList<>(blocks.size());
        order(blocks, out);
        return out;
    }

    private static void order(List<LayoutBlock> blocks, List<LayoutBlock> out) {
        if (blocks.size() <= 1) {
            out.addAll(blocks);
            return;
        }
        List<List<LayoutBlock>> bands = bands(blocks);
        if (bands.size() == 1) {
            orderBand(blocks, out);
            return;
        }
        int[] runOf = columnRuns(bands);
        int i = 0;
        while (i < bands.size()) {
            if (runOf[i] < 0) {
                orderBand(bands.get(i), out);
                i++;
                continue;
            }
            int run = runOf[i];
            List<LayoutBlock> blocksInRun = new ArrayList<>();
            int bandsInRun = 0;
            while (i < bands.size() && runOf[i] == run) {
                blocksInRun.addAll(bands.get(i));
                bandsInRun++;
                i++;
            }
            if (bandsInRun == 1) {
                orderBand(blocksInRun, out);
            } else {
                orderColumns(blocksInRun, out);
            }
        }
    }

    /**
     * For each band, the run of column bands it belongs to, or -1. A run starts at a band laid out as columns
     * of text and extends up and down over bands that leave one of its gutters free.
     */
    private static int[] columnRuns(List<List<LayoutBlock>> bands) {
        int[] runOf = new int[bands.size()];
        Arrays.fill(runOf, -1);
        int runs = 0;
        for (int i = 0; i < bands.size(); i++) {
            if (runOf[i] >= 0) {
                continue;
            }
            List<Interval> gutters = textColumnGutters(bands.get(i));
            if (gutters.isEmpty()) {
                continue;
            }
            int run = runs++;
            runOf[i] = run;
            for (int j = i - 1; j >= 0 && runOf[j] < 0; j--) {
                List<Interval> free = freeParts(gutters, bands.get(j));
                if (free.isEmpty()) {
                    break;
                }
                gutters = free;
                runOf[j] = run;
            }
            for (int j = i + 1; j < bands.size(); j++) {
                List<Interval> free = freeParts(gutters, bands.get(j));
                if (free.isEmpty()) {
                    break;
                }
                gutters = free;
                runOf[j] = run;
            }
        }
        return runOf;
    }

    /** Blocks that cannot be cut horizontally: cut them into columns, or fall back to top-to-bottom order. */
    private static void orderBand(List<LayoutBlock> band, List<LayoutBlock> out) {
        if (band.size() <= 1) {
            out.addAll(band);
            return;
        }
        orderColumns(band, out);
    }

    private static void orderColumns(List<LayoutBlock> blocks, List<LayoutBlock> out) {
        List<List<LayoutBlock>> columns = columns(blocks);
        if (columns.size() == 1) {
            List<LayoutBlock> sorted = new ArrayList<>(blocks);
            sorted.sort(BY_TOP_THEN_LEFT);
            out.addAll(sorted);
            return;
        }
        for (List<LayoutBlock> column : columns) {
            order(column, out);
        }
    }

    /** Horizontal bands, top to bottom, separated by gaps no block crosses. */
    static List<List<LayoutBlock>> bands(List<LayoutBlock> blocks) {
        List<LayoutBlock> sorted = new ArrayList<>(blocks);
        sorted.sort(BY_TOP_THEN_LEFT);
        List<List<LayoutBlock>> bands = new ArrayList<>();
        List<LayoutBlock> current = new ArrayList<>();
        float bottom = -Float.MAX_VALUE;
        for (LayoutBlock block : sorted) {
            if (!current.isEmpty() && block.top() >= bottom - TOLERANCE) {
                bands.add(current);
                current = new ArrayList<>();
                bottom = -Float.MAX_VALUE;
            }
            current.add(block);
            bottom = Math.max(bottom, block.bottom());
        }
        bands.add(current);
        return bands;
    }

    /** Vertical columns, left to right, separated by gaps no block crosses. */
    static List<List<LayoutBlock>> columns(List<LayoutBlock> blocks) {
        List<LayoutBlock> sorted = new ArrayList<>(blocks);
        sorted.sort(BY_LEFT_THEN_TOP);
        List<List<LayoutBlock>> columns = new ArrayList<>();
        List<LayoutBlock> current = new ArrayList<>();
        float right = -Float.MAX_VALUE;
        for (LayoutBlock block : sorted) {
            if (!current.isEmpty() && block.left() >= right - TOLERANCE) {
                columns.add(current);
                current = new ArrayList<>();
                right = -Float.MAX_VALUE;
            }
            current.add(block);
            right = Math.max(right, block.right());
        }
        columns.add(current);
        return columns;
    }

    /** Gutters of {@code band} with a paragraph (a block of two or more lines) on both sides. */
    private static List<Interval> textColumnGutters(List<LayoutBlock> band) {
        List<List<LayoutBlock>> columns = columns(band);
        if (columns.size() < 2) {
            return List.of();
        }
        List<Interval> gutters = new ArrayList<>();
        float start = -Float.MAX_VALUE;
        for (int i = 0; i + 1 < columns.size(); i++) {
            for (LayoutBlock block : columns.get(i)) {
                start = Math.max(start, block.right());
            }
            float end = Float.MAX_VALUE;
            for (LayoutBlock block : columns.get(i + 1)) {
                end = Math.min(end, block.left());
            }
            if (hasParagraph(columns.subList(0, i + 1)) && hasParagraph(columns.subList(i + 1, columns.size()))) {
                gutters.add(new Interval(start, end));
            }
        }
        return gutters;
    }

    private static boolean hasParagraph(List<List<LayoutBlock>> columns) {
        for (List<LayoutBlock> column : columns) {
            for (LayoutBlock block : column) {
                if (block.lineCount() >= 2) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The parts of {@code gutters} that no block of {@code band} covers (each at least {@link #MIN_GUTTER} wide). */
    private static List<Interval> freeParts(List<Interval> gutters, List<LayoutBlock> band) {
        List<Interval> free = new ArrayList<>();
        for (Interval gutter : gutters) {
            float start = gutter.start;
            float end = gutter.end;
            for (LayoutBlock block : band) {
                if (block.right() <= start + TOLERANCE || block.left() >= end - TOLERANCE) {
                    continue;
                }
                // The block reaches into the gutter: keep the part on the side away from the block's centre.
                float centre = (block.left() + block.right()) / 2;
                if (centre <= (start + end) / 2) {
                    start = Math.max(start, block.right());
                } else {
                    end = Math.min(end, block.left());
                }
                if (end - start < MIN_GUTTER) {
                    break;
                }
            }
            if (end - start >= MIN_GUTTER) {
                free.add(new Interval(start, end));
            }
        }
        return free;
    }

    private record Interval(float start, float end) {
    }
}
