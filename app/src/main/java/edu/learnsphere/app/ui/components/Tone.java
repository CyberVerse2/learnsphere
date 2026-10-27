package edu.learnsphere.app.ui.components;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import javafx.scene.Node;

/**
 * The meaning a coloured element carries: neutral, the accent (progress, the next step), success
 * (correct, ready), warning (needs review, due soon) or danger (wrong, failed). Each tone is a style class
 * ({@code tone-accent} …) that the stylesheet maps to the colour tokens.
 */
public enum Tone {
    NEUTRAL,
    ACCENT,
    SUCCESS,
    WARNING,
    DANGER;

    private static final List<String> CLASSES = Arrays.stream(values()).map(Tone::styleClass).toList();

    /** The style class, e.g. {@code tone-warning}. */
    public String styleClass() {
        return "tone-" + name().toLowerCase(Locale.ROOT);
    }

    /** Replaces whatever tone class {@code node} has with {@code tone}'s. */
    public static void apply(Node node, Tone tone) {
        node.getStyleClass().removeAll(CLASSES);
        node.getStyleClass().add(tone.styleClass());
    }
}
