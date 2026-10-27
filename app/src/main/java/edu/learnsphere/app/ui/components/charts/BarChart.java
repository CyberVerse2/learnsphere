package edu.learnsphere.app.ui.components.charts;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Horizontal bars with the label on the left and the value written at the end of each bar, so no legend or
 * axis is needed. Used for mastery by concept: emphasised bars (weak concepts) take the warning colour.
 * Values run from 0 to {@link #setMax(double) max} (1 by default, for fractions).
 */
public final class BarChart extends CanvasChart {

    /**
     * One bar.
     *
     * @param label      what the bar measures, e.g. a concept name
     * @param value      its size, from 0 to the chart's maximum
     * @param valueLabel the text written at the end of the bar, e.g. "82%"
     * @param emphasized true to draw it in the warning colour (needs attention)
     */
    public record Bar(String label, double value, String valueLabel, boolean emphasized) {
        public Bar {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(valueLabel, "valueLabel");
        }
    }

    /** Height of one row. */
    public static final double ROW_HEIGHT = 36;
    /** Thickness of a bar. */
    public static final double BAR_THICKNESS = 10;
    private static final double LABEL_GAP = 16;
    private static final double VALUE_GAP = 10;
    private static final double MAX_LABEL_SHARE = 0.4;

    private List<Bar> bars = List.of();
    private double max = 1;

    public BarChart() {
        getStyleClass().add("bar-chart");
    }

    /** Replaces the bars, drawn top to bottom in this order. */
    public void setBars(List<Bar> bars) {
        this.bars = List.copyOf(bars);
        requestLayout();
        redraw();
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

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + snappedBottomInset() + Math.max(1, bars.size()) * ROW_HEIGHT;
    }

    @Override
    protected double computePrefWidth(double height) {
        return 420;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void draw(GraphicsContext g, double width, double height) {
        if (bars.isEmpty()) {
            return;
        }
        Font labelFont = sans(13);
        Font valueFont = sansMedium(12);
        double labelWidth = 0;
        double valueWidth = 0;
        for (Bar bar : bars) {
            labelWidth = Math.max(labelWidth, textWidth(bar.label(), labelFont));
            valueWidth = Math.max(valueWidth, textWidth(bar.valueLabel(), valueFont));
        }
        labelWidth = Math.min(Math.ceil(labelWidth), width * MAX_LABEL_SHARE);
        double trackStart = labelWidth + LABEL_GAP;
        double trackEnd = width - Math.ceil(valueWidth) - VALUE_GAP;
        double trackLength = Math.max(0, trackEnd - trackStart);

        g.setTextBaseline(VPos.CENTER);
        for (int i = 0; i < bars.size(); i++) {
            Bar bar = bars.get(i);
            double centerY = i * ROW_HEIGHT + ROW_HEIGHT / 2;

            g.setFont(labelFont);
            g.setFill(text());
            g.setTextAlign(TextAlignment.LEFT);
            g.fillText(fit(bar.label(), labelFont, labelWidth), 0, centerY);

            double barY = centerY - BAR_THICKNESS / 2;
            g.setFill(track());
            g.fillRoundRect(trackStart, barY, trackLength, BAR_THICKNESS, BAR_THICKNESS, BAR_THICKNESS);
            double fraction = Math.clamp(bar.value() / max, 0, 1);
            double fill = trackLength * fraction;
            if (fill > 0) {
                g.setFill(bar.emphasized() ? warning() : accent());
                g.fillRoundRect(trackStart, barY, Math.max(fill, BAR_THICKNESS), BAR_THICKNESS,
                        BAR_THICKNESS, BAR_THICKNESS);
            }

            g.setFont(valueFont);
            g.setFill(bar.emphasized() ? warning() : textMuted());
            g.setTextAlign(TextAlignment.RIGHT);
            g.fillText(bar.valueLabel(), width, centerY);
        }
    }

    /** {@code value} shortened with an ellipsis to fit {@code width}. */
    static String fit(String value, Font font, double width) {
        if (textWidth(value, font) <= width) {
            return value;
        }
        String text = value;
        while (text.length() > 1 && textWidth(text + "…", font) > width) {
            text = text.substring(0, text.length() - 1);
        }
        return text.strip() + "…";
    }

    @Override
    protected String describe() {
        return bars.stream().map(bar -> bar.label() + " " + bar.valueLabel()).collect(Collectors.joining(", "));
    }
}
