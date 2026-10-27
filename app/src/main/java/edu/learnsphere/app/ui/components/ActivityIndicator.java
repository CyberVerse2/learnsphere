package edu.learnsphere.app.ui.components;

import edu.learnsphere.app.theme.Motion;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.scene.layout.Region;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.StrokeLineCap;
import javafx.util.Duration;

/**
 * A small spinning arc for work in progress. It turns only while it is in a scene, and stands still (as a
 * three-quarter ring) when motion is reduced. Style classes: {@code activity-indicator}, {@code track},
 * {@code arc}.
 */
public final class ActivityIndicator extends Region {

    private final Arc arc = new Arc();
    private final Circle track = new Circle();
    private final RotateTransition spin;

    public ActivityIndicator(double size) {
        getStyleClass().add("activity-indicator");
        double stroke = Math.max(1.5, size / 8);
        double r = (size - stroke) / 2;
        track.setCenterX(size / 2);
        track.setCenterY(size / 2);
        track.setRadius(r);
        track.setStrokeWidth(stroke);
        track.getStyleClass().add("track");
        arc.setCenterX(size / 2);
        arc.setCenterY(size / 2);
        arc.setRadiusX(r);
        arc.setRadiusY(r);
        arc.setStartAngle(90);
        arc.setLength(-110);
        arc.setType(ArcType.OPEN);
        arc.setStrokeWidth(stroke);
        arc.setStrokeLineCap(StrokeLineCap.ROUND);
        arc.getStyleClass().add("arc");
        getChildren().addAll(track, arc);
        setMinSize(size, size);
        setPrefSize(size, size);
        setMaxSize(size, size);
        spin = new RotateTransition(Duration.millis(900), this);
        spin.setByAngle(360);
        spin.setInterpolator(Interpolator.LINEAR);
        spin.setCycleCount(Animation.INDEFINITE);
        sceneProperty().addListener((obs, old, scene) -> update());
        visibleProperty().addListener((obs, old, visible) -> update());
    }

    /** True while the arc is turning. */
    public boolean isSpinning() {
        return spin.getStatus() == Animation.Status.RUNNING;
    }

    private void update() {
        boolean run = getScene() != null && isVisible() && !Motion.reduced();
        if (run && spin.getStatus() != Animation.Status.RUNNING) {
            spin.play();
        } else if (!run) {
            spin.stop();
            setRotate(0);
        }
    }
}
