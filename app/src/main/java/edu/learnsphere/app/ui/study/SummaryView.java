package edu.learnsphere.app.ui.study;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.ProgressRing;
import edu.learnsphere.app.ui.components.StatTile;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.service.ConceptChange;
import edu.learnsphere.core.service.ForecastDay;
import edu.learnsphere.core.service.SessionSummary;
import edu.learnsphere.core.service.StudyNext;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The end of a session: a headline, accuracy as a ring that fills, time, questions answered, new questions and
 * the streak, the concepts that improved (before and after), what is coming up in plain words, and the way on:
 * Study more when more is ready now, Back to Home. FX thread only.
 */
final class SummaryView {

    /** Up to this many improved concepts are listed. */
    static final int MAX_IMPROVED = 5;

    /** What the summary shows besides the session's own numbers. */
    record Extras(StudyNext moreNow, List<ForecastDay> forecast, int hintsUsed) {
        Extras {
            forecast = forecast == null ? List.of() : List.copyOf(forecast);
        }

        /** Items ready to study now. */
        int ready() {
            return moreNow == null ? 0 : moreNow.total();
        }
    }

    private final VBox root = new VBox(40);
    private final ProgressRing ring = new ProgressRing(148, 10);
    private final Button primary;
    private final StatTile answered;

    /**
     * @param homeLabel what the way back is called ("Back to Home", "Back to course")
     */
    SummaryView(SessionSummary summary, Extras extras, Clock clock, Runnable studyMore, Runnable home,
                Runnable progress, String homeLabel) {
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(extras, "extras");
        root.getStyleClass().add("summary");

        Label overline = StudyParts.overline(summary.answered() < summary.itemCount() ? "Session ended"
                : "Session complete");
        Label headline = new Label(StudyText.headline(summary));
        headline.getStyleClass().addAll("text-display", "summary-headline");
        headline.setWrapText(true);
        String recapText = StudyText.recap(summary);
        if (extras.hintsUsed() > 0) {
            recapText += " You used " + Formats.count(extras.hintsUsed(), "hint", "hints") + ".";
        }
        Label recap = new Label(recapText);
        recap.getStyleClass().addAll("text-reading", "summary-recap");
        recap.setWrapText(true);
        recap.setMinHeight(Region.USE_PREF_SIZE);
        VBox head = new VBox(12, overline, headline, recap);

        // Accuracy ring beside the numbers.
        Label percent = new Label(Formats.percent(summary.accuracy()));
        percent.getStyleClass().addAll("text-numeral", "summary-percent");
        Label correct = new Label("correct");
        correct.getStyleClass().add("text-caption");
        VBox center = new VBox(-2, percent, correct);
        center.setAlignment(Pos.CENTER);
        ring.setCenter(center);
        ring.setShowPercent(false);
        ring.setAccessibleText(Formats.percent(summary.accuracy()) + " correct");
        if (summary.answered() == 0) {
            ring.getStyleClass().add("empty");
        }

        answered = new StatTile("Answered", summary.answered()).framed(false);
        StatTile time = timeTile(summary.durationMs());
        StatTile learned = new StatTile("New questions", summary.newItemsLearned()).framed(false);
        StatTile streak = new StatTile("Streak", summary.streakDays()).framed(false)
                .unit(summary.streakDays() == 1 ? "day" : "days");
        GridPane stats = new GridPane();
        stats.getStyleClass().add("summary-stats");
        stats.add(answered, 0, 0);
        stats.add(time, 1, 0);
        stats.add(learned, 0, 1);
        stats.add(streak, 1, 1);
        HBox.setHgrow(stats, Priority.ALWAYS);
        HBox results = new HBox(48, ring, stats);
        results.setAlignment(Pos.CENTER_LEFT);
        results.getStyleClass().add("summary-results");
        results.setMaxWidth(Region.USE_PREF_SIZE);

        root.getChildren().addAll(head, results);

        List<ConceptChange> improved = summary.improved().stream().limit(MAX_IMPROVED).toList();
        if (!improved.isEmpty()) {
            VBox list = new VBox(14);
            list.getStyleClass().add("improved-list");
            for (ConceptChange change : improved) {
                list.getChildren().add(improvedRow(change));
            }
            root.getChildren().add(section("What improved", list));
        }

        String coming = StudyText.comingUp(extras.forecast(), summary.nextReviewAt(), clock);
        VBox upcoming = new VBox(6);
        if (extras.ready() > 0) {
            Label ready = new Label(Formats.count(extras.ready(), "more question is", "more questions are")
                    + " ready now.");
            ready.getStyleClass().addAll("text-reading", "summary-ready");
            upcoming.getChildren().add(ready);
        }
        if (coming != null) {
            Label later = new Label(coming);
            later.getStyleClass().addAll("text-reading", "summary-coming");
            later.setWrapText(true);
            upcoming.getChildren().add(later);
        }
        if (!upcoming.getChildren().isEmpty()) {
            root.getChildren().add(section("Coming up", upcoming));
        }

        HBox actions = new HBox(12);
        actions.setAlignment(Pos.CENTER_LEFT);
        actions.getStyleClass().add("summary-actions");
        if (extras.ready() > 0) {
            primary = button("Study more", studyMore, true);
            primary.setGraphic(Icons.of(Feather.ARROW_RIGHT, 16));
            primary.setContentDisplay(ContentDisplay.RIGHT);
            actions.getChildren().addAll(primary, button(homeLabel, home, false));
        } else {
            primary = button(homeLabel, home, true);
            actions.getChildren().addAll(primary, button("See your progress", progress, false));
        }
        actions.getChildren().add(StudyParts.key("Enter"));
        root.getChildren().add(actions);
    }

    /** A length of time as a number and its unit, for a stat: "&lt;1" min, "12" min, "1.5" h. */
    record Time(String value, String unit) {
    }

    /** {@code durationMs} as a number and its unit. */
    static Time time(long durationMs) {
        long minutes = Math.round(durationMs / 60_000.0);
        if (minutes < 1) {
            return new Time("<1", "min");
        }
        if (minutes < 60) {
            return new Time(Long.toString(minutes), "min");
        }
        double hours = Math.round(minutes / 6.0) / 10.0;
        return new Time(hours == Math.rint(hours) ? Long.toString((long) hours) : Double.toString(hours), "h");
    }

    private static StatTile timeTile(long durationMs) {
        Time time = time(durationMs);
        return new StatTile("Time", time.value()).unit(time.unit()).framed(false);
    }

    private static Node section(String title, Node body) {
        VBox box = new VBox(14, StudyParts.overline(title), body);
        box.getStyleClass().add("summary-section");
        return box;
    }

    private static Node improvedRow(ConceptChange change) {
        Label name = new Label(change.name());
        name.getStyleClass().addAll("medium", "improved-name");
        name.setWrapText(true);
        name.setMinWidth(0);
        name.setPrefWidth(260);
        ProgressBarSlim bar = new ProgressBarSlim(6).tone(Tone.SUCCESS);
        bar.setPrefWidth(200);
        bar.setMinWidth(120);
        bar.setProgressNow(change.before() == null ? 0 : change.before());
        bar.setProgress(change.after());
        HBox.setHgrow(bar, Priority.ALWAYS);
        String from = change.before() == null ? "New" : Formats.percent(change.before());
        Label values = new Label(from + "  →  " + Formats.percent(change.after()));
        values.getStyleClass().addAll("medium", "improved-values");
        values.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(20, name, bar, values);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("improved-row");
        return row;
    }

    private static Button button(String text, Runnable action, boolean primary) {
        Button button = new Button(text);
        button.getStyleClass().add("large");
        if (primary) {
            button.getStyleClass().add("primary");
        }
        button.setOnAction(event -> action.run());
        return button;
    }

    Node root() {
        return root;
    }

    /** The button Enter presses. */
    Button primaryButton() {
        return primary;
    }

    /** Fills the ring and counts the numbers up, a small moment when the summary appears. */
    void play(SessionSummary summary) {
        ring.setProgressNow(0);
        ring.setProgress(summary.accuracy());
        answered.countTo(summary.answered());
        Motion.enter(root);
    }
}
