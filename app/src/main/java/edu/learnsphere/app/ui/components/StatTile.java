package edu.learnsphere.app.ui.components;

import edu.learnsphere.app.theme.Motion;
import java.util.Objects;
import java.util.function.DoubleFunction;
import javafx.animation.Animation;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * One number worth knowing, set big: a caption ("Due today"), the numeral in Newsreader 40 with an
 * optional unit ("days", "min"), and an optional detail line coloured by its {@link Tone} ("4 more than
 * yesterday"). A graphic (a ring, an icon) can sit on the right. Framed as a flat card by default.
 *
 * <p>{@link #countTo(double)} counts the numeral up to its value over 600 ms (instantly when motion is
 * reduced), a small moment when a screen opens.
 */
public final class StatTile extends VBox {

    private final Label label;
    private final Label value = new Label();
    private final Label unit = new Label();
    private final Label detail = new Label();
    private final HBox top;
    private final DoubleProperty shown = new SimpleDoubleProperty(this, "shown");
    private DoubleFunction<String> format = v -> Formats.number(Math.round(v));
    private Animation running;

    public StatTile(String label, String value) {
        getStyleClass().addAll("stat-tile", "framed");
        this.label = new Label(Objects.requireNonNull(label, "label"));
        this.label.getStyleClass().add("stat-label");
        this.value.getStyleClass().addAll("text-numeral", "stat-value");
        this.value.setText(value);
        this.value.setMinWidth(Region.USE_PREF_SIZE);
        unit.getStyleClass().add("stat-unit");
        unit.setManaged(false);
        unit.setVisible(false);
        HBox number = new HBox(6, this.value, unit);
        number.setAlignment(Pos.BASELINE_LEFT);
        detail.getStyleClass().add("stat-detail");
        detail.setWrapText(true);
        detail.setManaged(false);
        detail.setVisible(false);
        VBox text = new VBox(4, this.label, number);
        HBox.setHgrow(text, Priority.ALWAYS);
        top = new HBox(12, text);
        top.setAlignment(Pos.TOP_LEFT);
        getChildren().addAll(top, detail);
        shown.addListener((obs, old, v) -> this.value.setText(format.apply(v.doubleValue())));
    }

    /** A tile showing a whole number. */
    public StatTile(String label, long number) {
        this(label, Formats.number(number));
        shown.set(number);
    }

    /** The unit after the numeral, e.g. "days" (null hides it). */
    public StatTile unit(String text) {
        unit.setText(text);
        unit.setManaged(text != null);
        unit.setVisible(text != null);
        return this;
    }

    /** A line under the numeral (null hides it). */
    public StatTile detail(String text, Tone tone) {
        detail.setText(text);
        detail.setManaged(text != null);
        detail.setVisible(text != null);
        Tone.apply(detail, tone);
        return this;
    }

    /** A node on the right of the numeral, such as a progress ring. */
    public StatTile graphic(Node node) {
        if (top.getChildren().size() > 1) {
            top.getChildren().remove(1);
        }
        if (node != null) {
            top.getChildren().add(node);
        }
        return this;
    }

    /** How numbers become the numeral's text when counting (default: whole number with separators). */
    public StatTile format(DoubleFunction<String> formatter) {
        format = Objects.requireNonNull(formatter, "formatter");
        value.setText(format.apply(shown.get()));
        return this;
    }

    /** Whether the tile draws its own card frame (default true). */
    public StatTile framed(boolean framed) {
        if (framed && !getStyleClass().contains("framed")) {
            getStyleClass().add("framed");
        } else if (!framed) {
            getStyleClass().remove("framed");
        }
        return this;
    }

    /** Sets the numeral to {@code number} at once. */
    public void setNumber(double number) {
        stop();
        shown.set(number);
        value.setText(format.apply(number));
    }

    /** Counts the numeral from zero up to {@code number}. */
    public void countTo(double number) {
        stop();
        shown.set(0);
        value.setText(format.apply(0));
        running = Motion.animate(shown, number, Motion.PROGRESS);
        if (running == null) {
            value.setText(format.apply(number));
        }
    }

    public String valueText() {
        return value.getText();
    }

    public Label labelNode() {
        return label;
    }

    private void stop() {
        if (running != null) {
            running.stop();
            running = null;
        }
    }
}
