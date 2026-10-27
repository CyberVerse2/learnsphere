package edu.learnsphere.app.ui.components;

import java.util.Objects;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Tooltip;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;

/**
 * A quiet, square button showing only an icon. The label is required: it becomes the tooltip and the text
 * screen readers announce. Add {@code small} for the 28 px size.
 */
public class IconButton extends Button {

    public IconButton(Ikon icon, String label) {
        Objects.requireNonNull(icon, "icon");
        Objects.requireNonNull(label, "label");
        setGraphic(Icons.of(icon));
        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        getStyleClass().add("icon-button");
        setAccessibleText(label);
        Tooltip tooltip = new Tooltip(label);
        tooltip.setShowDelay(Duration.millis(400));
        setTooltip(tooltip);
    }

    /** The 28 px variant. */
    public IconButton small() {
        if (!getStyleClass().contains("small")) {
            getStyleClass().add("small");
        }
        return this;
    }
}
