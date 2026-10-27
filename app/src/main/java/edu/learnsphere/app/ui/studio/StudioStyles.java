package edu.learnsphere.app.ui.studio;

import java.util.Objects;
import javafx.collections.ObservableList;
import javafx.scene.Parent;

/** The Studio's own stylesheet ({@code studio.css}), layered over the theme for the Studio's pages and dialogs. */
final class StudioStyles {

    /** The stylesheet's URL. */
    static final String URL = Objects.requireNonNull(StudioStyles.class.getResource("studio.css"), "studio.css")
            .toExternalForm();

    private StudioStyles() {
    }

    /** Adds the stylesheet to {@code parent} (once). */
    static <P extends Parent> P apply(P parent) {
        ObservableList<String> sheets = parent.getStylesheets();
        if (!sheets.contains(URL)) {
            sheets.add(URL);
        }
        return parent;
    }
}
