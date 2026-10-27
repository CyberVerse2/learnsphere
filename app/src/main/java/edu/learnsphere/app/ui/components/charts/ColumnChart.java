package edu.learnsphere.app.ui.components.charts;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Vertical columns with the value written above each one and its label below, on a quiet baseline. Used
 * for the 14-day reviews forecast. When any column is highlighted (today, say), the others are drawn in the
 * soft accent so the highlighted ones stand out; otherwise all use the accent. Labels thin out when there
 * isn't room for every one.
 */
public final class ColumnChart extends CanvasChart {

    /**
     * One column.
     *
     * @param label       written under the column, e.g. "Tue" or "28"
     * @param value       its height, from 0 up
     * @param valueLabel  written above it, or null to write the value as a whole number (nothing for 0)
     * @param highlighted true to draw it in the full accent
     */
    public record Column(String label, double value, String valueLabel, boolean highlighted) {
        public Column {
            Objects.requireNonNull(label, "label");
        }

        public Column(String label, double value) {
            this(label, value, null, false);
        }

        String shownValue() {
            if (valueLabel != null) {
                return valueLabel;
            }
            return value > 0 ? Long.toString(Math.round(value)) : "";
        }
    }

    private static final double VALUE_SPACE = 22;
    private static final double LABEL_SPACE = 24;
    private static final double MAX_COLUMN_WIDTH = 28;
    private static final double MIN_LABEL_GAP = 6;

    private List<Column> columns = List.of();

    public ColumnChart() {
        getStyleClass().add("column-chart");
    }

    public void setColumns(List<Column> columns) {
        this.columns = List.copyOf(columns);
        redraw();
    }

    public List<Column> columns() {
        return columns;
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + snappedBottomInset() + 180;
    }

    @Override
    protected double computePrefWidth(double height) {
        return 480;
    }

    @Override
    protected void draw(GraphicsContext g, double width, double height) {
        if (columns.isEmpty()) {
            return;
        }
        int n = columns.size();
        double slot = width / n;
        double columnWidth = Math.min(MAX_COLUMN_WIDTH, slot * 0.58);
        double top = VALUE_SPACE;
        double baseline = height - LABEL_SPACE;
        double plotHeight = Math.max(0, baseline - top);
        double max = columns.stream().mapToDouble(Column::value).max().orElse(0);
        boolean anyHighlighted = columns.stream().anyMatch(Column::highlighted);

        Font labelFont = sans(12);
        Font valueFont = sansMedium(12);
        double widestLabel = columns.stream().mapToDouble(c -> textWidth(c.label(), labelFont)).max().orElse(0);
        int labelStep = Math.max(1, (int) Math.ceil((widestLabel + MIN_LABEL_GAP) / slot));

        g.setStroke(grid());
        g.setLineWidth(1);
        g.strokeLine(0, Math.floor(baseline) + 0.5, width, Math.floor(baseline) + 0.5);

        for (int i = 0; i < n; i++) {
            Column column = columns.get(i);
            double centerX = slot * i + slot / 2;
            double x = centerX - columnWidth / 2;
            double h = max > 0 ? plotHeight * Math.clamp(column.value() / max, 0, 1) : 0;
            if (column.value() > 0) {
                h = Math.max(h, 4);
                g.setFill(!anyHighlighted || column.highlighted() ? accent() : accentSoft());
                double radius = Math.min(6, columnWidth / 2);
                fillTopRounded(g, x, baseline - h, columnWidth, h, radius);
            }

            String value = column.shownValue();
            if (!value.isEmpty()) {
                g.setFont(valueFont);
                g.setFill(column.highlighted() ? text() : textMuted());
                g.setTextAlign(TextAlignment.CENTER);
                g.setTextBaseline(VPos.BOTTOM);
                g.fillText(value, centerX, baseline - h - 4);
            }

            boolean showLabel = column.highlighted() || i % labelStep == 0;
            if (showLabel) {
                g.setFont(column.highlighted() ? sansSemibold(12) : labelFont);
                g.setFill(column.highlighted() ? text() : textMuted());
                g.setTextAlign(TextAlignment.CENTER);
                g.setTextBaseline(VPos.TOP);
                g.fillText(column.label(), centerX, baseline + 8);
            }
        }
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
    protected String describe() {
        return columns.stream()
                .map(c -> c.label() + " " + (c.shownValue().isEmpty() ? "0" : c.shownValue()))
                .collect(Collectors.joining(", "));
    }
}
