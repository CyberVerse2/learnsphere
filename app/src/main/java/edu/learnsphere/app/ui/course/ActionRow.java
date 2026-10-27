package edu.learnsphere.app.ui.course;

import edu.learnsphere.app.ui.components.Icons;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAction;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;

/**
 * A full-width row that acts as a button: an icon, a title with a line under it, and a chevron. Click, Enter
 * or Space runs it; a disabled row is skipped by the keyboard. Long titles and captions wrap.
 */
public final class ActionRow extends HBox {

    private final Label title;
    private final Label caption;
    private Runnable onAction = () -> { };

    public ActionRow(Ikon icon, String title, String caption) {
        getStyleClass().add("action-row");
        var glyph = Icons.of(icon, 18);
        glyph.getStyleClass().add("action-row-icon");
        this.title = new Label(Objects.requireNonNull(title, "title"));
        this.title.getStyleClass().addAll("medium", "action-row-title");
        this.title.setWrapText(true);
        this.caption = new Label(caption);
        this.caption.getStyleClass().addAll("text-caption", "action-row-caption");
        this.caption.setWrapText(true);
        this.caption.setVisible(caption != null && !caption.isBlank());
        this.caption.setManaged(this.caption.isVisible());
        VBox words = new VBox(2, this.title, this.caption);
        words.setMinWidth(0);
        HBox.setHgrow(words, Priority.ALWAYS);
        var arrow = Icons.of(Feather.CHEVRON_RIGHT, 16);
        arrow.getStyleClass().add("action-row-arrow");
        getChildren().addAll(glyph, words, arrow);
        setSpacing(14);
        setAlignment(Pos.CENTER_LEFT);
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.BUTTON);
        setAccessibleText(caption == null || caption.isBlank() ? title : title + ". " + caption);
        focusTraversableProperty().bind(disabledProperty().not());

        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && !isDisabled()) {
                fire();
            }
        });
        setOnKeyPressed(event -> {
            if ((event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) && !isDisabled()) {
                event.consume();
                fire();
            }
        });
    }

    public void setOnAction(Runnable action) {
        onAction = Objects.requireNonNull(action, "action");
    }

    /** Runs the row's action, as a click does. */
    public void fire() {
        onAction.run();
    }

    public Label titleLabel() {
        return title;
    }

    public Label captionLabel() {
        return caption;
    }

    @Override
    public void executeAccessibleAction(AccessibleAction action, Object... parameters) {
        if (action == AccessibleAction.FIRE) {
            fire();
        } else {
            super.executeAccessibleAction(action, parameters);
        }
    }
}
