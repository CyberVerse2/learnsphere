package edu.learnsphere.app.ui.components.charts;

import java.util.List;
import java.util.Objects;
import java.util.function.DoubleFunction;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.Stop;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * A line over time with a soft area beneath it and the latest value written beside its last point. Used
 * for the retention trend. A few quiet guide lines carry their values on the left; an optional reference
 * line (a goal, say) is dashed and labelled. {@link #sparkline()} makes a bare version for tiles: just the
 * line, the area and the last point.
 */
public final class LineChart extends CanvasChart {

    /**
     * One point.
     *
     * @param label written under the first and last points, e.g. "12 Oct"
     * @param value the value, within the chart's range
     */
    public record Point(String label, double value) {
        public Point {
            Objects.requireNonNull(label, "label");
        }
    }

    private static final double LEFT_SPACE = 40;
    private static final double RIGHT_SPACE = 52;
    private static final double TOP_SPACE = 12;
    private static final double BOTTOM_SPACE = 26;
    private static final int GUIDE_LINES = 3;

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

    /** A bare line for small spaces: no guide lines or labels. */
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
        redraw();
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

    /** How values are written (default: fractions as percentages). */
    public void setFormatter(DoubleFunction<String> formatter) {
        this.formatter = Objects.requireNonNull(formatter, "formatter");
        redraw();
    }

    /** A dashed, labelled line at {@code value}, e.g. a goal; null removes it. */
    public void setReference(Double value, String label) {
        this.reference = value;
        this.referenceLabel = label;
        redraw();
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + snappedBottomInset() + (sparkline ? 40 : 200);
    }

    @Override
    protected double computePrefWidth(double height) {
        return sparkline ? 120 : 480;
    }

    @Override
    protected void draw(GraphicsContext g, double width, double height) {
        double left = sparkline ? 3 : LEFT_SPACE;
        double right = width - (sparkline ? 5 : RIGHT_SPACE);
        double top = sparkline ? 5 : TOP_SPACE;
        double bottom = height - (sparkline ? 3 : BOTTOM_SPACE);
        if (right <= left || bottom <= top) {
            return;
        }
        Font small = sans(12);

        if (!sparkline) {
            g.setLineWidth(1);
            g.setFont(small);
            g.setTextAlign(TextAlignment.LEFT);
            g.setTextBaseline(VPos.CENTER);
            for (int i = 0; i < GUIDE_LINES; i++) {
                double value = min + (max - min) * i / (GUIDE_LINES - 1);
                double y = Math.floor(yOf(value, top, bottom)) + 0.5;
                g.setStroke(grid());
                g.setLineDashes();
                g.strokeLine(left, y, right, y);
                g.setFill(textMuted());
                g.fillText(formatter.apply(value), 0, y);
            }
            if (reference != null) {
                double y = Math.floor(yOf(reference, top, bottom)) + 0.5;
                g.setStroke(textMuted());
                g.setLineDashes(4, 4);
                g.strokeLine(left, y, right, y);
                g.setLineDashes();
                if (referenceLabel != null) {
                    g.setFill(textMuted());
                    g.setTextAlign(TextAlignment.RIGHT);
                    g.setTextBaseline(VPos.BOTTOM);
                    g.fillText(referenceLabel, right, y - 4);
                }
            }
        }
        if (points.isEmpty()) {
            return;
        }

        int n = points.size();
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = n == 1 ? (left + right) / 2 : left + (right - left) * i / (n - 1);
            ys[i] = yOf(points.get(i).value(), top, bottom);
        }

        Color accent = accent();
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
        g.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        g.beginPath();
        g.moveTo(xs[0], ys[0]);
        for (int i = 1; i < n; i++) {
            g.lineTo(xs[i], ys[i]);
        }
        g.stroke();

        double lastX = xs[n - 1];
        double lastY = ys[n - 1];
        double dot = sparkline ? 3 : 4.5;
        g.setFill(surface());
        g.fillOval(lastX - dot - 2, lastY - dot - 2, (dot + 2) * 2, (dot + 2) * 2);
        g.setFill(accent);
        g.fillOval(lastX - dot, lastY - dot, dot * 2, dot * 2);

        if (!sparkline) {
            g.setFont(sansSemibold(13));
            g.setFill(text());
            g.setTextAlign(TextAlignment.LEFT);
            g.setTextBaseline(VPos.CENTER);
            g.fillText(formatter.apply(points.get(n - 1).value()), lastX + 10, lastY);

            g.setFont(small);
            g.setFill(textMuted());
            g.setTextBaseline(VPos.TOP);
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(points.getFirst().label(), xs[0], bottom + 8);
            if (n > 1) {
                g.setTextAlign(TextAlignment.RIGHT);
                g.fillText(points.get(n - 1).label(), xs[n - 1], bottom + 8);
            }
        }
    }

    private double yOf(double value, double top, double bottom) {
        double fraction = Math.clamp((value - min) / (max - min), 0, 1);
        return bottom - (bottom - top) * fraction;
    }

    @Override
    protected String describe() {
        if (points.isEmpty()) {
            return "";
        }
        Point first = points.getFirst();
        Point last = points.getLast();
        return first.label() + " " + formatter.apply(first.value()) + " to " + last.label() + " "
                + formatter.apply(last.value());
    }
}
