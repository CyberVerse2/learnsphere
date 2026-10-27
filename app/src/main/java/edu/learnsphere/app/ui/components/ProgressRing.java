package edu.learnsphere.app.ui.components;

import edu.learnsphere.app.theme.Motion;
import javafx.animation.Animation;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.css.PseudoClass;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.layout.Region;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;

/**
 * A learner's progress as a ring: a soft track, an accent arc that eases to its value over 600 ms, and a
 * small "moon" riding the arc's leading edge, the orbit motif of the course covers. The centre shows the
 * percentage (or any node set with {@link #setCenter(Node)}); rings under 48 px show no label.
 *
 * <p>Style classes: {@code progress-ring} with {@code ring-track}, {@code ring-fill}, {@code ring-moon} and
 * {@code ring-label}; the {@code :complete} pseudo-class is set at 100%.
 */
public final class ProgressRing extends Region {

    private static final PseudoClass COMPLETE = PseudoClass.getPseudoClass("complete");
    /** Rings smaller than this show no centre label. */
    public static final double LABEL_MIN_SIZE = 48;

    private final double diameter;
    private final double thickness;
    private final Circle track = new Circle();
    private final Arc fill = new Arc();
    private final Circle moon = new Circle();
    private final Label label = new Label();
    private final DoubleProperty shown = new SimpleDoubleProperty(this, "shown", 0);
    private final ReadOnlyDoubleWrapper progress = new ReadOnlyDoubleWrapper(this, "progress", 0);
    private Node center;
    private boolean showPercent = true;
    private Animation running;

    /** A ring of {@code diameter} pixels whose stroke is {@code thickness} pixels. */
    public ProgressRing(double diameter, double thickness) {
        this.diameter = diameter;
        this.thickness = thickness;
        getStyleClass().add("progress-ring");
        track.getStyleClass().add("ring-track");
        track.setStrokeWidth(thickness);
        fill.getStyleClass().add("ring-fill");
        fill.setType(ArcType.OPEN);
        fill.setStrokeWidth(thickness);
        fill.setStrokeLineCap(StrokeLineCap.ROUND);
        moon.getStyleClass().add("ring-moon");
        moon.setStrokeWidth(Math.max(1.5, thickness * 0.45));
        label.getStyleClass().add("ring-label");
        if (diameter >= 96) {
            label.getStyleClass().add("large");
        }
        label.setManaged(false);
        label.setVisible(diameter >= LABEL_MIN_SIZE);
        getChildren().addAll(track, fill, moon, label);
        setMinSize(diameter, diameter);
        setPrefSize(diameter, diameter);
        setMaxSize(diameter, diameter);
        setAccessibleRole(AccessibleRole.TEXT);
        shown.addListener((obs, old, value) -> {
            requestLayout();
            updateLabel();
        });
        updateLabel();
    }

    /** A ring sized for a course card (40 px). */
    public static ProgressRing small() {
        return new ProgressRing(40, 4);
    }

    /** The value the ring shows or is easing towards (0–1). */
    public ReadOnlyDoubleProperty progressProperty() {
        return progress.getReadOnlyProperty();
    }

    public double getProgress() {
        return progress.get();
    }

    /** The value currently drawn (moves during the animation). */
    public double shownProgress() {
        return shown.get();
    }

    /** Eases the ring to {@code value} (0–1) over 600 ms; jumps when motion is reduced. */
    public void setProgress(double value) {
        double target = clamp(value);
        progress.set(target);
        pseudoClassStateChanged(COMPLETE, target >= 1);
        if (running != null) {
            running.stop();
        }
        running = Motion.animateProgress(shown, target);
        setAccessibleText(Formats.percent(target) + " complete");
    }

    /** Sets the value without animating. */
    public void setProgressNow(double value) {
        double target = clamp(value);
        if (running != null) {
            running.stop();
            running = null;
        }
        progress.set(target);
        pseudoClassStateChanged(COMPLETE, target >= 1);
        shown.set(target);
        setAccessibleText(Formats.percent(target) + " complete");
    }

    /** Whether the centre shows the percentage (default true). */
    public void setShowPercent(boolean value) {
        showPercent = value;
        updateLabel();
    }

    /** Replaces the centre label with {@code node} (null restores the percentage). */
    public void setCenter(Node node) {
        if (center != null) {
            getChildren().remove(center);
        }
        center = node;
        label.setVisible(node == null && diameter >= LABEL_MIN_SIZE);
        if (node != null) {
            node.setManaged(false);
            getChildren().add(node);
        }
        requestLayout();
    }

    private void updateLabel() {
        label.setText(showPercent ? Formats.percent(shown.get()) : "");
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        double cx = w / 2;
        double cy = h / 2;
        double r = (Math.min(w, h) - thickness) / 2 - moonOverhang();
        track.setCenterX(cx);
        track.setCenterY(cy);
        track.setRadius(r);
        double value = shown.get();
        fill.setCenterX(cx);
        fill.setCenterY(cy);
        fill.setRadiusX(r);
        fill.setRadiusY(r);
        fill.setStartAngle(90);
        fill.setLength(-360 * value);
        fill.setVisible(value > 0.001);
        double angle = Math.toRadians(90 - 360 * value);
        moon.setCenterX(cx + r * Math.cos(angle));
        moon.setCenterY(cy - r * Math.sin(angle));
        moon.setRadius(thickness * 0.95);
        moon.setVisible(value > 0.001 && value < 0.999);
        for (Node node : new Node[] {label, center}) {
            if (node != null && node.isVisible()) {
                double nw = node.prefWidth(-1);
                double nh = node.prefHeight(nw);
                node.resizeRelocate(Math.round(cx - nw / 2), Math.round(cy - nh / 2), nw, nh);
            }
        }
    }

    /** Room the moon needs beyond the stroke so it is never clipped. */
    private double moonOverhang() {
        return Math.max(0, thickness * 0.95 + moon.getStrokeWidth() / 2 - thickness / 2);
    }

    private static double clamp(double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }
}
