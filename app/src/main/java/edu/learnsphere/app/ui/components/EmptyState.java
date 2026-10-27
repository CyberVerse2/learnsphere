package edu.learnsphere.app.ui.components;

import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

/**
 * What a place shows before it has anything in it: a small drawing, a title, one or two sentences saying
 * what belongs here, and the action that fills it. An empty screen is an invitation to act, so give it a
 * primary action whenever there is one.
 *
 * <pre>{@code
 * new EmptyState(EmptyIllustration.Kind.LIBRARY, "Your library is empty",
 *         "Import a course someone shared with you, or make your own from your PDFs.")
 *     .primary("Import a course", this::importCourse)
 *     .secondary("New course", this::newCourse);
 * }</pre>
 */
public final class EmptyState extends VBox {

    private final HBox actions = new HBox(12);
    private final Label title;
    private final Label message;

    public EmptyState(EmptyIllustration.Kind kind, String title, String message) {
        getStyleClass().add("empty-state");
        setAlignment(Pos.CENTER);
        this.title = new Label(Objects.requireNonNull(title, "title"));
        this.title.getStyleClass().addAll("text-title", "empty-state-title");
        this.title.setWrapText(true);
        this.title.setTextAlignment(TextAlignment.CENTER);
        this.message = new Label(Objects.requireNonNull(message, "message"));
        this.message.getStyleClass().add("empty-state-message");
        this.message.setWrapText(true);
        this.message.setTextAlignment(TextAlignment.CENTER);
        this.message.setMaxWidth(440);
        actions.setAlignment(Pos.CENTER);
        actions.getStyleClass().add("empty-state-actions");
        VBox text = new VBox(8, this.title, this.message);
        text.setAlignment(Pos.CENTER);
        getChildren().addAll(new EmptyIllustration(kind), text);
    }

    /** The main action, as a primary button. */
    public EmptyState primary(String label, Runnable action) {
        return addButton(label, action, "primary");
    }

    /** A second, quieter action. */
    public EmptyState secondary(String label, Runnable action) {
        return addButton(label, action, null);
    }

    public String titleText() {
        return title.getText();
    }

    public String messageText() {
        return message.getText();
    }

    /** The action buttons in order. */
    public HBox actions() {
        return actions;
    }

    private EmptyState addButton(String label, Runnable action, String styleClass) {
        Button button = new Button(label);
        if (styleClass != null) {
            button.getStyleClass().add(styleClass);
        }
        button.setOnAction(event -> action.run());
        actions.getChildren().add(button);
        if (actions.getParent() == null) {
            getChildren().add(actions);
        }
        return this;
    }
}
