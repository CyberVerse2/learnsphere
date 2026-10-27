package edu.learnsphere.app.ui.study;

import edu.learnsphere.app.theme.Motion;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Polyline;
import javafx.util.Duration;

/** Small pieces several parts of the study session share: key caps, the key legend and the drawn check. */
final class StudyParts {

    /** Length of the check mark's outline, for drawing it in. */
    private static final double CHECK_LENGTH = 22;

    private StudyParts() {
    }

    /** The number on a number key (top row or keypad), or -1 for any other key. */
    static int digit(KeyEvent event) {
        if (!event.getCode().isDigitKey()) {
            return -1;
        }
        String text = event.getText();
        if (text != null && text.length() == 1 && Character.isDigit(text.charAt(0))) {
            return Character.digit(text.charAt(0), 10);
        }
        String name = event.getCode().getName();
        char last = name.charAt(name.length() - 1);
        return Character.isDigit(last) ? Character.digit(last, 10) : -1;
    }

    /** A key cap, e.g. "Enter" or "1". */
    static Label key(String text) {
        Label key = new Label(text);
        key.getStyleClass().addAll("medium", "study-key");
        key.setMinWidth(Region.USE_PREF_SIZE);
        return key;
    }

    /**
     * A row of shortcuts for the footer: pairs of key and what it does, e.g. {@code "1–4", "Choose",
     * "Enter", "Check"}.
     */
    static HBox legend(String... keysAndActions) {
        if (keysAndActions.length % 2 != 0) {
            throw new IllegalArgumentException("keys and actions come in pairs");
        }
        HBox row = new HBox(20);
        row.setAlignment(Pos.CENTER);
        row.getStyleClass().add("study-legend");
        for (int i = 0; i < keysAndActions.length; i += 2) {
            Label action = new Label(keysAndActions[i + 1]);
            action.getStyleClass().add("study-legend-action");
            HBox pair = new HBox(6, key(keysAndActions[i]), action);
            pair.setAlignment(Pos.CENTER_LEFT);
            row.getChildren().add(pair);
        }
        return row;
    }

    /** The overline above a question: "MULTIPLE CHOICE  ·  PROCESS STATES". */
    static Label overline(String text) {
        Label label = new Label(text.toUpperCase(Locale.ROOT));
        label.getStyleClass().addAll("text-overline", "study-overline");
        label.setMinWidth(0);
        return label;
    }

    /** A check mark that draws itself in (the correct-answer moment), in a box of {@code size}. */
    static Node drawnCheck(double size) {
        double s = size / 24;
        Polyline check = new Polyline(5 * s, 12.5 * s, 10 * s, 17.5 * s, 19 * s, 7 * s);
        check.getStyleClass().add("study-check");
        StackPane box = new StackPane(check);
        box.setMinSize(size, size);
        box.setPrefSize(size, size);
        box.setMaxSize(size, size);
        Motion.drawIn(check, CHECK_LENGTH * s, Duration.millis(320));
        return box;
    }
}
