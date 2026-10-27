package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.model.Visibility;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * Who can open a course: Only me, Anyone with the file, or With a code. Each choice is a row with a radio
 * mark, its name, what it means for learners and an icon. One choice is always selected. Like a radio group,
 * only the selected row is in the tab order; the up and down arrows move the choice, and a click, Space or
 * Enter picks a row.
 */
final class VisibilityPicker extends VBox {

    private static final PseudoClass SELECTED = PseudoClass.getPseudoClass("selected");

    private final Map<Visibility, Option> options = new EnumMap<>(Visibility.class);
    private final ObjectProperty<Visibility> value = new SimpleObjectProperty<>(this, "value", Visibility.PRIVATE);

    VisibilityPicker() {
        getStyleClass().add("visibility-picker");
        setAccessibleRole(AccessibleRole.LIST_VIEW);
        setAccessibleText("Who can open this course");
        setFillWidth(true);
        for (Visibility visibility : Visibility.values()) {
            Option option = option(visibility);
            options.put(visibility, option);
            getChildren().add(option);
        }
        value.addListener((obs, old, chosen) -> showSelected());
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
        showSelected();
    }

    private Option option(Visibility visibility) {
        Region dot = new Region();
        dot.getStyleClass().add("radio-dot");
        StackPane radio = new StackPane(dot);
        radio.getStyleClass().add("radio-mark");
        radio.setMinSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Label name = new Label(StudioFormats.visibilityLabel(visibility));
        name.getStyleClass().addAll("visibility-name", "medium");
        name.setMinHeight(Region.USE_PREF_SIZE);
        Label detail = new Label(StudioFormats.visibilityDetail(visibility));
        detail.getStyleClass().add("text-caption");
        detail.setWrapText(true);
        detail.setMinHeight(Region.USE_PREF_SIZE);
        VBox text = new VBox(2, name, detail);
        text.setMinWidth(0);
        text.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(text, Priority.ALWAYS);
        var icon = Icons.of(switch (visibility) {
            case PRIVATE -> Feather.LOCK;
            case SHAREABLE -> Feather.SHARE_2;
            case PROTECTED -> Feather.KEY;
        }, 16);
        icon.getStyleClass().add("visibility-icon");

        Option option = new Option(radio, text, icon);
        option.setAlignment(Pos.CENTER_LEFT);
        option.getStyleClass().add("visibility-option");
        option.setMaxWidth(Double.MAX_VALUE);
        option.setUserData(visibility);
        option.setAccessibleRole(AccessibleRole.RADIO_BUTTON);
        option.setAccessibleText(name.getText() + ". " + detail.getText());
        option.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                value.set(visibility);
                option.requestFocus();
            }
        });
        option.addEventHandler(KeyEvent.KEY_PRESSED, event -> {
            switch (event.getCode()) {
                case SPACE, ENTER -> {
                    value.set(visibility);
                    event.consume();
                }
                default -> {
                }
            }
        });
        return option;
    }

    private void showSelected() {
        Visibility chosen = value.get();
        options.forEach((visibility, option) -> {
            boolean selected = visibility == chosen;
            option.setSelected(selected);
            option.setFocusTraversable(selected);
        });
    }

    /** A choice: a row that reports whether it is the selected one, to the stylesheet and to screen readers. */
    private static final class Option extends HBox {

        private boolean selected;

        Option(Node... children) {
            super(14, children);
        }

        void setSelected(boolean value) {
            if (selected != value) {
                selected = value;
                pseudoClassStateChanged(SELECTED, value);
                notifyAccessibleAttributeChanged(AccessibleAttribute.SELECTED);
            }
        }

        @Override
        public Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
            return attribute == AccessibleAttribute.SELECTED ? selected : super.queryAccessibleAttribute(attribute, parameters);
        }
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
    Region option(Visibility visibility, boolean unused) {
        return options.get(visibility);
    }
}
