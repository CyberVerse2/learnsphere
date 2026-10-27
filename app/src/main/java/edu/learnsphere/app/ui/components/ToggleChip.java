package edu.learnsphere.app.ui.components;

import javafx.scene.Node;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.Region;

/** A chip people can select (filters, confidence). Outlined when off, accent-tinted when on. */
public class ToggleChip extends ToggleButton {

    public ToggleChip(String text) {
        this(text, null);
    }

    /** @param graphic a node before the text, or null */
    public ToggleChip(String text, Node graphic) {
        super(text, graphic);
        getStyleClass().setAll("chip", "toggle-chip");
        setMinWidth(Region.USE_PREF_SIZE);
        setMnemonicParsing(false);
    }
}
