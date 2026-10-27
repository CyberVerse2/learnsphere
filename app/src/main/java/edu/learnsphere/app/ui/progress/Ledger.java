package edu.learnsphere.app.ui.progress;

import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.ProgressRing;
import edu.learnsphere.app.ui.components.StatTile;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.app.ui.components.charts.CanvasChart.Tip;
import edu.learnsphere.app.ui.components.charts.StreakStrip;
import edu.learnsphere.core.service.CourseProgress;
import edu.learnsphere.core.service.DayActivity;
import java.time.DayOfWeek;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The top of the Progress screen: the four numbers worth knowing set big in one card, divided by hairlines
 * (streak, reviews, accuracy and time in the overview; course progress instead of the streak for one course),
 * and in the overview the last five weeks of study days with the streak drawn through them.
 */
final class Ledger {

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter WEEKDAY_DAY_MONTH = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private Ledger() {
    }

    static Card of(ProgressData data) {
        CourseProgress p = data.progress();
        List<StatTile> tiles = new ArrayList<>();
        tiles.add(data.isOverview() ? streak(p.streakDays(), data.reviewsToday()) : courseProgress(data));
        tiles.add(reviews(p));
        tiles.add(accuracy(p));
        tiles.add(time(p));

        HBox row = new HBox();
        row.getStyleClass().add("ledger-row");
        for (int i = 0; i < tiles.size(); i++) {
            if (i > 0) {
                Region rule = new Region();
                rule.getStyleClass().add("ledger-rule");
                row.getChildren().add(rule);
            }
            StatTile tile = tiles.get(i);
            tile.framed(false);
            tile.getStyleClass().add("ledger-cell");
            if (i == 0) {
                tile.getStyleClass().add("first");
            }
            // Equal starting widths, so the cells share the row evenly.
            tile.setPrefWidth(200);
            tile.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(tile, Priority.ALWAYS);
            row.getChildren().add(tile);
        }

        Card card = new Card(row);
        card.setInteractive(false);
        card.getStyleClass().addAll("panel", "progress-ledger");
        if (data.isOverview() && !data.days().isEmpty()) {
            Region divider = new Region();
            divider.getStyleClass().add("ledger-divider");
            card.getChildren().addAll(divider, studyDays(data.days(), p.streakDays()));
        }
        return card;
    }

    private static StatTile streak(int days, int reviewsToday) {
        StatTile tile = new StatTile("Streak", days).unit(days == 1 ? "day" : "days");
        if (reviewsToday > 0) {
            tile.detail("You've studied today", Tone.ACCENT);
        } else if (days > 0) {
            tile.detail("Study today to keep it going", Tone.ACCENT);
        } else {
            tile.detail("Study today to start one", Tone.NEUTRAL);
        }
        tile.countTo(days);
        return tile;
    }

    private static StatTile courseProgress(ProgressData data) {
        CourseProgress p = data.progress();
        StatTile tile = new StatTile("Course progress", Formats.percent(p.progress()));
        tile.format(v -> Math.round(v) + "%");
        tile.countTo(Math.round(Math.clamp(p.progress(), 0, 1) * 100));
        ProgressRing ring = new ProgressRing(52, 5);
        ring.setShowPercent(false);
        ring.setProgress(p.progress());
        tile.graphic(ring);
        int[] counts = data.startedOfTotal();
        tile.detail(counts[1] == 0 ? "No questions to study yet"
                : Formats.number(counts[0]) + " of " + Formats.count(counts[1], "question", "questions") + " started",
                Tone.NEUTRAL);
        return tile;
    }

    private static StatTile reviews(CourseProgress p) {
        StatTile tile = new StatTile("Reviews", p.totalReviews())
                .detail(p.totalReviews() == 1 ? "Answer given so far" : "Answers given so far", Tone.NEUTRAL);
        tile.countTo(p.totalReviews());
        return tile;
    }

    private static StatTile accuracy(CourseProgress p) {
        if (p.accuracy() == null) {
            return new StatTile("Accuracy", "–").detail("Answer a question to see it", Tone.NEUTRAL);
        }
        StatTile tile = new StatTile("Accuracy", Formats.percent(p.accuracy())).detail("Answered correctly", Tone.NEUTRAL);
        tile.format(v -> Math.round(v) + "%");
        tile.countTo(Math.round(Math.clamp(p.accuracy(), 0, 1) * 100));
        return tile;
    }

    private static StatTile time(CourseProgress p) {
        long ms = p.studyTimeMs();
        long minutes = Math.round(ms / 60_000.0);
        StatTile tile;
        if (minutes < 60) {
            tile = new StatTile("Time studied", minutes).unit("min");
            tile.countTo(minutes);
        } else {
            tile = new StatTile("Time studied", Formats.hours(ms)).unit("h");
        }
        tile.detail(pace(ms, p.totalReviews()), Tone.NEUTRAL);
        return tile;
    }

    /** "About 25 seconds an answer", from the time spent answering and the answers given. */
    static String pace(long studyTimeMs, int answers) {
        if (answers <= 0 || studyTimeMs <= 0) {
            return "Time spent answering";
        }
        long seconds = Math.round(studyTimeMs / 1000.0 / answers);
        if (seconds < 90) {
            return "About " + Math.max(1, seconds) + (seconds == 1 ? " second" : " seconds") + " an answer";
        }
        long minutes = Math.round(seconds / 60.0);
        return "About " + minutes + " minutes an answer";
    }

    private static VBox studyDays(List<DayActivity> days, int streak) {
        Label title = new Label("Study days");
        title.getStyleClass().add("text-heading");
        long studied = days.stream().filter(d -> d.reviews() > 0).count();
        Label summary = new Label("Reviews on " + studied + " of the last " + days.size() + " days");
        summary.getStyleClass().add("text-caption");
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox head = new HBox(12, title, gap, summary);
        head.setAlignment(Pos.BASELINE_LEFT);

        StreakStrip strip = new StreakStrip();
        strip.setTitle("Study days");
        strip.setDays(days.stream().map(Ledger::day).toList(), streak);
        strip.setMaxWidth(Double.MAX_VALUE);
        VBox box = new VBox(12, head, strip);
        box.getStyleClass().add("study-days");
        return box;
    }

    private static StreakStrip.Day day(DayActivity day) {
        String label = day.today() ? "Today" : day.date().getDayOfWeek() == DayOfWeek.MONDAY ? day.date().format(DAY_MONTH) : "";
        String when = day.today() ? "Today" : day.date().format(WEEKDAY_DAY_MONTH);
        String value = day.reviews() == 0 ? "No reviews" : Formats.count(day.reviews(), "review", "reviews");
        return new StreakStrip.Day(label, day.reviews(), day.today(), new Tip(when, value));
    }
}
