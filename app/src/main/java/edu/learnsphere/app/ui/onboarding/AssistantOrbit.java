package edu.learnsphere.app.ui.onboarding;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.service.AssistantState;
import java.util.Objects;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.scene.Group;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.transform.Rotate;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;
import org.kordamp.ikonli.javafx.FontIcon;

/**
 * The study assistant's readiness drawn in the orbit language of the course covers: a sphere with a tilted
 * orbit and one moon. While it gets ready the moon travels the orbit, smaller and dimmer on the far side,
 * where the sphere hides it as it passes behind; when it is ready the moon comes to rest in front and the
 * sphere shows a check; when it can't run the moon stops and the sphere shows a warning. With reduced motion
 * the moon rests and only the sphere changes.
 * Style classes: {@code assistant-orbit} plus {@code starting}, {@code ready} or {@code unavailable}; parts
 * {@code orbit-sphere}, {@code orbit-line}, {@code orbit-moon}, {@code orbit-mark}.
 */
public final class AssistantOrbit extends Region {

    /** Width and height of the drawing. */
    public static final double WIDTH = 136;
    public static final double HEIGHT = 96;

    private static final double SPHERE_RADIUS = 26;
    private static final double ORBIT_RX = 56;
    private static final double ORBIT_RY = 16;
    private static final double MOON_RADIUS = 6;
    /** On the far side the moon is further away, so it looks smaller. */
    private static final double FAR_MOON_RADIUS = 4.5;
    private static final double TILT = -14;
    /** Where the moon rests: on the near side, to the right of the sphere. */
    private static final double REST_ANGLE = 322;
    /** One trip round the orbit, unhurried. */
    private static final double PERIOD_MS = 3000;

    private final Circle sphere = new Circle(SPHERE_RADIUS);
    private final Arc back = new Arc(0, 0, ORBIT_RX, ORBIT_RY, 0, 180);
    private final Arc front = new Arc(0, 0, ORBIT_RX, ORBIT_RY, 180, 180);
    /** The moon on the far half of the orbit: smaller, dimmer, and drawn behind the sphere. */
    private final Circle moonBehind = new Circle(FAR_MOON_RADIUS);
    /** The moon on the near half, drawn in front of the sphere. */
    private final Circle moon = new Circle(MOON_RADIUS);
    private final StackPane mark = new StackPane();
    private final Group orbit = new Group();
    private final DoubleProperty angle = new SimpleDoubleProperty(this, "angle", REST_ANGLE);
    private final Timeline travel;
    private final ChangeListener<Boolean> motionListener = (obs, was, reduced) -> animate();
    private AssistantState state;

    public AssistantOrbit() {
        getStyleClass().add("assistant-orbit");
        setMinSize(WIDTH, HEIGHT);
        setPrefSize(WIDTH, HEIGHT);
        setMaxSize(WIDTH, HEIGHT);
        setMouseTransparent(true);
        sphere.getStyleClass().add("orbit-sphere");
        for (Arc arc : new Arc[] {back, front}) {
            arc.setType(ArcType.OPEN);
            arc.getStyleClass().add("orbit-line");
        }
        back.getStyleClass().add("back");
        moon.getStyleClass().add("orbit-moon");
        moonBehind.getStyleClass().addAll("orbit-moon", "far");
        mark.getStyleClass().add("orbit-mark");
        mark.setMinSize(SPHERE_RADIUS * 2, SPHERE_RADIUS * 2);
        mark.setMaxSize(SPHERE_RADIUS * 2, SPHERE_RADIUS * 2);
        mark.setLayoutX(-SPHERE_RADIUS);
        mark.setLayoutY(-SPHERE_RADIUS);
        orbit.getTransforms().add(new Rotate(TILT, 0, 0));
        orbit.setLayoutX(WIDTH / 2);
        orbit.setLayoutY(HEIGHT / 2);
        // A fixed drawing order; the moon changes sides by showing one of its two circles.
        orbit.getChildren().setAll(back, moonBehind, sphere, mark, front, moon);
        getChildren().add(orbit);
        angle.addListener((obs, old, value) -> placeMoon());
        // The moon sets off from its resting place along the near side, so it is in view as it starts.
        travel = new Timeline(
                new KeyFrame(Duration.ZERO, new KeyValue(angle, 360, Interpolator.LINEAR)),
                new KeyFrame(Duration.millis(PERIOD_MS), new KeyValue(angle, 0, Interpolator.LINEAR)));
        travel.setCycleCount(Animation.INDEFINITE);
        sceneProperty().addListener((obs, old, scene) -> animate());
        Motion.reduceMotionProperty().addListener(new WeakChangeListener<>(motionListener));
        placeMoon();
        show(AssistantState.STARTING);
    }

    /** The state drawn, or null before the first one. */
    public AssistantState state() {
        return state;
    }

    /** True while the moon is travelling (tests). */
    public boolean isTravelling() {
        return travel.getStatus() == Animation.Status.RUNNING;
    }

    /** Draws {@code value}. */
    public void show(AssistantState value) {
        Objects.requireNonNull(value, "value");
        state = value;
        getStyleClass().removeAll("starting", "ready", "unavailable");
        switch (value) {
            case STARTING -> {
                getStyleClass().add("starting");
                mark.getChildren().clear();
            }
            case READY -> {
                getStyleClass().add("ready");
                mark.getChildren().setAll(icon(Feather.CHECK));
            }
            case UNAVAILABLE, FAILED -> {
                getStyleClass().add("unavailable");
                mark.getChildren().setAll(icon(Feather.ALERT_TRIANGLE));
            }
        }
        animate();
    }

    private static FontIcon icon(Feather glyph) {
        FontIcon icon = Icons.of(glyph, 22);
        icon.getStyleClass().add("orbit-icon");
        return icon;
    }

    private void animate() {
        boolean move = state == AssistantState.STARTING && getScene() != null && !Motion.reduced();
        if (move && travel.getStatus() != Animation.Status.RUNNING) {
            travel.playFrom(Duration.millis(PERIOD_MS * (360 - REST_ANGLE) / 360));
        } else if (!move && travel.getStatus() == Animation.Status.RUNNING) {
            travel.stop();
        }
        if (!move) {
            angle.set(REST_ANGLE);
        }
    }

    /** Puts the moon on the orbit at the current angle, smaller and dimmer on the far half. */
    private void placeMoon() {
        double radians = Math.toRadians(angle.get());
        double x = ORBIT_RX * Math.cos(radians);
        double y = -ORBIT_RY * Math.sin(radians);
        boolean behind = Math.sin(radians) > 0;
        for (Circle circle : new Circle[] {moon, moonBehind}) {
            circle.setCenterX(x);
            circle.setCenterY(y);
        }
        moonBehind.setVisible(behind);
        moon.setVisible(!behind);
    }

    /** Where the moon is drawn, in the drawing's centre-based coordinates, and whether it is behind (tests). */
    double[] moonPosition() {
        return new double[] {moon.getCenterX(), moon.getCenterY(), moonBehind.isVisible() ? 1 : 0};
    }

    @Override
    protected void layoutChildren() {
        // The drawing has a fixed size; nothing to lay out.
    }
}
