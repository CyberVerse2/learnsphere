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
 * Vertical columns with the value written above each one and its label below, on a quiet baseline. Used
 * for the 14-day reviews forecast. When any column is highlighted (today, say), the others are drawn in the
 * soft accent so the highlighted ones stand out; otherwise all use the accent. A column of zero leaves a
 * small stub on the baseline, so an empty day still has its place. Labels, and then values, thin out when
 * there isn't room for every one; the tooltip always has the exact value.
 */
public final class ColumnChart extends CanvasChart {

    /**
     * One column.
     *
     * @param label       written under the column, e.g. "Tue" or "28"
     * @param value       its height, from 0 up
     * @param valueLabel  written above it, or null to write the value as a whole number (nothing for 0)
     * @param highlighted true to draw it in the full accent
     * @param tip         the tooltip, or null for the label and the value
     */
    public record Column(String label, double value, String valueLabel, boolean highlighted, Tip tip) {
        public Column {
            Objects.requireNonNull(label, "label");
        }

        public Column(String label, double value, String valueLabel, boolean highlighted) {
            this(label, value, valueLabel, highlighted, null);
        }

        public Column(String label, double value) {
            this(label, value, null, false, null);
        }

        String shownValue() {
            if (valueLabel != null) {
                return valueLabel;
            }
            return value > 0 ? Long.toString(Math.round(value)) : "";
        }

        Tip tooltip() {
            if (tip != null) {
                return tip;
            }
            String shown = shownValue();
            return new Tip(label, shown.isEmpty() ? "0" : shown);
        }
    }

    /** Room above the tallest column for its value. */
    static final double VALUE_SPACE = 22;
    /** Room under the baseline for the labels. */
    static final double LABEL_SPACE = 24;
    private static final double MAX_COLUMN_WIDTH = 28;
    private static final double MIN_LABEL_GAP = 6;
    private static final double EMPTY_STUB = 3;

    private List<Column> columns = List.of();

    public ColumnChart() {
        getStyleClass().add("column-chart");
    }

    public void setColumns(List<Column> columns) {
        this.columns = List.copyOf(columns);
        dataChanged();
    }

    public List<Column> columns() {
        return columns;
    }

    /** Every how many columns a label fits, when the widest label is {@code widest} pixels and a slot {@code slot}. */
    static int labelStep(double widest, double slot) {
        if (slot <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(1, (int) Math.ceil((widest + MIN_LABEL_GAP) / slot));
    }

    /** The height of a column of {@code value} when the tallest is {@code max} and the plot {@code plot} tall. */
    static double columnHeight(double value, double max, double plot) {
        if (!(max > 0) || !(value > 0)) {
            return 0;
        }
        return plot * Math.clamp(value / max, 0, 1);
    }

    /** The width of each column in a slot of {@code slot} pixels. */
    static double columnWidth(double slot) {
        return slot >= 8 ? Math.min(MAX_COLUMN_WIDTH, Math.round(slot * 0.58)) : Math.max(1, slot - 1);
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + snappedBottomInset() + 180;
    }

    @Override
    protected double computePrefWidth(double height) {
        return 480;
    }

    private double slot(double width) {
        return columns.isEmpty() ? width : width / columns.size();
    }

    private double max() {
        return columns.stream().mapToDouble(Column::value).max().orElse(0);
    }

    @Override
    protected void draw(GraphicsContext g, double width, double height) {
        double baseline = height - LABEL_SPACE;
        g.setStroke(grid());
        g.setLineWidth(1);
        g.strokeLine(0, crisp(baseline), width, crisp(baseline));
        if (columns.isEmpty()) {
            return;
        }
        int n = columns.size();
        double slot = slot(width);
        double columnWidth = columnWidth(slot);
        double plotHeight = Math.max(0, baseline - VALUE_SPACE);
        double max = max();
        boolean anyHighlighted = columns.stream().anyMatch(Column::highlighted);

        Font labelFont = caption();
        Font valueFont = caption();
        double widestLabel = columns.stream().mapToDouble(c -> textWidth(c.label(), labelFont)).max().orElse(0);
        int labelStep = labelStep(widestLabel, slot);
        double widestValue = columns.stream().mapToDouble(c -> textWidth(c.shownValue(), valueFont)).max().orElse(0);
        boolean everyValue = widestValue + 2 <= slot;

        for (int i = 0; i < n; i++) {
            Column column = columns.get(i);
            double grown = staggered(reveal(), i, n);
            double centerX = slot * i + slot / 2;
            double x = Math.round(centerX - columnWidth / 2);
            boolean strong = !anyHighlighted || column.highlighted() || i == active();

            if (i == active()) {
                g.setFill(hover());
                g.fillRoundRect(slot * i + 1, 0, slot - 2, baseline, 8, 8);
            }

            double h = columnHeight(column.value(), max, plotHeight);
            if (h > 0) {
                h = Math.max(h, 4) * grown;
                g.setFill(strong ? accent() : accentSoft());
                fillTopRounded(g, x, baseline - h, columnWidth, h, Math.min(6, columnWidth / 2));
            } else {
                g.setFill(track());
                fillTopRounded(g, x, baseline - EMPTY_STUB, columnWidth, EMPTY_STUB, Math.min(1.5, columnWidth / 2));
            }

            String value = column.shownValue();
            if (!value.isEmpty() && (everyValue || column.highlighted() || i == active())) {
                g.setGlobalAlpha(Math.clamp(grown * 1.5, 0, 1));
                g.setFont(valueFont);
                g.setFill(column.highlighted() || i == active() ? text() : textMuted());
                g.setTextAlign(TextAlignment.CENTER);
                g.setTextBaseline(VPos.BOTTOM);
                g.fillText(value, inside(centerX, textWidth(value, valueFont), width), baseline - h - 4);
                g.setGlobalAlpha(1);
            }

            boolean showLabel = column.highlighted() || i % labelStep == 0 && !crowdsHighlight(i, labelStep);
            if (showLabel) {
                Font font = column.highlighted() ? sansSemibold(12) : labelFont;
                g.setFont(font);
                g.setFill(column.highlighted() ? text() : textMuted());
                g.setTextAlign(TextAlignment.CENTER);
                g.setTextBaseline(VPos.TOP);
                g.fillText(column.label(), inside(centerX, textWidth(column.label(), font), width), baseline + 8);
            }
        }
    }

    /**
     * Where to centre text {@code textWidth} wide over a column centred at {@code centerX}, so it stays inside
     * a chart {@code width} wide (the first and last labels shift inwards rather than being cut off).
     */
    static double inside(double centerX, double textWidth, double width) {
        double half = textWidth / 2;
        if (width <= textWidth) {
            return width / 2;
        }
        return Math.clamp(centerX, half, width - half);
    }

    /** True when a label at {@code index} would collide with a highlighted column's label next to it. */
    private boolean crowdsHighlight(int index, int step) {
        if (step <= 1) {
            return false;
        }
        for (int j = Math.max(0, index - step + 1); j < Math.min(columns.size(), index + step); j++) {
            if (j != index && columns.get(j).highlighted()) {
                return true;
            }
        }
        return false;
    }

    private static void fillTopRounded(GraphicsContext g, double x, double y, double w, double h, double r) {
        double radius = Math.min(r, h);
        g.beginPath();
        g.moveTo(x, y + h);
        g.lineTo(x, y + radius);
        g.arcTo(x, y, x + radius, y, radius);
        g.lineTo(x + w - radius, y);
        g.arcTo(x + w, y, x + w, y + radius, radius);
        g.lineTo(x + w, y + h);
        g.closePath();
        g.fill();
    }

    @Override
    protected int itemCount() {
        return columns.size();
    }

    @Override
    protected int itemAt(double x, double y) {
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        if (columns.isEmpty() || x < 0 || x >= width || y < 0) {
            return -1;
        }
        return Math.min(columns.size() - 1, (int) Math.floor(x / slot(width)));
    }

    @Override
    protected Point2D anchorOf(int index) {
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        double height = getHeight() - snappedTopInset() - snappedBottomInset();
        double baseline = height - LABEL_SPACE;
        double slot = slot(width);
        double h = Math.max(EMPTY_STUB, columnHeight(columns.get(index).value(), max(), Math.max(0, baseline - VALUE_SPACE)));
        // Above the value written over the column.
        return new Point2D(slot * index + slot / 2, baseline - h - 20);
    }

    @Override
    protected Tip tipOf(int index) {
        return columns.get(index).tooltip();
    }

    @Override
    protected String describe() {
        return columns.stream().map(c -> c.tooltip().title() + " " + c.tooltip().value())
                .collect(Collectors.joining(", "));
    }
}
