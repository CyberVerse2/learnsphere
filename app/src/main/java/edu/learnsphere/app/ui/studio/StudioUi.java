package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.components.Icons;
import java.util.function.UnaryOperator;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TextInputControl;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;

/** Small building blocks the Studio's tabs share: panels, labelled fields, notes and dividers. */
final class StudioUi {

    private StudioUi() {
    }

    /** A surface panel with a heading, an optional line under it, and its content. */
    static VBox panel(String title, String subtitle, Node... content) {
        VBox panel = new VBox();
        panel.getStyleClass().add("studio-panel");
        if (title != null) {
            panel.getChildren().add(heading(title, subtitle));
        }
        panel.getChildren().addAll(content);
        return panel;
    }

    /** A panel's heading and the line under it. */
    static VBox heading(String title, String subtitle) {
        Label heading = new Label(title);
        heading.getStyleClass().addAll("text-heading", "studio-panel-title");
        heading.setWrapText(true);
        VBox box = new VBox(4, heading);
        if (subtitle != null) {
            Label line = new Label(subtitle);
            line.getStyleClass().add("studio-panel-subtitle");
            line.setWrapText(true);
            box.getChildren().add(line);
        }
        return box;
    }

    /** A labelled field with an optional hint under it; the label names the control for screen readers. */
    static VBox field(String label, Node control, String hint) {
        Label name = new Label(label);
        name.getStyleClass().add("field-label");
        name.setLabelFor(control);
        VBox field = new VBox(6, name, control);
        field.getStyleClass().add("studio-field");
        if (hint != null) {
            Label line = new Label(hint);
            line.getStyleClass().add("field-hint");
            line.setWrapText(true);
            field.getChildren().add(line);
        }
        return field;
    }

    /** A one-sentence error under a field, hidden until {@link #showFieldError} fills it. */
    static Label fieldError() {
        Label error = new Label();
        error.getStyleClass().add("field-error");
        error.setGraphic(Icons.of(Feather.ALERT_CIRCLE, 12));
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        return error;
    }

    /** Shows {@code text} in {@code error}, or hides it when {@code text} is null. */
    static void showFieldError(Label error, String text) {
        boolean shown = text != null;
        error.setText(shown ? text : "");
        error.setVisible(shown);
        error.setManaged(shown);
    }

    /** A tinted note with an icon: {@code tone} is null, "warning", "success" or "danger". */
    static HBox note(Ikon icon, String text, String tone) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setMinHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(label, Priority.ALWAYS);
        HBox note = new HBox(12, Icons.of(icon, 16), label);
        note.setAlignment(Pos.TOP_LEFT);
        note.getStyleClass().add("studio-note");
        if (tone != null) {
            note.getStyleClass().add(tone);
        }
        return note;
    }

    /** A hairline between rows. */
    static Region divider() {
        Region line = new Region();
        line.getStyleClass().add("studio-row-divider");
        return line;
    }

    /** Space that takes the rest of a row. */
    static Region grow() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        VBox.setVgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** Keeps {@code input} to at most {@code max} characters as people type or paste. */
    static void limit(TextInputControl input, int max) {
        UnaryOperator<TextFormatter.Change> filter = change -> {
            if (change.getControlNewText().length() <= max) {
                return change;
            }
            int room = max - (change.getControlText().length() - (change.getRangeEnd() - change.getRangeStart()));
            if (room <= 0) {
                return null;
            }
            change.setText(change.getText().substring(0, Math.min(room, change.getText().length())));
            return change;
        };
        input.setTextFormatter(new TextFormatter<>(filter));
    }

    /** The stripped text of {@code input}, never null. */
    static String text(TextInputControl input) {
        return input.getText() == null ? "" : input.getText().strip();
    }
}
