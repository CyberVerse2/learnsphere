package edu.learnsphere.app.ui.library;

import edu.learnsphere.app.ui.components.CourseCover;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Toggle;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.util.Duration;

/**
 * The cover colours a creator picks from, as a grid of small covers. One is always chosen; the arrow keys
 * move the choice and Tab leaves the grid. {@link #hueProperty()} holds the chosen hue (0–359).
 */
public final class CoverHuePicker extends GridPane {

    /**
     * One cover colour.
     *
     * @param hue  the hue (0–359)
     * @param name what people call it, for screen readers and the tooltip
     */
    public record Swatch(int hue, String name) {
        public Swatch {
            Objects.requireNonNull(name, "name");
        }
    }

    /** The colours offered, in the order new courses get them when nobody picks one. */
    public static final List<Swatch> SWATCHES = List.of(
            new Swatch(210, "Blue"), new Swatch(150, "Jade"), new Swatch(28, "Apricot"),
            new Swatch(262, "Violet"), new Swatch(340, "Rose"), new Swatch(190, "Teal"),
            new Swatch(95, "Moss"), new Swatch(45, "Amber"), new Swatch(300, "Orchid"),
            new Swatch(120, "Green"), new Swatch(0, "Coral"), new Swatch(235, "Indigo"));

    /** Swatches per row. */
    public static final int COLUMNS = 6;

    private final ToggleGroup group = new ToggleGroup();
    private final List<ToggleButton> buttons = new ArrayList<>();
    private final IntegerProperty hue = new SimpleIntegerProperty(this, "hue");

    /** A picker with {@code initialHue} chosen (the nearest offered colour when it is not one of them). */
    public CoverHuePicker(int initialHue) {
        getStyleClass().add("hue-picker");
        setHgap(8);
        setVgap(8);
        for (int i = 0; i < SWATCHES.size(); i++) {
            Swatch swatch = SWATCHES.get(i);
            ToggleButton button = new ToggleButton();
            button.setGraphic(new CourseCover(swatch.hue(), "swatch-" + swatch.hue(), CourseCover.Size.SMALL));
            button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
            button.getStyleClass().setAll("hue-swatch");
            button.setToggleGroup(group);
            button.setUserData(swatch);
            button.setAccessibleText("Cover colour: " + swatch.name());
            Tooltip tip = new Tooltip(swatch.name());
            tip.setShowDelay(Duration.millis(300));
            button.setTooltip(tip);
            buttons.add(button);
            add(button, i % COLUMNS, i / COLUMNS);
        }
        group.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                if (old != null) {
                    old.setSelected(true);
                }
                return;
            }
            hue.set(((Swatch) selected.getUserData()).hue());
        });
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            int index = buttons.indexOf(group.getSelectedToggle());
            int next = switch (event.getCode()) {
                case RIGHT -> Math.min(index + 1, buttons.size() - 1);
                case LEFT -> Math.max(index - 1, 0);
                case DOWN -> Math.min(index + COLUMNS, buttons.size() - 1);
                case UP -> index - COLUMNS >= 0 ? index - COLUMNS : index;
                default -> -1;
            };
            if (next >= 0) {
                event.consume();
                buttons.get(next).setSelected(true);
                buttons.get(next).requestFocus();
            }
        });
        select(initialHue);
    }

    /** The chosen hue. */
    public IntegerProperty hueProperty() {
        return hue;
    }

    public int getHue() {
        return hue.get();
    }

    /** Chooses the offered colour nearest {@code value}. */
    public void select(int value) {
        int target = Math.floorMod(value, 360);
        ToggleButton best = buttons.getFirst();
        int bestDistance = Integer.MAX_VALUE;
        for (ToggleButton button : buttons) {
            int d = distance(((Swatch) button.getUserData()).hue(), target);
            if (d < bestDistance) {
                best = button;
                bestDistance = d;
            }
        }
        best.setSelected(true);
    }

    /** The swatch buttons in order (tests). */
    public List<ToggleButton> swatches() {
        return List.copyOf(buttons);
    }

    /** The selected swatch button. */
    public Toggle selected() {
        return group.getSelectedToggle();
    }

    /**
     * The offered hue farthest around the colour wheel from every hue in {@code used}, so a new course looks
     * different from the ones beside it; the earliest offered hue wins a tie.
     */
    public static int suggest(Collection<Integer> used) {
        int best = SWATCHES.getFirst().hue();
        int bestDistance = -1;
        for (Swatch swatch : SWATCHES) {
            int nearest = 360;
            for (int other : used) {
                nearest = Math.min(nearest, distance(swatch.hue(), other));
            }
            if (nearest > bestDistance) {
                best = swatch.hue();
                bestDistance = nearest;
            }
        }
        return best;
    }

    private static int distance(int a, int b) {
        int d = Math.abs(a - b) % 360;
        return Math.min(d, 360 - d);
    }
}
