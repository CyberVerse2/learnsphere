package edu.learnsphere.app.ui.progress;

import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.app.ui.components.charts.CanvasChart.Tip;
import edu.learnsphere.app.ui.components.charts.ColumnChart;
import edu.learnsphere.app.ui.components.charts.LineChart;
import edu.learnsphere.core.service.ForecastDay;
import edu.learnsphere.core.service.RetentionPoint;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The two charts over time: reviews coming up over the next 14 days (columns, today first and highlighted)
 * and the share of reviews remembered week by week (a line with the learner's goal dashed across it, with
 * a break for each week without reviews).
 */
final class TrendSections {

    /** Height of both charts, so the two cards line up. */
    static final double CHART_HEIGHT = 196;

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY_DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter LONG_DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY = DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH);

    private TrendSections() {
    }

    // ---- coming up ----------------------------------------------------------------------------

    static Card forecast(List<ForecastDay> days) {
        int total = days.stream().mapToInt(ForecastDay::dueCount).sum();
        SectionHeader header = new SectionHeader("Coming up").subtitle(forecastSummary(days));
        ColumnChart chart = new ColumnChart();
        chart.setTitle("Reviews coming up over the next " + days.size() + " days");
        chart.getStyleClass().add("forecast-chart");
        List<ColumnChart.Column> columns = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            ForecastDay day = days.get(i);
            String label = i == 0 ? "Today" : Formats.weekdayShort(day.date());
            String when = i == 0 ? "Today" : day.date().format(WEEKDAY_DAY_MONTH);
            String value = day.dueCount() == 0 ? "Nothing due"
                    : Formats.count(day.dueCount(), "review", "reviews") + " due";
            columns.add(new ColumnChart.Column(label, day.dueCount(), day.dueCount() == 0 ? "" : Formats.number(day.dueCount()),
                    i == 0, new Tip(when, value)));
        }
        chart.setColumns(columns);
        chart.setPrefHeight(CHART_HEIGHT);
        chart.setMinHeight(CHART_HEIGHT);
        Card card = panel(header, chart);
        card.getStyleClass().add("forecast-card");
        if (total == 0) {
            card.getStyleClass().add("quiet");
        }
        return card;
    }

    /** "86 reviews in the next 14 days. The busiest day is Thursday." */
    static String forecastSummary(List<ForecastDay> days) {
        int total = days.stream().mapToInt(ForecastDay::dueCount).sum();
        if (total == 0) {
            return "Nothing due in the next " + days.size() + " days.";
        }
        String text = Formats.count(total, "review", "reviews") + " in the next " + days.size() + " days.";
        int busiest = 0;
        for (int i = 1; i < days.size(); i++) {
            if (days.get(i).dueCount() > days.get(busiest).dueCount()) {
                busiest = i;
            }
        }
        if (days.stream().filter(d -> d.dueCount() > 0).count() < 2) {
            return text;
        }
        ForecastDay day = days.get(busiest);
        String when = busiest == 0 ? null : busiest == 1 ? "tomorrow"
                : busiest < 7 ? day.date().format(WEEKDAY) : day.date().format(LONG_DAY);
        return when == null ? text + " Today is the busiest day." : text + " The busiest day is " + when + ".";
    }

    // ---- remembered ---------------------------------------------------------------------------

    /**
     * One week of the retention trend.
     *
     * @param start  its Monday
     * @param result its reviews, or null for a week without any
     */
    record Week(LocalDate start, RetentionPoint result) {
    }

    /**
     * Every week from the first one with reviews to the week of {@code today}, in order: the service lists
     * only weeks with reviews, and the chart spaces its points evenly, so the weeks in between are put back
     * as gaps to keep each point at its place in time.
     *
     * @param today the learner's today, or null to end at the last week with reviews
     */
    static List<Week> weeks(List<RetentionPoint> points, LocalDate today) {
        if (points.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, RetentionPoint> byWeek = new HashMap<>();
        LocalDate first = null;
        LocalDate last = null;
        for (RetentionPoint point : points) {
            LocalDate monday = monday(point.weekStart());
            byWeek.put(monday, point);
            first = first == null || monday.isBefore(first) ? monday : first;
            last = last == null || monday.isAfter(last) ? monday : last;
        }
        if (today != null && monday(today).isAfter(last)) {
            last = monday(today);
        }
        List<Week> weeks = new ArrayList<>();
        for (LocalDate week = first; !week.isAfter(last); week = week.plusWeeks(1)) {
            weeks.add(new Week(week, byWeek.get(week)));
        }
        return weeks;
    }

    private static LocalDate monday(LocalDate date) {
        return date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    /**
     * @param today the learner's today (the first day of the forecast), or null
     */
    static Card retention(List<RetentionPoint> points, double goal, LocalDate today) {
        SectionHeader header = new SectionHeader("Remembered")
                .subtitle("Reviews you got right, week by week. Your goal is " + Formats.percent(goal) + ".");
        if (points.isEmpty()) {
            Label none = new Label("After your first week of reviews, you'll see here how much you remember.");
            none.getStyleClass().addAll("text-muted", "trend-empty");
            none.setWrapText(true);
            VBox.setVgrow(none, Priority.ALWAYS);
            Card card = panel(header, none);
            card.getStyleClass().add("retention-card");
            return card;
        }
        LineChart chart = new LineChart();
        chart.setTitle("Reviews remembered, week by week");
        chart.getStyleClass().add("retention-chart");
        double[] range = LineChart.fractionRange(points.stream().map(RetentionPoint::retention).toList(), goal);
        chart.setRange(range[0], range[1]);
        chart.setReference(goal, "Goal " + Formats.percent(goal));
        LocalDate thisWeek = today == null ? null : monday(today);
        chart.setPoints(weeks(points, today).stream().map(week -> {
            String label = week.start().format(DAY_MONTH);
            Tip tip;
            if (week.result() == null) {
                tip = new Tip("Week of " + label, week.start().equals(thisWeek) ? "No reviews yet" : "No reviews");
                return new LineChart.Point(label, Double.NaN, tip);
            }
            RetentionPoint result = week.result();
            tip = new Tip("Week of " + label, Formats.percent(result.retention()) + " right · "
                    + Formats.count(result.reviews(), "review", "reviews"));
            return new LineChart.Point(label, result.retention(), tip);
        }).toList());
        chart.setPrefHeight(CHART_HEIGHT);
        chart.setMinHeight(CHART_HEIGHT);
        Card card = panel(header, chart);
        card.getStyleClass().add("retention-card");
        if (points.size() == 1) {
            Label note = new Label("Your trend appears after a second week of reviews.");
            note.getStyleClass().add("text-caption");
            card.getChildren().add(note);
        }
        return card;
    }

    private static Card panel(SectionHeader header, javafx.scene.Node body) {
        Card card = new Card(header, body);
        card.setInteractive(false);
        card.getStyleClass().add("panel");
        card.setSpacing(20);
        return card;
    }
}
