package edu.learnsphere.app.ui.components.charts;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.DoubleFunction;
import java.util.stream.Collectors;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * A line over time with a soft area beneath it, a point for each value and the latest value written beside
 * the last point. Used for the retention trend. A few quiet guide lines carry their values on the left; an
 * optional reference line (a goal, say) is dashed and labelled. Point labels sit under the axis and thin
 * out when they would collide; the first and last always show. One value shows as a single labelled point.
 * {@link #sparkline()} makes a bare version for tiles: just the line, the area and the last point.
 *
 * <p>Points are spaced evenly, so each one stands for a step of time (a week, say). A point whose value is
 * {@link Double#NaN} is a gap, a step without a value: it keeps its place and label on the axis and can be
 * inspected, while the line and the area break around it instead of joining its neighbours.
 */
public final class LineChart extends CanvasChart {

    /**
     * One point.
     *
     * @param label written under the axis, e.g. "12 Oct"
     * @param value the value, within the chart's range, or {@link Double#NaN} for a step without one
     * @param tip   the tooltip, or null for the label and the formatted value
     */
    public record Point(String label, double value, Tip tip) {
        public Point {
            Objects.requireNonNull(label, "label");
        }

        public Point(String label, double value) {
            this(label, value, null);
        }
    }

    static final double LEFT_SPACE = 40;
    static final double RIGHT_SPACE = 52;
    static final double TOP_SPACE = 12;
    static final double BOTTOM_SPACE = 26;
    /** How far the reference label keeps from the line and its points. */
    static final double LABEL_CLEARANCE = 3;
    private static final int GUIDE_LINES = 3;
    private static final double MIN_POINT_SPACING = 10;
    private static final double MIN_LABEL_GAP = 12;
    private static final double LABEL_HEIGHT = 14;

    private List<Point> points = List.of();
    private double min = 0;
    private double max = 1;
    private DoubleFunction<String> formatter = v -> Math.round(v * 100) + "%";
    private boolean sparkline;
    private Double reference;
    private String referenceLabel;

    public LineChart() {
        getStyleClass().add("line-chart");
    }

    /** A bare line for small spaces: no guide lines, labels or tooltips. */
    public static LineChart sparkline() {
        LineChart chart = new LineChart();
        chart.sparkline = true;
        chart.getStyleClass().add("sparkline");
        return chart;
    }

    public boolean isSparkline() {
        return sparkline;
    }

    public void setPoints(List<Point> points) {
        this.points = List.copyOf(points);
        dataChanged();
    }

    public List<Point> points() {
        return points;
    }

    /** The values at the bottom and top of the chart (default 0 to 1). */
    public void setRange(double min, double max) {
        if (!(max > min)) {
            throw new IllegalArgumentException("max must be above min");
        }
        this.min = min;
        this.max = max;
        redraw();
    }

    public double rangeMin() {
        return min;
    }

    public double rangeMax() {
        return max;
    }

    /** How values are written (default: fractions as percentages). */
    public void setFormatter(DoubleFunction<String> formatter) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        dataChanged();
    }

    /** A dashed, labelled line at {@code value}, e.g. a goal; null removes it. */
    public void setReference(Double value, String label) {
        this.reference = value;
        this.referenceLabel = label;
        redraw();
    }

    /**
     * A range for fractions (0–1) that shows {@code values} and {@code reference} with room below them: the
     * top is 100%, the bottom a round tenth at least 10 points under the lowest value, and the range spans at
     * least 30 points.
     */
    public static double[] fractionRange(List<Double> values, Double reference) {
        double low = reference == null ? 1 : reference;
        for (Double value : values) {
            if (value != null && !value.isNaN()) {
                low = Math.min(low, value);
            }
        }
        double bottom = Math.floor((Math.clamp(low, 0, 1) - 0.1) * 10 + 1e-9) / 10;
        bottom = Math.clamp(Math.min(bottom, 0.7), 0, 0.7);
        return new double[] {bottom, 1};
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + snappedBottomInset() + (sparkline ? 40 : 200);
    }

    @Override
    protected double computePrefWidth(double height) {
        return sparkline ? 120 : 480;
    }

    /** The plot area inside {@code width} × {@code height}: left, top, right, bottom. */
    private double[] plot(double width, double height) {
        return sparkline
                ? new double[] {3, 5, width - 5, height - 3}
                : new double[] {LEFT_SPACE, TOP_SPACE, width - RIGHT_SPACE, height - BOTTOM_SPACE};
    }

    /** The x of each of {@code n} points spread over {@code left}–{@code right} (one point sits in the middle). */
    static double[] xPositions(int n, double left, double right) {
        double[] xs = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? (left + right) / 2 : left + (right - left) * i / (n - 1);
        }
        return xs;
    }

    /**
     * The y of {@code value} in a plot from {@code top} to {@code bottom} showing {@code min}–{@code max}
     * ({@link Double#NaN} for a gap).
     */
    static double yOf(double value, double min, double max, double top, double bottom) {
        if (Double.isNaN(value)) {
            return Double.NaN;
        }
        double fraction = Math.clamp((value - min) / (max - min), 0, 1);
        return bottom - (bottom - top) * fraction;
    }

    /**
     * The stretches of consecutive values among {@code ys} (gaps are {@link Double#NaN}), each as its first
     * and last index: where the line is drawn without a break.
     */
    static List<int[]> runs(double[] ys) {
        List<int[]> runs = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= ys.length; i++) {
            boolean value = i < ys.length && !Double.isNaN(ys[i]);
            if (value && start < 0) {
                start = i;
            } else if (!value && start >= 0) {
                runs.add(new int[] {start, i - 1});
                start = -1;
            }
        }
        return runs;
    }

    /** The index of the last point with a value, or -1 when every point is a gap. */
    private int lastValued() {
        for (int i = points.size() - 1; i >= 0; i--) {
            if (!Double.isNaN(points.get(i).value())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Which of {@code widths.length} labels centred at {@code xs} to write so none collide: the first and last
     * always, then every other one that keeps {@link #MIN_LABEL_GAP} from its neighbours.
     */
    static boolean[] visibleLabels(double[] xs, double[] widths) {
        int n = xs.length;
        boolean[] shown = new boolean[n];
        if (n == 0) {
            return shown;
        }
        shown[0] = true;
        if (n == 1) {
            return shown;
        }
        shown[n - 1] = true;
        double lastRightEdge = xs[0] + widths[0];
        double finalLeftEdge = xs[n - 1] - widths[n - 1];
        for (int i = 1; i < n - 1; i++) {
            double left = xs[i] - widths[i] / 2;
            double right = xs[i] + widths[i] / 2;
            if (left - lastRightEdge >= MIN_LABEL_GAP && finalLeftEdge - right >= MIN_LABEL_GAP) {
                shown[i] = true;
                lastRightEdge = right;
            }
        }
        return shown;
    }

    @Override
    protected void draw(GraphicsContext g, double width, double height) {
        double[] p = plot(width, height);
        double left = p[0];
        double top = p[1];
        double right = p[2];
        double bottom = p[3];
        if (right <= left || bottom <= top) {
            return;
        }
        Font small = caption();
        int n = points.size();
        double[] xs = xPositions(n, left, right);
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            ys[i] = yOf(points.get(i).value(), min, max, top, bottom);
        }

        if (!sparkline) {
            drawGuides(g, small, left, top, right, bottom, xs, ys);
        }
        int last = lastValued();
        if (last < 0) {
            return;
        }
        double shown = reveal();
        double revealX = left + (right - left + 12) * shown;
        Color accent = accent();

        if (!sparkline && active() >= 0) {
            double x = crisp(xs[active()]);
            g.setStroke(grid());
            g.setLineWidth(1);
            g.setLineDashes();
            g.strokeLine(x, top, x, bottom);
        }

        g.save();
        g.beginPath();
        g.rect(0, 0, n == 1 ? width : revealX, height);
        g.clip();
        List<int[]> runs = runs(ys);
        boolean[] alone = new boolean[n];
        for (int[] run : runs) {
            if (run[0] == run[1]) {
                alone[run[0]] = true;
                continue;
            }
            g.setFill(new LinearGradient(0, top, 0, bottom, false, CycleMethod.NO_CYCLE,
                    new Stop(0, accent.deriveColor(0, 1, 1, sparkline ? 0.20 : 0.16)),
                    new Stop(1, accent.deriveColor(0, 1, 1, 0))));
            g.beginPath();
            g.moveTo(xs[run[0]], bottom);
            for (int i = run[0]; i <= run[1]; i++) {
                g.lineTo(xs[i], ys[i]);
            }
            g.lineTo(xs[run[1]], bottom);
            g.closePath();
            g.fill();

            g.setStroke(accent);
            g.setLineWidth(sparkline ? 1.75 : 2.25);
            g.setLineDashes();
            g.setLineJoin(StrokeLineJoin.ROUND);
            g.setLineCap(StrokeLineCap.ROUND);
            g.beginPath();
            g.moveTo(xs[run[0]], ys[run[0]]);
            for (int i = run[0] + 1; i <= run[1]; i++) {
                g.lineTo(xs[i], ys[i]);
            }
            g.stroke();
        }

        boolean markers = !sparkline && n > 1 && (right - left) / (n - 1) >= MIN_POINT_SPACING;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(ys[i])) {
                continue;
            }
            boolean latest = i == last;
            boolean hovered = i == active();
            // A value between two gaps has no line through it: its point is all there is to see.
            if (!latest && !hovered && !markers && !alone[i]) {
                continue;
            }
            double r = sparkline ? 3 : hovered ? 5 : latest ? 4.5 : 3;
            g.setFill(surface());
            g.fillOval(xs[i] - r - 2, ys[i] - r - 2, (r + 2) * 2, (r + 2) * 2);
            if (latest || hovered) {
                g.setFill(accent);
                g.fillOval(xs[i] - r, ys[i] - r, r * 2, r * 2);
            } else {
                g.setStroke(accent);
                g.setLineWidth(1.75);
                g.strokeOval(xs[i] - r, ys[i] - r, r * 2, r * 2);
            }
        }
        g.restore();

        if (!sparkline) {
            g.setGlobalAlpha(Math.clamp((shown - 0.7) / 0.3, 0, 1));
            g.setFont(sansSemibold(13));
            g.setFill(text());
            g.setTextAlign(n == 1 ? TextAlignment.CENTER : TextAlignment.LEFT);
            g.setTextBaseline(n == 1 ? VPos.BOTTOM : VPos.CENTER);
            String latestValue = formatter.apply(points.get(last).value());
            if (n == 1) {
                g.fillText(latestValue, xs[0], ys[0] - 10);
            } else {
                g.fillText(latestValue, xs[last] + 10, ys[last]);
            }
            g.setGlobalAlpha(1);

            double[] widths = new double[n];
            for (int i = 0; i < n; i++) {
                widths[i] = textWidth(points.get(i).label(), small);
            }
            boolean[] visible = visibleLabels(xs, widths);
            g.setFont(small);
            g.setTextBaseline(VPos.TOP);
            for (int i = 0; i < n; i++) {
                if (!visible[i] && i != active()) {
                    continue;
                }
                g.setFill(i == active() ? text() : textMuted());
                // The first label starts at its point and the last ends at it, so neither leaves the chart.
                g.setTextAlign(n == 1 ? TextAlignment.CENTER : i == 0 ? TextAlignment.LEFT
                        : i == n - 1 ? TextAlignment.RIGHT : TextAlignment.CENTER);
                g.fillText(points.get(i).label(), xs[i], bottom + 8);
            }
        }
    }

    private void drawGuides(GraphicsContext g, Font small, double left, double top, double right, double bottom,
                            double[] xs, double[] ys) {
        g.setLineWidth(1);
        g.setFont(small);
        g.setTextAlign(TextAlignment.LEFT);
        g.setTextBaseline(VPos.CENTER);
        for (int i = 0; i < GUIDE_LINES; i++) {
            double value = min + (max - min) * i / (GUIDE_LINES - 1);
            double y = crisp(yOf(value, min, max, top, bottom));
            g.setStroke(grid());
            g.setLineDashes();
            g.strokeLine(left, y, right, y);
            g.setFill(textMuted());
            g.fillText(formatter.apply(value), 0, y);
        }
        if (reference == null) {
            return;
        }
        double y = crisp(yOf(reference, min, max, top, bottom));
        g.setStroke(textFaint());
        g.setLineDashes(4, 4);
        g.strokeLine(left, y, right, y);
        g.setLineDashes();
        if (referenceLabel != null) {
            double w = textWidth(referenceLabel, small);
            double[] box = referenceLabelBox(w, left, top, right, bottom, y, xs, ys);
            g.setFill(textMuted());
            g.setTextAlign(TextAlignment.LEFT);
            g.setTextBaseline(VPos.TOP);
            g.fillText(referenceLabel, box[0], box[1]);
        }
    }

    /**
     * Where the reference label goes (its top-left corner): above the reference line at the right end of the
     * plot, else below it, else at the left end, else in the middle, whichever first keeps clear of every
     * point, of the line between them, and of the latest value written beside the last point. Gaps
     * ({@link Double#NaN} in {@code ys}) break the line, as drawn.
     */
    static double[] referenceLabelBox(double w, double left, double top, double right, double bottom, double y,
                                      double[] xs, double[] ys) {
        double h = LABEL_HEIGHT;
        double middle = (left + right - w) / 2;
        double[][] candidates = {
                {right - 6 - w, y - 4 - h}, {right - 6 - w, y + 4},
                {left + 6, y - 4 - h}, {left + 6, y + 4},
                {middle, y - 4 - h}, {middle, y + 4}};
        int last = -1;
        for (int i = ys.length - 1; i >= 0 && last < 0; i--) {
            last = Double.isNaN(ys[i]) ? -1 : i;
        }
        for (double[] c : candidates) {
            if (c[1] < top - 6 || c[1] + h > bottom + 2) {
                continue;
            }
            double x0 = c[0] - LABEL_CLEARANCE;
            double y0 = c[1] - LABEL_CLEARANCE;
            double x1 = c[0] + w + LABEL_CLEARANCE;
            double y1 = c[1] + h + LABEL_CLEARANCE;
            boolean clear = true;
            for (int i = 0; i < xs.length && clear; i++) {
                if (Double.isNaN(ys[i])) {
                    continue;
                }
                double reach = i == last ? 9 : 6;
                clear = xs[i] + reach < c[0] || xs[i] - reach > c[0] + w || ys[i] + reach < c[1] || ys[i] - reach > c[1] + h;
                if (clear && i + 1 < xs.length && !Double.isNaN(ys[i + 1])) {
                    clear = !segmentCrosses(xs[i], ys[i], xs[i + 1], ys[i + 1], x0, y0, x1, y1);
                }
            }
            if (clear) {
                return c;
            }
        }
        return candidates[0];
    }

    /**
     * True when the segment from {@code (ax, ay)} to {@code (bx, by)} touches the box {@code x0}–{@code x1}
     * by {@code y0}–{@code y1} (Liang–Barsky clipping).
     */
    static boolean segmentCrosses(double ax, double ay, double bx, double by,
                                  double x0, double y0, double x1, double y1) {
        double dx = bx - ax;
        double dy = by - ay;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {ax - x0, x1 - ax, ay - y0, y1 - ay};
        double enter = 0;
        double leave = 1;
        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return false;
                }
            } else {
                double t = q[i] / p[i];
                if (p[i] < 0) {
                    enter = Math.max(enter, t);
                } else {
                    leave = Math.min(leave, t);
                }
                if (enter > leave) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    protected int itemCount() {
        // Only gaps: nothing to show or inspect.
        return sparkline || lastValued() < 0 ? 0 : points.size();
    }

    @Override
    protected int itemAt(double x, double y) {
        if (sparkline || points.isEmpty()) {
            return -1;
        }
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        double height = getHeight() - snappedTopInset() - snappedBottomInset();
        double[] p = plot(width, height);
        if (x < p[0] - 16 || x > p[2] + 16 || y < 0 || y > height) {
            return -1;
        }
        double[] xs = xPositions(points.size(), p[0], p[2]);
        int nearest = 0;
        for (int i = 1; i < xs.length; i++) {
            if (Math.abs(xs[i] - x) < Math.abs(xs[nearest] - x)) {
                nearest = i;
            }
        }
        return nearest;
    }

    @Override
    protected Point2D anchorOf(int index) {
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        double height = getHeight() - snappedTopInset() - snappedBottomInset();
        double[] p = plot(width, height);
        double x = xPositions(points.size(), p[0], p[2])[index];
        double value = points.get(index).value();
        // A gap has no point: its tooltip rises from the axis.
        double y = Double.isNaN(value) ? p[3] : yOf(value, min, max, p[1], p[3]);
        return new Point2D(x, y - 6);
    }

    @Override
    protected double belowOffset(int index) {
        return 12;
    }

    @Override
    protected Tip tipOf(int index) {
        Point point = points.get(index);
        if (point.tip() != null) {
            return point.tip();
        }
        return new Tip(point.label(), Double.isNaN(point.value()) ? "" : formatter.apply(point.value()));
    }

    @Override
    protected String describe() {
        if (lastValued() < 0) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Point point : points) {
            if (!Double.isNaN(point.value())) {
                parts.add(point.label() + " " + formatter.apply(point.value()));
            } else if (point.tip() != null && !point.tip().value().isEmpty()) {
                parts.add(point.label() + " " + point.tip().value());
            } else {
                parts.add(point.label());
            }
        }
        return parts.stream().collect(Collectors.joining(", "));
    }
}
