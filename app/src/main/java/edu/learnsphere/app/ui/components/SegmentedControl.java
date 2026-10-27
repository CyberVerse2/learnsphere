package edu.learnsphere.app.ui.components;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.geometry.Pos;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

/**
 * A row of mutually exclusive options in a sunken track, the chosen one raised (Library's "Studying /
 * Creating"). One option is always chosen. Left and right arrow keys move the choice.
 *
 * @param <T> the option type
 */
public final class SegmentedControl<T> extends HBox {

    private final ToggleGroup group = new ToggleGroup();
    private final List<T> values;
    private final List<ToggleButton> buttons = new ArrayList<>();
    private final ObjectProperty<T> value = new SimpleObjectProperty<>(this, "value");

    /**
     * @param values the options, in order (at least one)
     * @param label  each option's text
     */
    public SegmentedControl(List<T> values, Function<? super T, String> label) {
        if (values.isEmpty()) {
            throw new IllegalArgumentException("A segmented control needs at least one option");
        }
        this.values = List.copyOf(values);
        getStyleClass().add("segmented");
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Region.USE_PREF_SIZE);
        for (T option : this.values) {
            ToggleButton button = new ToggleButton(label.apply(option));
            button.getStyleClass().add("segment");
            button.setToggleGroup(group);
            button.setUserData(option);
            button.setMnemonicParsing(false);
            button.setMinWidth(Region.USE_PREF_SIZE);
            buttons.add(button);
            getChildren().add(button);
        }
        group.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null) {
                if (old != null) {
                    old.setSelected(true);
                }
                return;
            }
            @SuppressWarnings("unchecked")
            T chosen = (T) selected.getUserData();
            value.set(chosen);
        });
        value.addListener((obs, old, chosen) -> {
            int index = this.values.indexOf(chosen);
            if (index >= 0 && !buttons.get(index).isSelected()) {
                buttons.get(index).setSelected(true);
            }
        });
        addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            int index = this.values.indexOf(value.get());
            if (event.getCode() == KeyCode.RIGHT && index < this.values.size() - 1) {
                value.set(this.values.get(index + 1));
                buttons.get(index + 1).requestFocus();
                event.consume();
            } else if (event.getCode() == KeyCode.LEFT && index > 0) {
                value.set(this.values.get(index - 1));
                buttons.get(index - 1).requestFocus();
                event.consume();
            }
        });
        value.set(this.values.getFirst());
    }

    /** The chosen option (never null). */
    public ObjectProperty<T> valueProperty() {
        return value;
    }

    public T getValue() {
        return value.get();
    }

    public void setValue(T option) {
        Objects.requireNonNull(option, "option");
        if (!values.contains(option)) {
            throw new IllegalArgumentException("Not an option: " + option);
        }
        value.set(option);
    }

    /** The button of {@code option}. */
    public ToggleButton button(T option) {
        return buttons.get(values.indexOf(option));
    }
}
