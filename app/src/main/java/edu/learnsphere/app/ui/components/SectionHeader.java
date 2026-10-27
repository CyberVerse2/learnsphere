package edu.learnsphere.app.ui.components;

import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The heading of a section of a page: a title (section heading type, or the larger title type), an
 * optional line of explanation below it, and optional controls on the right, such as a "See all" link.
 *
 * <pre>{@code
 * new SectionHeader("Continue").action("Open library", () -> navigator.navigate(Destination.LIBRARY));
 * }</pre>
 */
public final class SectionHeader extends HBox {

    private final Label title;
    private final VBox text;
    private final HBox trailing = new HBox(8);
    private Label subtitle;

    public SectionHeader(String title) {
        getStyleClass().add("section-header");
        setAlignment(Pos.CENTER_LEFT);
        setFillHeight(false);
        this.title = new Label(Objects.requireNonNull(title, "title"));
        this.title.getStyleClass().addAll("section-title", "text-heading");
        this.title.setWrapText(true);
        text = new VBox(2, this.title);
        HBox.setHgrow(text, Priority.ALWAYS);
        trailing.setAlignment(Pos.CENTER_RIGHT);
        trailing.getStyleClass().add("section-trailing");
        trailing.setMinWidth(Region.USE_PREF_SIZE);
        getChildren().addAll(text, trailing);
    }

    /** A section header set in the title type (Newsreader 24), for the main sections of a page. */
    public static SectionHeader large(String title) {
        SectionHeader header = new SectionHeader(title);
        header.title.getStyleClass().setAll("label", "section-title", "text-title");
        header.getStyleClass().add("large");
        return header;
    }

    /** Adds a line of explanation under the title. */
    public SectionHeader subtitle(String value) {
        if (subtitle == null) {
            subtitle = new Label();
            subtitle.getStyleClass().addAll("section-subtitle", "text-muted");
            subtitle.setWrapText(true);
            text.getChildren().add(subtitle);
        }
        subtitle.setText(value);
        return this;
    }

    /** Adds a quiet link-style button with a trailing arrow on the right. */
    public SectionHeader action(String label, Runnable action) {
        Button button = new Button(label, Icons.of(Feather.ARROW_RIGHT, 14));
        button.getStyleClass().addAll("quiet", "small");
        button.setContentDisplay(ContentDisplay.RIGHT);
        button.setOnAction(event -> action.run());
        trailing.getChildren().add(button);
        return this;
    }

    /** Adds controls on the right. */
    public SectionHeader trailing(Node... nodes) {
        trailing.getChildren().addAll(nodes);
        return this;
    }

    public Label titleLabel() {
        return title;
    }
}
