package edu.learnsphere.app.ui.components;

import edu.learnsphere.app.theme.Motion;
import javafx.animation.Animation;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.AccessibleRole;
import javafx.scene.layout.Region;

/**
 * A thin rounded progress bar (4 px by default) whose fill eases to its value over 600 ms. Used at the top
 * of a study session, under module rows and inside document rows. {@link Tone} classes recolour the fill.
 */
public final class ProgressBarSlim extends Region {

    private final Region fill = new Region();
    private final DoubleProperty shown = new SimpleDoubleProperty(this, "shown", 0);
    private final double thickness;
    private double progress;
    private Animation running;

    public ProgressBarSlim() {
        this(4);
    }

    public ProgressBarSlim(double thickness) {
        this.thickness = thickness;
        getStyleClass().add("progress-slim");
        fill.getStyleClass().add("progress-slim-fill");
        fill.setManaged(false);
        getChildren().add(fill);
        setMinHeight(thickness);
        setPrefHeight(thickness);
        setMaxHeight(thickness);
        setPrefWidth(120);
        setAccessibleRole(AccessibleRole.TEXT);
        shown.addListener((obs, old, value) -> requestLayout());
    }

    public double getProgress() {
        return progress;
    }

    /** The fraction currently drawn (moves during the animation). */
    public double shownProgress() {
        return shown.get();
    }

    /** Eases to {@code value} (0–1). */
    public void setProgress(double value) {
        progress = clamp(value);
        if (running != null) {
            running.stop();
        }
        running = Motion.animateProgress(shown, progress);
        setAccessibleText(Formats.percent(progress));
    }

    /** Sets the value without animating. */
    public void setProgressNow(double value) {
        progress = clamp(value);
        if (running != null) {
            running.stop();
            running = null;
        }
        shown.set(progress);
        setAccessibleText(Formats.percent(progress));
    }

    public ProgressBarSlim tone(Tone tone) {
        Tone.apply(this, tone);
        return this;
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double value = shown.get();
        double width = value <= 0 ? 0 : Math.max(thickness, w * value);
        fill.resizeRelocate(0, 0, Math.min(w, width), getHeight());
        fill.setVisible(value > 0);
    }

    private static double clamp(double value) {
        return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value));
    }
}
