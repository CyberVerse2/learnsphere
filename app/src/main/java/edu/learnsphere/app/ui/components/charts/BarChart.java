package edu.learnsphere.app.ui.components.charts;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Horizontal bars with the label on the left and the value in a right-aligned column, so no legend or axis
 * is needed. Used for mastery by concept and accuracy per question: emphasised bars (weak concepts, questions
 * most people miss) take the warning colour. Values run from 0 to {@link #setMax(double) max} (1 by default,
 * for fractions). Labels longer than the label column end in an ellipsis; the tooltip shows them in full.
 *
 * <p>An optional {@link #setMarker(double, String) marker} notches every track at one value (the 60%
 * line under which a concept counts as weak) and labels it under the last bar.
 */
public final class BarChart extends CanvasChart {

    /**
     * One bar.
     *
     * @param label      what the bar measures, e.g. a concept name
     * @param value      its size, from 0 to the chart's maximum
     * @param valueLabel the text in the value column, e.g. "82%"
     * @param emphasized true to draw it in the warning colour (needs attention)
     * @param tip        the tooltip, or null for the label and the value label
     */
    public record Bar(String label, double value, String valueLabel, boolean emphasized, Tip tip) {
        public Bar {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(valueLabel, "valueLabel");
        }

        public Bar(String label, double value, String valueLabel, boolean emphasized) {
            this(label, value, valueLabel, emphasized, null);
        }

        Tip tooltip() {
            return tip != null ? tip : new Tip(label, valueLabel);
        }
    }

    /** Height of one row. */
    public static final double ROW_HEIGHT = 36;
    /** Thickness of a bar. */
    public static final double BAR_THICKNESS = 10;
    /** Space under the last row for the marker's label. */
    static final double MARKER_LABEL_SPACE = 20;
    private static final double LABEL_GAP = 16;
    private static final double VALUE_GAP = 12;
    private static final double MAX_LABEL_SHARE = 0.4;
    private static final double MIN_VALUE_COLUMN = 32;

    private List<Bar> bars = List.of();
    private double max = 1;
    private double labelWidth = -1;
    private Double marker;
    private String markerLabel;

    public BarChart() {
        getStyleClass().add("bar-chart");
    }

    /** Replaces the bars, drawn top to bottom in this order. */
    public void setBars(List<Bar> bars) {
        this.bars = List.copyOf(bars);
        dataChanged();
    }

    public List<Bar> bars() {
        return bars;
    }

    /** The value of a full-width bar (default 1). */
    public void setMax(double max) {
        if (!(max > 0)) {
            throw new IllegalArgumentException("max must be positive");
        }
        this.max = max;
        redraw();
    }

    /**
     * Fixes the width of the label column (so several charts line up); a negative width sizes it to the
     * longest label, up to 40% of the chart.
     */
    public void setLabelWidth(double width) {
        labelWidth = width;
        redraw();
    }

    /** Notches every track at {@code value} and writes {@code label} under the last bar; null removes it. */
    public void setMarker(Double value, String label) {
        marker = value;
        markerLabel = label;
        requestLayout();
        redraw();
    }

    /** The share of the track a bar of {@code value} fills, 0 to 1. */
    static double fraction(double value, double max) {
        if (Double.isNaN(value) || max <= 0) {
            return 0;
        }
        return Math.clamp(value / max, 0, 1);
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + snappedBottomInset() + Math.max(1, bars.size()) * ROW_HEIGHT
                + (hasMarkerLabel() ? MARKER_LABEL_SPACE : 0);
    }

    @Override
    protected double computePrefWidth(double height) {
        return 420;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    private boolean hasMarkerLabel() {
        return marker != null && markerLabel != null && !markerLabel.isBlank() && !bars.isEmpty();
    }

    /** Where the parts of each row go at {@code width}. */
    private record Columns(double labelWidth, double trackStart, double trackLength, double valueRight) {
    }

    private Columns columns(double width) {
        Font labelFont = sans(13);
        Font valueFont = caption();
        double widest = 0;
        double widestValue = 0;
        for (Bar bar : bars) {
            widest = Math.max(widest, textWidth(bar.label(), labelFont));
            widestValue = Math.max(widestValue, textWidth(bar.valueLabel(), valueFont));
        }
        double label = labelWidth >= 0 ? labelWidth : Math.min(Math.ceil(widest), Math.floor(width * MAX_LABEL_SHARE));
        double valueColumn = Math.max(MIN_VALUE_COLUMN, Math.ceil(widestValue));
        double trackStart = label + LABEL_GAP;
        double trackLength = Math.max(0, width - valueColumn - VALUE_GAP - trackStart);
        return new Columns(label, trackStart, trackLength, width);
    }

    @Override
    protected void draw(GraphicsContext g, double width, double height) {
        if (bars.isEmpty()) {
            return;
        }
        Columns c = columns(width);
        Font labelFont = sans(13);
        Font valueFont = caption();
        int n = bars.size();
        g.setTextBaseline(VPos.CENTER);
        for (int i = 0; i < n; i++) {
            Bar bar = bars.get(i);
            double top = i * ROW_HEIGHT;
            double centerY = top + ROW_HEIGHT / 2;
            double grown = staggered(reveal(), i, n);

            if (i == active()) {
                g.setFill(hover());
                g.fillRoundRect(-0.5, top + 2, width + 1, ROW_HEIGHT - 4, 10, 10);
            }

            g.setFont(labelFont);
            g.setFill(text());
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(fit(bar.label(), labelFont, c.labelWidth()), 0, centerY);

            double barY = Math.round(centerY - BAR_THICKNESS / 2);
            g.setFill(track());
            g.fillRoundRect(c.trackStart(), barY, c.trackLength(), BAR_THICKNESS, BAR_THICKNESS, BAR_THICKNESS);
            double fill = c.trackLength() * fraction(bar.value(), max) * grown;
            if (fill > 0.5) {
                g.setFill(bar.emphasized() ? warning() : accent());
                g.fillRoundRect(c.trackStart(), barY, Math.max(fill, BAR_THICKNESS), BAR_THICKNESS,
                        BAR_THICKNESS, BAR_THICKNESS);
            }
            if (marker != null) {
                double x = crisp(c.trackStart() + c.trackLength() * fraction(marker, max));
                g.setStroke(textFaint());
                g.setLineWidth(1);
                g.strokeLine(x, barY - 4, x, barY + BAR_THICKNESS + 4);
            }

            g.setGlobalAlpha(Math.clamp(grown * 1.5, 0, 1));
            g.setFont(valueFont);
            g.setFill(bar.emphasized() ? warningText() : textMuted());
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(bar.valueLabel(), c.valueRight(), centerY);
            g.setGlobalAlpha(1);
        }
        if (hasMarkerLabel()) {
            double x = c.trackStart() + c.trackLength() * fraction(marker, max);
            g.setFont(caption());
            g.setFill(textMuted());
            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.TOP);
            double half = textWidth(markerLabel, caption()) / 2;
            g.fillText(markerLabel, Math.clamp(x, c.trackStart() + half, width - half), n * ROW_HEIGHT + 2);
        }
    }

    @Override
    protected int itemCount() {
        return bars.size();
    }

    @Override
    protected int itemAt(double x, double y) {
        if (x < 0 || x > getWidth() || y < 0) {
            return -1;
        }
        int row = (int) Math.floor(y / ROW_HEIGHT);
        return row < bars.size() ? row : -1;
    }

    @Override
    protected Point2D anchorOf(int index) {
        Columns c = columns(Math.max(0, getWidth() - snappedLeftInset() - snappedRightInset()));
        double end = c.trackStart() + Math.max(BAR_THICKNESS, c.trackLength() * fraction(bars.get(index).value(), max));
        double y = index * ROW_HEIGHT + ROW_HEIGHT / 2 - BAR_THICKNESS / 2;
        return new Point2D(Math.min(end, c.trackStart() + c.trackLength()), y);
    }

    @Override
    protected double belowOffset(int index) {
        return BAR_THICKNESS;
    }

    @Override
    protected Tip tipOf(int index) {
        return bars.get(index).tooltip();
    }

    @Override
    protected String describe() {
        return bars.stream().map(bar -> bar.tooltip().title() + " " + bar.tooltip().value())
                .collect(Collectors.joining(", "));
    }
}
