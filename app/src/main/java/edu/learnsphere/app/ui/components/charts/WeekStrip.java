package edu.learnsphere.app.ui.components.charts;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.text.TextAlignment;

/**
 * A small strip of the last seven days: each day is a rounded pill that fills from the bottom with the
 * reviews done that day, the count written above and the weekday initial below. Today is outlined in the
 * accent. Days with no reviews show an empty pill, so gaps in a streak are easy to see.
 *
 * <p>A day without its own {@link Day#tip() tooltip} is named from its place before today ("Today",
 * "Yesterday", "3 days ago"), as a one-letter label can't tell Tuesday from Thursday.
 */
public final class WeekStrip extends CanvasChart {

    /**
     * One day.
     *
     * @param label short day label, e.g. "M"
     * @param count reviews done that day
     * @param today true for today
     * @param tip   the tooltip, or null to name the day from its place before today
     */
    public record Day(String label, int count, boolean today, Tip tip) {
        public Day {
            Objects.requireNonNull(label, "label");
            if (count < 0) {
                throw new IllegalArgumentException("count must not be negative");
            }
        }

        public Day(String label, int count, boolean today) {
            this(label, count, today, null);
        }
    }

    /**
     * The tooltip of day {@code index} of {@code days}: its own, else its place before today, or its label
     * when no day is today.
     */
    static Tip tipOf(List<Day> days, int index) {
        Day day = days.get(index);
        if (day.tip() != null) {
            return day.tip();
        }
        String value = day.count() == 0 ? "No reviews" : day.count() == 1 ? "1 review" : day.count() + " reviews";
        int today = -1;
        for (int i = 0; i < days.size() && today < 0; i++) {
            today = days.get(i).today() ? i : -1;
        }
        int before = today - index;
        String when = today < 0 || before < 0 ? day.label()
                : before == 0 ? "Today" : before == 1 ? "Yesterday" : before + " days ago";
        return new Tip(when, value);
    }

    static final double PILL_WIDTH = 22;
    static final double PILL_HEIGHT = 44;
    static final double PILL_TOP = 20;
    private static final double MIN_FILL = 8;

    private List<Day> days = List.of();

    public WeekStrip() {
        getStyleClass().add("week-strip");
    }

    public void setDays(List<Day> days) {
        this.days = List.copyOf(days);
        dataChanged();
    }

    public List<Day> days() {
        return days;
    }

    /** How tall the fill of a day with {@code count} reviews is when the busiest day had {@code max}. */
    static double fillHeight(int count, int max, double pillWidth) {
        if (count <= 0 || max <= 0) {
            return 0;
        }
        double fill = Math.max(MIN_FILL, PILL_HEIGHT * count / (double) max);
        return Math.min(PILL_HEIGHT, Math.max(fill, pillWidth));
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + snappedBottomInset() + PILL_TOP + PILL_HEIGHT + 24;
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
        int max = days.stream().mapToInt(Day::count).max().orElse(0);
        for (int i = 0; i < n; i++) {
            Day day = days.get(i);
            double grown = staggered(reveal(), i, n);
            double centerX = slot * i + slot / 2;
            double x = Math.round(centerX - pillWidth / 2);
            g.setFill(track());
            g.fillRoundRect(x, PILL_TOP, pillWidth, PILL_HEIGHT, pillWidth, pillWidth);
            double fill = fillHeight(day.count(), max, pillWidth) * grown;
            if (fill > 0.5) {
                g.setFill(day.today() || i == active() ? accent() : accent().deriveColor(0, 1, 1, 0.72));
                g.fillRoundRect(x, PILL_TOP + PILL_HEIGHT - fill, pillWidth, fill, pillWidth, Math.min(pillWidth, fill));
            }
            if (day.today()) {
                g.setStroke(accent());
                g.setLineWidth(1.5);
                g.strokeRoundRect(x - 3, PILL_TOP - 3, pillWidth + 6, PILL_HEIGHT + 6, pillWidth + 6, pillWidth + 6);
            }

            g.setTextAlign(TextAlignment.CENTER);
            g.setTextBaseline(VPos.BOTTOM);
            g.setFont(caption());
            g.setFill(day.count() > 0 || i == active() ? text() : textMuted());
            g.fillText(Integer.toString(day.count()), centerX, PILL_TOP - 6);

            g.setTextBaseline(VPos.TOP);
            g.setFont(day.today() ? sansSemibold(12) : caption());
            g.setFill(day.today() ? accent() : textMuted());
            g.fillText(day.label(), centerX, PILL_TOP + PILL_HEIGHT + 8);
        }
    }

    @Override
    protected int itemCount() {
        return days.size();
    }

    @Override
    protected int itemAt(double x, double y) {
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        if (days.isEmpty() || x < 0 || x >= width) {
            return -1;
        }
        return Math.min(days.size() - 1, (int) Math.floor(x / (width / days.size())));
    }

    @Override
    protected Point2D anchorOf(int index) {
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        double slot = width / days.size();
        return new Point2D(slot * index + slot / 2, PILL_TOP - 20);
    }

    @Override
    protected Tip tipOf(int index) {
        return tipOf(days, index);
    }

    @Override
    protected String describe() {
        return IntStream.range(0, days.size()).mapToObj(i -> tipOf(days, i))
                .map(tip -> tip.title() + " " + tip.value()).collect(Collectors.joining(", "));
    }
}
