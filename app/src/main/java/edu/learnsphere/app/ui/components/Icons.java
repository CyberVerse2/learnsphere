package edu.learnsphere.app.ui.components;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.javafx.FontIcon;

/** Icon nodes. Colour and size come from the stylesheet ({@code -fx-icon-color}, {@code -fx-icon-size}). */
public final class Icons {

    private Icons() {
    }

    public static FontIcon of(Ikon icon) {
        return new FontIcon(icon);
    }

    /** An icon with an explicit size in pixels (colour still from the stylesheet). */
    public static FontIcon of(Ikon icon, int size) {
        FontIcon node = new FontIcon(icon);
        node.setIconSize(size);
        return node;
    }
}
