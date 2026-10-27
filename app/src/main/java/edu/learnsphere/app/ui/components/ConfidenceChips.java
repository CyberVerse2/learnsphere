package edu.learnsphere.app.ui.components;

import edu.learnsphere.core.model.Confidence;
import java.util.EnumMap;
import java.util.Map;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;

/**
 * How sure the learner is before checking an answer: three chips, Guessing, Fairly sure and Certain, each
 * with a small one-to-three bar mark. One can be chosen at a time; choosing the chosen one again keeps it.
 * {@link #confidenceProperty()} is null until the learner picks.
 */
public final class ConfidenceChips extends HBox {

    private final ToggleGroup group = new ToggleGroup();
    private final Map<Confidence, ToggleChip> chips = new EnumMap<>(Confidence.class);
    private final ObjectProperty<Confidence> confidence = new SimpleObjectProperty<>(this, "confidence");

    public ConfidenceChips() {
        super(8);
        getStyleClass().add("confidence-chips");
        setAlignment(Pos.CENTER_LEFT);
        for (Confidence level : Confidence.values()) {
            ToggleChip chip = new ToggleChip(label(level), bars(level.value()));
            chip.getStyleClass().add("confidence-chip");
            chip.setToggleGroup(group);
            chip.setUserData(level);
            chip.setAccessibleText("Confidence: " + label(level));
            chips.put(level, chip);
            getChildren().add(chip);
        }
        group.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null && old != null && confidence.get() != null) {
                // Clicking the chosen chip keeps it chosen.
                old.setSelected(true);
                return;
            }
            confidence.set(selected == null ? null : (Confidence) selected.getUserData());
        });
        confidence.addListener((obs, old, value) -> {
            Toggle wanted = value == null ? null : chips.get(value);
            if (group.getSelectedToggle() != wanted) {
                if (wanted == null) {
                    // The property is already null, so the group lets the chip go.
                    group.selectToggle(null);
                } else {
                    wanted.setSelected(true);
                }
            }
        });
    }

    /** The chosen confidence, or null before the learner picks. Settable. */
    public ObjectProperty<Confidence> confidenceProperty() {
        return confidence;
    }

    public Confidence getConfidence() {
        return confidence.get();
    }

    public void setConfidence(Confidence value) {
        confidence.set(value);
    }

    /** The chip for {@code level} (tests and keyboard shortcuts). */
    public ToggleChip chip(Confidence level) {
        return chips.get(level);
    }

    /** The chip's label, e.g. "Fairly sure". */
    public static String label(Confidence level) {
        return switch (level) {
            case GUESSING -> "Guessing";
            case FAIRLY_SURE -> "Fairly sure";
            case CERTAIN -> "Certain";
        };
    }

    /** Three rising bars with {@code filled} of them filled. */
    private static Region bars(int filled) {
        HBox box = new HBox(2);
        box.setAlignment(Pos.BOTTOM_CENTER);
        box.getStyleClass().add("confidence-bars");
        for (int i = 1; i <= 3; i++) {
            Rectangle bar = new Rectangle(3, 4 + i * 3);
            bar.setArcWidth(2);
            bar.setArcHeight(2);
            bar.getStyleClass().add(i <= filled ? "bar-on" : "bar-off");
            box.getChildren().add(bar);
        }
        box.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return box;
    }
}
