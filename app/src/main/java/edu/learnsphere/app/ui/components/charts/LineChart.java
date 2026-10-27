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
 */
public final class LineChart extends CanvasChart {

    /**
     * One point.
     *
     * @param label written under the axis, e.g. "12 Oct"
     * @param value the value, within the chart's range
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
    private static final int GUIDE_LINES = 3;
    private static final double MIN_POINT_SPACING = 10;
    private static final double MIN_LABEL_GAP = 12;

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

    /** The y of {@code value} in a plot from {@code top} to {@code bottom} showing {@code min}–{@code max}. */
    static double yOf(double value, double min, double max, double top, double bottom) {
        double fraction = Math.clamp((value - min) / (max - min), 0, 1);
        return bottom - (bottom - top) * fraction;
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
        if (points.isEmpty()) {
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
        if (n > 1) {
            g.setFill(new LinearGradient(0, top, 0, bottom, false, CycleMethod.NO_CYCLE,
                    new Stop(0, accent.deriveColor(0, 1, 1, sparkline ? 0.20 : 0.16)),
                    new Stop(1, accent.deriveColor(0, 1, 1, 0))));
            g.beginPath();
            g.moveTo(xs[0], bottom);
            for (int i = 0; i < n; i++) {
                g.lineTo(xs[i], ys[i]);
            }
            g.lineTo(xs[n - 1], bottom);
            g.closePath();
            g.fill();

            g.setStroke(accent);
            g.setLineWidth(sparkline ? 1.75 : 2.25);
            g.setLineDashes();
            g.setLineJoin(StrokeLineJoin.ROUND);
            g.setLineCap(StrokeLineCap.ROUND);
            g.beginPath();
            g.moveTo(xs[0], ys[0]);
            for (int i = 1; i < n; i++) {
                g.lineTo(xs[i], ys[i]);
            }
            g.stroke();
        }

        boolean markers = !sparkline && n > 1 && (right - left) / (n - 1) >= MIN_POINT_SPACING;
        for (int i = 0; i < n; i++) {
            boolean last = i == n - 1;
            boolean hovered = i == active();
            if (!last && !hovered && !markers) {
                continue;
            }
            double r = sparkline ? 3 : hovered ? 5 : last ? 4.5 : 3;
            g.setFill(surface());
            g.fillOval(xs[i] - r - 2, ys[i] - r - 2, (r + 2) * 2, (r + 2) * 2);
            if (last || hovered) {
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
            Point lastPoint = points.get(n - 1);
            if (n == 1) {
                g.fillText(formatter.apply(lastPoint.value()), xs[0], ys[0] - 10);
            } else {
                g.fillText(formatter.apply(lastPoint.value()), xs[n - 1] + 10, ys[n - 1]);
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
     * Where the reference label goes (its top-left corner): above the line at the right end of the plot,
     * else below it, else at the left end, whichever first keeps clear of every point and of the latest
     * value written beside the last point.
     */
    static double[] referenceLabelBox(double w, double left, double top, double right, double bottom, double y,
                                      double[] xs, double[] ys) {
        double h = 14;
        double[][] candidates = {
                {right - 6 - w, y - 4 - h}, {right - 6 - w, y + 4},
                {left + 6, y - 4 - h}, {left + 6, y + 4}};
        for (double[] c : candidates) {
            if (c[1] < top - 6 || c[1] + h > bottom + 2) {
                continue;
            }
            boolean clear = true;
            for (int i = 0; i < xs.length && clear; i++) {
                double reach = i == xs.length - 1 ? 9 : 6;
                clear = xs[i] + reach < c[0] || xs[i] - reach > c[0] + w || ys[i] + reach < c[1] || ys[i] - reach > c[1] + h;
            }
            if (clear) {
                return c;
            }
        }
        return candidates[0];
    }

    @Override
    protected int itemCount() {
        return sparkline ? 0 : points.size();
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
        return new Point2D(x, yOf(points.get(index).value(), min, max, p[1], p[3]) - 6);
    }

    @Override
    protected double belowOffset(int index) {
        return 12;
    }

    @Override
    protected Tip tipOf(int index) {
        Point point = points.get(index);
        return point.tip() != null ? point.tip() : new Tip(point.label(), formatter.apply(point.value()));
    }

    @Override
    protected String describe() {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < points.size(); i++) {
            Point point = points.get(i);
            parts.add(point.label() + " " + formatter.apply(point.value()));
        }
        return parts.stream().collect(Collectors.joining(", "));
    }
}
