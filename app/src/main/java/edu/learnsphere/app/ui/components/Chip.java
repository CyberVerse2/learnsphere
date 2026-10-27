package edu.learnsphere.app.ui.components;

import java.util.Objects;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.Ikon;

/**
 * A small rounded label for metadata and tags ("Flashcards", "AI draft", "12 due"). The {@link Tone}
 * colours it; an optional icon sits before the text. Not interactive: for a chip people select, use
 * {@link ToggleChip}.
 */
public class Chip extends Label {

    private Tone tone;

    public Chip(String text) {
        this(text, Tone.NEUTRAL, null);
    }

    public Chip(String text, Tone tone) {
        this(text, tone, null);
    }

    /** @param icon an icon before the text, or null */
    public Chip(String text, Tone tone, Ikon icon) {
        super(text);
        getStyleClass().add("chip");
        setMinWidth(Region.USE_PREF_SIZE);
        setMnemonicParsing(false);
        if (icon != null) {
            setGraphic(Icons.of(icon, 12));
        }
        setTone(tone);
    }

    public Tone getTone() {
        return tone;
    }

    public void setTone(Tone tone) {
        this.tone = Objects.requireNonNull(tone, "tone");
        Tone.apply(this, tone);
    }
}
