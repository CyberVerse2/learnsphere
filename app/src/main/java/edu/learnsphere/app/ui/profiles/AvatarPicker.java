package edu.learnsphere.app.ui.profiles;

import edu.learnsphere.app.ui.components.Avatar;
import edu.learnsphere.core.service.ProfileSummary;
import java.util.List;
import java.util.Locale;
import javafx.beans.property.ReadOnlyIntegerProperty;
import javafx.beans.property.ReadOnlyIntegerWrapper;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;

/**
 * The avatar colours to choose from, each drawn as the avatar it makes (with the initials of the name being
 * typed). One is always chosen. Tab reaches each colour; Space or Enter picks it.
 */
public final class AvatarPicker extends HBox {

    /** A colour on offer: its hue and the name screen readers say. */
    public record Colour(int hue, String name) {
    }

    /** The colours on offer, in order. */
    public static final List<Colour> COLOURS = List.of(
            new Colour(18, "Coral"), new Colour(42, "Amber"), new Colour(150, "Green"), new Colour(190, "Teal"),
            new Colour(226, "Blue"), new Colour(268, "Violet"), new Colour(320, "Pink"));

    /** Size of each avatar. */
    public static final double SIZE = 34;

    private final ToggleGroup group = new ToggleGroup();
    private final ReadOnlyIntegerWrapper hue = new ReadOnlyIntegerWrapper(this, "hue");
    private String initials = "";

    /** A picker with {@code hue} chosen (the nearest colour on offer when it is not one of them). */
    public AvatarPicker(int hue) {
        super(10);
        getStyleClass().add("avatar-picker");
        setAlignment(Pos.CENTER_LEFT);
        for (Colour colour : COLOURS) {
            ToggleButton swatch = new ToggleButton();
            swatch.getStyleClass().setAll("avatar-swatch");
            swatch.setUserData(colour.hue());
            swatch.setToggleGroup(group);
            swatch.setAccessibleText(colour.name());
            swatch.setMnemonicParsing(false);
            getChildren().add(swatch);
        }
        group.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                // One colour is always chosen: clicking the chosen one again keeps it.
                if (old != null) {
                    old.setSelected(true);
                }
                return;
            }
            this.hue.set((Integer) selected.getUserData());
        });
        setHue(hue);
        paint();
    }

    /** The chosen hue. */
    public ReadOnlyIntegerProperty hueProperty() {
        return hue.getReadOnlyProperty();
    }

    public int getHue() {
        return hue.get();
    }

    /** Chooses the colour nearest to {@code value}. */
    public void setHue(int value) {
        Colour nearest = COLOURS.getFirst();
        for (Colour colour : COLOURS) {
            if (distance(colour.hue(), value) < distance(nearest.hue(), value)) {
                nearest = colour;
            }
        }
        int chosen = nearest.hue();
        group.getToggles().stream().filter(t -> (Integer) t.getUserData() == chosen).findFirst()
                .ifPresent(group::selectToggle);
    }

    /** Shows the initials of {@code name} on every colour. */
    public void setName(String name) {
        String next = initials(name);
        if (!next.equals(initials)) {
            initials = next;
            paint();
        }
    }

    /** The swatch of {@code hue} (tests and keyboard focus). */
    public ToggleButton swatch(int value) {
        return (ToggleButton) group.getToggles().stream().filter(t -> (Integer) t.getUserData() == value)
                .findFirst().orElseThrow();
    }

    private void paint() {
        for (var toggle : group.getToggles()) {
            ((ToggleButton) toggle).setGraphic(new Avatar(initials, (Integer) toggle.getUserData(), SIZE));
        }
    }

    private static int distance(int a, int b) {
        int d = Math.abs(Math.floorMod(a, 360) - Math.floorMod(b, 360));
        return Math.min(d, 360 - d);
    }

    /** The avatar initials of a name being typed, as {@link ProfileSummary#initials()} makes them; empty for none. */
    static String initials(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        return new ProfileSummary("preview", name.strip(), 0, false, 0, null).initials().toUpperCase(Locale.ROOT);
    }
}
