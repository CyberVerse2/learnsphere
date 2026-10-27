package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.model.Visibility;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * Who can open a course: Only me, Anyone with the file, or With a code. Each choice is a row with a radio
 * mark, its name and what it means for learners. One choice is always selected; the up and down arrows move
 * it.
 */
final class VisibilityPicker extends VBox {

    private final ToggleGroup group = new ToggleGroup();
    private final Map<Visibility, ToggleButton> options = new EnumMap<>(Visibility.class);
    private final ObjectProperty<Visibility> value = new SimpleObjectProperty<>(this, "value", Visibility.PRIVATE);

    VisibilityPicker() {
        super(8);
        getStyleClass().add("visibility-picker");
        setAccessibleRole(AccessibleRole.LIST_VIEW);
        setAccessibleText("Who can open this course");
        for (Visibility visibility : Visibility.values()) {
            ToggleButton option = option(visibility);
            options.put(visibility, option);
            getChildren().add(option);
        }
        group.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                if (old != null) {
                    old.setSelected(true);
                }
                return;
            }
            value.set((Visibility) selected.getUserData());
        });
        value.addListener((obs, old, chosen) -> {
            ToggleButton option = options.get(chosen);
            if (option != null && !option.isSelected()) {
                option.setSelected(true);
            }
        });
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            int step = switch (event.getCode()) {
                case DOWN -> 1;
                case UP -> -1;
                default -> 0;
            };
            if (step != 0) {
                Visibility[] all = Visibility.values();
                int next = Math.clamp(value.get().ordinal() + step, 0, all.length - 1);
                value.set(all[next]);
                options.get(all[next]).requestFocus();
                event.consume();
            }
        });
        options.get(Visibility.PRIVATE).setSelected(true);
    }

    private ToggleButton option(Visibility visibility) {
        Region dot = new Region();
        dot.getStyleClass().add("radio-dot");
        StackPane radio = new StackPane(dot);
        radio.getStyleClass().add("radio-mark");
        radio.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Label name = new Label(StudioFormats.visibilityLabel(visibility));
        name.getStyleClass().addAll("visibility-name", "medium");
        Label detail = new Label(StudioFormats.visibilityDetail(visibility));
        detail.getStyleClass().add("text-caption");
        detail.setWrapText(true);
        VBox text = new VBox(2, name, detail);
        HBox.setHgrow(text, Priority.ALWAYS);
        var icon = Icons.of(switch (visibility) {
            case PRIVATE -> Feather.LOCK;
            case SHAREABLE -> Feather.SHARE_2;
            case PROTECTED -> Feather.KEY;
        }, 16);
        icon.getStyleClass().add("visibility-icon");
        HBox content = new HBox(14, radio, text, icon);
        content.setAlignment(Pos.CENTER_LEFT);
        content.setMaxWidth(Double.MAX_VALUE);

        ToggleButton option = new ToggleButton();
        option.setGraphic(content);
        option.setUserData(visibility);
        option.setToggleGroup(group);
        option.getStyleClass().setAll("visibility-option");
        option.setMaxWidth(Double.MAX_VALUE);
        option.setAccessibleRole(AccessibleRole.RADIO_BUTTON);
        option.setAccessibleText(name.getText() + ". " + detail.getText());
        content.prefWidthProperty().bind(option.widthProperty().subtract(32));
        return option;
    }

    /** The chosen visibility (never null). */
    ObjectProperty<Visibility> valueProperty() {
        return value;
    }

    Visibility getValue() {
        return value.get();
    }

    void setValue(Visibility visibility) {
        value.set(Objects.requireNonNull(visibility, "visibility"));
    }

    /** The row of {@code visibility} (tests). */
    ToggleButton option(Visibility visibility, boolean unused) {
        return options.get(visibility);
    }
}
