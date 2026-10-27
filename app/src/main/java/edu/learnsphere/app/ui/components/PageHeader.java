package edu.learnsphere.app.ui.components;

import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * The top of a screen: an optional overline (a date, a course name), the screen title in display type, an
 * optional sentence below it, and the screen's main actions on the right, aligned with the title's
 * baseline area.
 */
public final class PageHeader extends HBox {

    private final VBox text = new VBox(6);
    private final Label title;
    private final HBox actions = new HBox(12);
    private Label overline;
    private Label subtitle;

    public PageHeader(String title) {
        getStyleClass().add("page-header");
        setAlignment(Pos.BOTTOM_LEFT);
        setSpacing(24);
        this.title = new Label(Objects.requireNonNull(title, "title"));
        this.title.getStyleClass().addAll("text-display", "page-title");
        this.title.setWrapText(true);
        text.getChildren().add(this.title);
        HBox.setHgrow(text, Priority.ALWAYS);
        actions.setAlignment(Pos.BOTTOM_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        actions.getStyleClass().add("page-actions");
        getChildren().addAll(text, actions);
    }

    /** Small capitals above the title, e.g. the date. */
    public PageHeader overline(String value) {
        if (overline == null) {
            overline = new Label();
            overline.getStyleClass().addAll("text-overline", "page-overline");
            text.getChildren().addFirst(overline);
        }
        overline.setText(value.toUpperCase(java.util.Locale.ROOT));
        return this;
    }

    /** One or two sentences under the title. */
    public PageHeader subtitle(String value) {
        if (subtitle == null) {
            subtitle = new Label();
            subtitle.getStyleClass().addAll("page-subtitle");
            subtitle.setWrapText(true);
            subtitle.setMaxWidth(640);
            text.getChildren().add(subtitle);
        }
        subtitle.setText(value);
        return this;
    }

    /** The screen's actions, right-aligned (one primary at most). */
    public PageHeader actions(Node... nodes) {
        actions.getChildren().setAll(nodes);
        return this;
    }

    public Label titleLabel() {
        return title;
    }

    public void setTitle(String value) {
        title.setText(value);
    }
}
