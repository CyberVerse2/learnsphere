package edu.learnsphere.app.ui.components.charts;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.TextAlignment;

/**
 * A small strip of the last seven days: each day is a rounded pill that fills from the bottom with the
 * reviews done that day, the count written above and the weekday initial below. Today is outlined in the
 * accent. Days with no reviews show an empty pill, so gaps in a streak are easy to see.
 */
public final class WeekStrip extends CanvasChart {

    /**
     * One day.
     *
     * @param label short day label, e.g. "M"
     * @param count reviews done that day
     * @param today true for today
     */
    public record Day(String label, int count, boolean today) {
        public Day {
            Objects.requireNonNull(label, "label");
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
        }
    }

    private static final double PILL_WIDTH = 22;
    private static final double PILL_HEIGHT = 44;
    private static final double MIN_FILL = 8;

    private List<Day> days = List.of();

    public WeekStrip() {
        getStyleClass().add("week-strip");
    }

    public void setDays(List<Day> days) {
        this.days = List.copyOf(days);
        redraw();
    }

    public List<Day> days() {
        return days;
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + snappedBottomInset() + 20 + PILL_HEIGHT + 24;
    }

    @Override
    protected double computePrefWidth(double height) {
        return snappedLeftInset() + snappedRightInset() + Math.max(7, days.size()) * 36;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected void draw(GraphicsContext g, double width, double height) {
        if (days.isEmpty()) {
            return;
        }
        int n = days.size();
        double slot = width / n;
        double pillWidth = Math.min(PILL_WIDTH, slot - 6);
        double pillTop = 20;
        int max = days.stream().mapToInt(Day::count).max().orElse(0);
        for (int i = 0; i < n; i++) {
            Day day = days.get(i);
            double centerX = slot * i + slot / 2;
            double x = centerX - pillWidth / 2;
            g.setFill(track());
            g.fillRoundRect(x, pillTop, pillWidth, PILL_HEIGHT, pillWidth, pillWidth);
            if (day.count() > 0 && max > 0) {
                double fill = Math.max(MIN_FILL, PILL_HEIGHT * day.count() / (double) max);
                fill = Math.max(fill, pillWidth);
                g.setFill(day.today() ? accent() : accent().deriveColor(0, 1, 1, 0.72));
                g.fillRoundRect(x, pillTop + PILL_HEIGHT - fill, pillWidth, fill, pillWidth, pillWidth);
            }
            if (day.today()) {
                g.setStroke(accent());
                g.setLineWidth(1.5);
                g.strokeRoundRect(x - 3, pillTop - 3, pillWidth + 6, PILL_HEIGHT + 6, pillWidth + 6, pillWidth + 6);
            }

            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.BOTTOM);
            g.setFont(sansMedium(12));
            g.setFill(day.count() > 0 ? text() : textMuted());
            g.fillText(Integer.toString(day.count()), centerX, pillTop - 6);

            g.setTextBaseline(VPos.TOP);
            g.setFont(day.today() ? sansSemibold(12) : sans(12));
            g.setFill(day.today() ? accent() : textMuted());
            g.fillText(day.label(), centerX, pillTop + PILL_HEIGHT + 8);
        }
    }

    @Override
    protected String describe() {
        return days.stream().map(d -> d.label() + " " + d.count()).collect(Collectors.joining(", "));
    }
}
