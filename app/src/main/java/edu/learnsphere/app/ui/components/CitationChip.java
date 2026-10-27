package edu.learnsphere.app.ui.components;

import edu.learnsphere.core.model.Citation;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.scene.control.Button;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.Region;
import javafx.util.Duration;

/**
 * Where part of an answer came from, as a small accent chip reading {@code p. 12} (or {@code pp. 12–13}).
 * Hovering shows the document and the cited passage; clicking calls {@link #setOnOpen(Consumer)} so the
 * screen can open the page with the passage highlighted.
 */
public final class CitationChip extends Button {

    /** Longest passage excerpt in the tooltip. */
    public static final int SNIPPET_LENGTH = 180;

    private final Citation citation;
    private Consumer<? super Citation> onOpen = citation -> { };

    public CitationChip(Citation citation) {
        super(Formats.pages(Objects.requireNonNull(citation, "citation")));
        this.citation = citation;
        getStyleClass().setAll("citation-chip");
        setMinWidth(Region.USE_PREF_SIZE);
        setMnemonicParsing(false);
        setFocusTraversable(true);
        String where = citation.documentTitle().isBlank() ? Formats.pages(citation)
                : citation.documentTitle() + ", " + Formats.pages(citation);
        setAccessibleText("Open " + where);
        String tip = citation.snippet().isBlank() ? where
                : where + "\n“" + Formats.shorten(citation.snippet(), SNIPPET_LENGTH) + "”";
        Tooltip tooltip = new Tooltip(tip);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(320);
        tooltip.setShowDelay(Duration.millis(250));
        setTooltip(tooltip);
        setOnAction(event -> onOpen.accept(citation));
    }

    public Citation citation() {
        return citation;
    }

    /** What clicking the chip does (opens the cited page). */
    public void setOnOpen(Consumer<? super Citation> action) {
        onOpen = Objects.requireNonNull(action, "action");
    }
}
