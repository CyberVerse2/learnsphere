package edu.learnsphere.app.ui.library;

import java.util.Objects;

/**
 * The stylesheet of the Library screen and the sheets it opens ({@code library.css} next to this package).
 * Sheets live in the window's modal layer, outside any page, so each sheet adds it itself.
 */
public final class LibraryStyles {

    /** The stylesheet's URL, for {@code getStylesheets().add(…)}. */
    public static final String URL = Objects.requireNonNull(LibraryStyles.class.getResource("library.css"),
            "library.css").toExternalForm();

    private LibraryStyles() {
    }
}
