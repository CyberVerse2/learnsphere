package edu.learnsphere.app.theme;

import java.util.Objects;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.SequentialTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.WritableValue;
import javafx.scene.Node;
import javafx.scene.shape.Shape;
import javafx.util.Duration;

/**
 * The standard motion of {@code docs/DESIGN.md}: route entrances, values easing to their target, the wrong-answer
 * shake, the flashcard flip and the drawn-in check.
 *
 * <p>When motion is reduced ({@link #reduceMotionProperty() the app setting} or the operating system's own
 * preference), movement is replaced by fades and values jump to their target. Every helper plays at once and
 * returns its animation (or null when nothing animates) so callers can stop it. FX thread only.
 */
public final class Motion {

    /** Ease-out used for entrances and lifts. */
    public static final Interpolator EASE_OUT = Interpolator.ofSpline(0.16, 1, 0.3, 1);
    /** Symmetric ease for movements that return to where they started. */
    public static final Interpolator EASE_IN_OUT = Interpolator.ofSpline(0.45, 0, 0.25, 1);
    /** Route change: fade in while rising {@link #RISE} pixels. */
    public static final Duration ROUTE = Duration.millis(180);
    /** Card hover lift and other small state changes. */
    public static final Duration HOVER = Duration.millis(150);
    /** Progress rings and bars easing to their value. */
    public static final Duration PROGRESS = Duration.millis(600);
    /** A flashcard flip: half to scale out, half to scale back in. */
    public static final Duration FLIP = Duration.millis(220);
    /** The wrong-answer shake. */
    public static final Duration SHAKE = Duration.millis(320);
    /** How far a route rises as it fades in. */
    public static final double RISE = 8;
    /** How far the wrong-answer shake moves. */
    public static final double SHAKE_DISTANCE = 6;

    private static final BooleanProperty REDUCE_MOTION = new SimpleBooleanProperty(false);

    private Motion() {
    }

    /** Mirrors the "Reduce motion" setting of the app. */
    public static BooleanProperty reduceMotionProperty() {
        return REDUCE_MOTION;
    }

    /** True when the app setting or the operating system asks for reduced motion. */
    public static boolean reduced() {
        return REDUCE_MOTION.get() || systemReducesMotion();
    }

    private static boolean systemReducesMotion() {
        try {
            return Platform.isFxApplicationThread() && Platform.getPreferences().isReducedMotion();
        } catch (RuntimeException | Error e) {
            // The toolkit is not running (plain unit tests): nothing to follow.
            return false;
        }
    }

    /** Fades {@code node} in while it rises 8 px (fade only when motion is reduced). */
    public static Animation enter(Node node) {
        FadeTransition fade = new FadeTransition(ROUTE, node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(EASE_OUT);
        Animation animation = fade;
        if (!reduced()) {
            TranslateTransition rise = new TranslateTransition(ROUTE, node);
            rise.setFromY(RISE);
            rise.setToY(0);
            rise.setInterpolator(EASE_OUT);
            animation = new ParallelTransition(fade, rise);
        }
        animation.play();
        return animation;
    }

    /** Fades {@code node} in over {@code duration}. */
    public static Animation fadeIn(Node node, Duration duration) {
        FadeTransition fade = new FadeTransition(duration, node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(EASE_OUT);
        fade.play();
        return fade;
    }

    /** Fades {@code node} out over {@code duration}, then runs {@code onFinished} (may be null). */
    public static Animation fadeOut(Node node, Duration duration, Runnable onFinished) {
        FadeTransition fade = new FadeTransition(duration, node);
        fade.setToValue(0);
        fade.setInterpolator(Interpolator.EASE_IN);
        if (onFinished != null) {
            fade.setOnFinished(event -> onFinished.run());
        }
        fade.play();
        return fade;
    }

    /** Moves {@code node} to {@code y} over {@link #HOVER} (jumps when motion is reduced). */
    public static void liftTo(Node node, double y) {
        if (reduced()) {
            node.setTranslateY(y);
            return;
        }
        TranslateTransition lift = new TranslateTransition(HOVER, node);
        lift.setToY(y);
        lift.setInterpolator(EASE_OUT);
        lift.play();
    }

    /**
     * Eases {@code value} to {@code target} over {@code duration}; sets it at once when motion is reduced.
     *
     * @return the running animation, or null when the value was set directly
     */
    public static Animation animate(WritableValue<Number> value, double target, Duration duration) {
        Objects.requireNonNull(value, "value");
        if (reduced() || duration.lessThanOrEqualTo(Duration.ZERO)) {
            value.setValue(target);
            return null;
        }
        Timeline timeline = new Timeline(new KeyFrame(duration, new KeyValue(value, target, EASE_OUT)));
        timeline.play();
        return timeline;
    }

    /** Eases a progress value to {@code target} over {@link #PROGRESS}. */
    public static Animation animateProgress(DoubleProperty value, double target) {
        return animate(value, target, PROGRESS);
    }

    /**
     * The wrong-answer moment: a single 6 px shake. With reduced motion the node dims briefly instead of
     * moving.
     */
    public static Animation shake(Node node) {
        Objects.requireNonNull(node, "node");
        Animation animation;
        if (reduced()) {
            FadeTransition dim = new FadeTransition(Duration.millis(120), node);
            dim.setFromValue(1);
            dim.setToValue(0.55);
            dim.setAutoReverse(true);
            dim.setCycleCount(2);
            animation = dim;
        } else {
            double d = SHAKE_DISTANCE;
            double step = SHAKE.toMillis() / 4;
            Timeline shake = new Timeline(
                    new KeyFrame(Duration.ZERO, new KeyValue(node.translateXProperty(), 0)),
                    new KeyFrame(Duration.millis(step), new KeyValue(node.translateXProperty(), -d, EASE_IN_OUT)),
                    new KeyFrame(Duration.millis(step * 2), new KeyValue(node.translateXProperty(), d, EASE_IN_OUT)),
                    new KeyFrame(Duration.millis(step * 3), new KeyValue(node.translateXProperty(), -d / 2, EASE_IN_OUT)),
                    new KeyFrame(SHAKE, new KeyValue(node.translateXProperty(), 0, EASE_IN_OUT)));
            animation = shake;
        }
        animation.play();
        return animation;
    }

    /**
     * Flips a card: {@code card} scales horizontally to nothing, {@code swap} replaces the face, and the card
     * scales back while the new face fades in (220 ms in all). With reduced motion the faces cross-fade.
     *
     * @param card the node that flips (holds both faces)
     * @param swap shows the other face; runs halfway through
     */
    public static Animation flip(Node card, Runnable swap) {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(swap, "swap");
        Duration half = FLIP.divide(2);
        Animation animation;
        if (reduced()) {
            FadeTransition out = new FadeTransition(half, card);
            out.setToValue(0);
            out.setOnFinished(event -> swap.run());
            FadeTransition in = new FadeTransition(half, card);
            in.setFromValue(0);
            in.setToValue(1);
            animation = new SequentialTransition(out, in);
        } else {
            ScaleTransition shrink = new ScaleTransition(half, card);
            shrink.setFromX(1);
            shrink.setToX(0);
            shrink.setInterpolator(Interpolator.EASE_IN);
            shrink.setOnFinished(event -> {
                swap.run();
                card.setOpacity(0.4);
            });
            ScaleTransition grow = new ScaleTransition(half, card);
            grow.setFromX(0);
            grow.setToX(1);
            grow.setInterpolator(EASE_OUT);
            FadeTransition fade = new FadeTransition(half, card);
            fade.setFromValue(0.4);
            fade.setToValue(1);
            animation = new SequentialTransition(shrink, new ParallelTransition(grow, fade));
        }
        animation.play();
        return animation;
    }

    /**
     * Draws a stroked shape in along its outline (the correct-answer check). {@code length} is the outline's
     * length in pixels. With reduced motion the shape fades in.
     */
    public static Animation drawIn(Shape shape, double length, Duration duration) {
        Objects.requireNonNull(shape, "shape");
        if (reduced()) {
            shape.getStrokeDashArray().clear();
            return fadeIn(shape, duration);
        }
        shape.getStrokeDashArray().setAll(length, length);
        shape.setStrokeDashOffset(length);
        Timeline draw = new Timeline(new KeyFrame(duration,
                new KeyValue(shape.strokeDashOffsetProperty(), 0, EASE_OUT)));
        draw.setOnFinished(event -> shape.getStrokeDashArray().clear());
        draw.play();
        return draw;
    }
}
