package edu.learnsphere.app.ui.components.charts;

import java.util.List;
import java.util.Objects;
import javafx.geometry.Point2D;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.TextAlignment;

/**
 * Several weeks of study days in one row, oldest on the left and today on the right. A day with reviews is
 * a dot in the accent that grows with the number of reviews; a rest day is a small quiet dot. The current
 * streak is a soft thread joining its days, ending at today, which is ringed (dashed while it has no review
 * yet: the streak is still open). Labels under the row (week starts, "Today") thin out when they would
 * collide; the tooltip names each day and its reviews.
 */
public final class StreakStrip extends CanvasChart {

    /**
     * One day.
     *
     * @param label written under the day, or empty for none (e.g. "12 Oct" under each Monday)
     * @param count reviews done that day
     * @param today true for today (the last day)
     * @param tip   the tooltip, or null for the label and the count
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

        Tip tooltip() {
            if (tip != null) {
                return tip;
            }
            return new Tip(label.isEmpty() ? "Day" : label,
                    count == 0 ? "No reviews" : count == 1 ? "1 review" : count + " reviews");
        }
    }

    /** Height of the row of dots, above the labels. */
    static final double DOT_ROW = 36;
    static final double LABEL_SPACE = 22;
    static final double MIN_RADIUS = 3.5;
    static final double MAX_RADIUS = 8;
    static final double REST_RADIUS = 2.5;
    private static final double MIN_LABEL_GAP = 10;

    private List<Day> days = List.of();
    private int streak;

    public StreakStrip() {
        getStyleClass().add("streak-strip");
    }

    /**
     * Shows {@code days} with the current streak of {@code streakDays} days: the last studied days, ending at
     * today or, while today has no review yet, yesterday.
     */
    public void setDays(List<Day> days, int streakDays) {
        if (streakDays < 0) {
            throw new IllegalArgumentException("streakDays must not be negative");
        }
        this.days = List.copyOf(days);
        this.streak = streakDays;
        dataChanged();
    }

    public List<Day> days() {
        return days;
    }

    public int streakDays() {
        return streak;
    }

    /** The radius of a day with {@code count} reviews when the busiest day had {@code max}. */
    static double radius(int count, int max) {
        if (count <= 0 || max <= 0) {
            return REST_RADIUS;
        }
        return MIN_RADIUS + (MAX_RADIUS - MIN_RADIUS) * Math.sqrt(Math.min(1, count / (double) max));
    }

    /**
     * The days the streak thread joins, as {first, last} indexes, or null when there is no streak: the last
     * {@code streak} days, ending at the last day when it has reviews and otherwise at the one before.
     */
    static int[] streakRun(List<Day> days, int streak) {
        if (streak <= 0 || days.isEmpty()) {
            return null;
        }
        int end = days.size() - 1;
        if (days.get(end).count() == 0) {
            end--;
        }
        if (end < 0) {
            return null;
        }
        int start = Math.max(0, end - streak + 1);
        return new int[] {start, end};
    }

    @Override
    protected double computePrefHeight(double width) {
        return snappedTopInset() + snappedBottomInset() + DOT_ROW + LABEL_SPACE;
    }

    @Override
    protected double computeMinHeight(double width) {
        return computePrefHeight(width);
    }

    @Override
    protected double computePrefWidth(double height) {
        return snappedLeftInset() + snappedRightInset() + Math.max(7, days.size()) * 18;
    }

    private static double slot(double width, int n) {
        return n == 0 ? width : width / n;
    }

    @Override
    protected void draw(GraphicsContext g, double width, double height) {
        int n = days.size();
        if (n == 0) {
            return;
        }
        double slot = slot(width, n);
        double cy = DOT_ROW / 2;
        int max = days.stream().mapToInt(Day::count).max().orElse(0);
        int[] run = streakRun(days, streak);

        if (active() >= 0) {
            g.setFill(hover());
            g.fillRoundRect(slot * active() + 1, 0, slot - 2, DOT_ROW, 8, 8);
        }

        if (run != null && run[1] > run[0]) {
            double x0 = slot * run[0] + slot / 2;
            double x1 = slot * run[1] + slot / 2;
            g.setStroke(accentSoft());
            g.setLineWidth(4);
            g.setLineCap(StrokeLineCap.ROUND);
            g.strokeLine(x0, cy, x0 + (x1 - x0) * Math.clamp(reveal() * 1.15, 0, 1), cy);
        }

        for (int i = 0; i < n; i++) {
            Day day = days.get(i);
            double grown = staggered(reveal(), i, n);
            double cx = slot * i + slot / 2;
            boolean inStreak = run != null && i >= run[0] && i <= run[1];
            if (day.count() > 0) {
                double r = radius(day.count(), max) * grown;
                g.setFill(inStreak || i == active() ? accent() : accent().deriveColor(0, 1, 1, 0.55));
                g.fillOval(cx - r, cy - r, r * 2, r * 2);
            } else {
                double r = REST_RADIUS * grown;
                g.setFill(i == active() ? textFaint() : track());
                g.fillOval(cx - r, cy - r, r * 2, r * 2);
            }
            if (day.today()) {
                double r = Math.max(radius(day.count(), max), 4.5) + 4;
                g.setStroke(accent());
                g.setLineWidth(1.5);
                if (day.count() == 0) {
                    g.setLineDashes(2.5, 2.5);
                }
                g.strokeOval(cx - r, cy - r, r * 2, r * 2);
                g.setLineDashes();
            }
        }

        Font font = caption();
        double[] xs = new double[n];
        double[] widths = new double[n];
        for (int i = 0; i < n; i++) {
            xs[i] = slot * i + slot / 2;
            Day day = days.get(i);
            widths[i] = day.label().isEmpty() ? 0 : textWidth(day.label(), day.today() ? sansSemibold(12) : font);
        }
        boolean[] shown = visibleLabels(days, xs, widths, width);
        g.setTextAlign(TextAlignment.CENTER);
        g.setTextBaseline(VPos.TOP);
        for (int i = 0; i < n; i++) {
            if (!shown[i]) {
                continue;
            }
            Day day = days.get(i);
            g.setFont(day.today() ? sansSemibold(12) : font);
            g.setFill(day.today() ? accent() : textMuted());
            double half = widths[i] / 2;
            g.fillText(day.label(), Math.clamp(xs[i], half, width - half), DOT_ROW + 4);
        }
    }

    /**
     * Which labels to write: today's always, then from the right every other one that keeps clear of the
     * labels already written and of the chart's edges.
     */
    static boolean[] visibleLabels(List<Day> days, double[] xs, double[] widths, double width) {
        int n = days.size();
        boolean[] shown = new boolean[n];
        double[][] taken = new double[n][];
        int takenCount = 0;
        for (int i = n - 1; i >= 0; i--) {
            if (days.get(i).label().isEmpty()) {
                continue;
            }
            double half = widths[i] / 2;
            double left = Math.clamp(xs[i], half, width - half) - half;
            double right = left + widths[i];
            boolean clear = true;
            for (int t = 0; t < takenCount && clear; t++) {
                clear = right + MIN_LABEL_GAP <= taken[t][0] || left - MIN_LABEL_GAP >= taken[t][1];
            }
            if (clear || days.get(i).today()) {
                shown[i] = true;
                taken[takenCount++] = new double[] {left, right};
            }
        }
        return shown;
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
        return Math.min(days.size() - 1, (int) Math.floor(x / slot(width, days.size())));
    }

    @Override
    protected Point2D anchorOf(int index) {
        double width = getWidth() - snappedLeftInset() - snappedRightInset();
        double slot = slot(width, days.size());
        return new Point2D(slot * index + slot / 2, 2);
    }

    @Override
    protected double belowOffset(int index) {
        return DOT_ROW - 4;
    }

    @Override
    protected Tip tipOf(int index) {
        return days.get(index).tooltip();
    }

    @Override
    protected String describe() {
        if (days.isEmpty()) {
            return "";
        }
        long studied = days.stream().filter(d -> d.count() > 0).count();
        String text = "Reviews on " + studied + " of the last " + days.size() + " days";
        return streak > 0 ? text + ", a streak of " + streak + (streak == 1 ? " day" : " days") : text;
    }
}
