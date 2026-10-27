package edu.learnsphere.app.ui.home;

import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.charts.WeekStrip;
import edu.learnsphere.core.service.DashboardData;
import edu.learnsphere.core.service.DayActivity;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** The last seven days on Home: the streak, reviews per day as a strip of columns, and the week's total. */
public final class WeekCard extends Card {

    private final Label streakNumber = new Label();
    private final Label streakUnit = new Label();
    private final Label streakHint = new Label();
    private final WeekStrip strip = new WeekStrip();
    private final Label total = new Label();

    public WeekCard() {
        setInteractive(false);
        getStyleClass().add("home-week");
        Label overline = new Label("LAST 7 DAYS");
        overline.getStyleClass().addAll("text-overline", "home-week-overline");
        streakNumber.getStyleClass().addAll("text-numeral", "home-week-streak");
        streakUnit.getStyleClass().addAll("medium", "home-week-streak-unit");
        streakHint.getStyleClass().add("home-week-hint");
        streakHint.setWrapText(true);
        HBox streak = new HBox(10, streakNumber, streakUnit);
        streak.setAlignment(Pos.BASELINE_LEFT);
        total.getStyleClass().addAll("text-caption", "home-week-total");
        strip.getStyleClass().add("home-week-strip");
        VBox head = new VBox(4, overline, streak, streakHint);
        // Beside a taller Study next card, the total sits at the foot, level with that card's button.
        VBox foot = new VBox(total);
        foot.setAlignment(Pos.BOTTOM_LEFT);
        VBox.setVgrow(foot, Priority.ALWAYS);
        getChildren().setAll(head, strip, foot);
        setSpacing(18);
    }

    public void show(DashboardData data) {
        int days = data.streakDays();
        streakNumber.setText(Formats.number(days));
        streakUnit.setText(days == 1 ? "day in a row" : "days in a row");
        boolean started = days > 0;
        streakNumber.setVisible(started);
        streakNumber.setManaged(started);
        streakUnit.setVisible(started);
        streakUnit.setManaged(started);
        streakUnit.getParent().setVisible(started);
        streakUnit.getParent().setManaged(started);
        boolean studiedToday = data.reviewsToday() > 0;
        streakHint.setText(started ? (studiedToday ? null : "Study today to keep it going.")
                : "Study today to start a streak.");
        streakHint.setVisible(streakHint.getText() != null);
        streakHint.setManaged(streakHint.isVisible());

        strip.setDays(data.week().stream()
                .map(day -> new WeekStrip.Day(Formats.weekdayInitial(day.date()), day.reviews(), day.today()))
                .toList());
        int sum = data.week().stream().mapToInt(DayActivity::reviews).sum();
        total.setText(sum == 0 ? "No reviews yet"
                : Formats.count(sum, "review", "reviews") + " in total"
                + (studiedToday ? " · " + Formats.number(data.reviewsToday()) + " today" : ""));
        setAccessibleText((started ? Formats.count(days, "day", "days") + " in a row. " : "") + total.getText());
    }

    public WeekStrip strip() {
        return strip;
    }

    public Label totalLabel() {
        return total;
    }
}
