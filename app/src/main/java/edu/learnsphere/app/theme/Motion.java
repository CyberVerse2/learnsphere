package edu.learnsphere.app.theme;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.Node;
import javafx.util.Duration;

/**
 * Standard transitions from {@code docs/DESIGN.md}. When {@link #reduceMotionProperty() reduce motion} is
 * on, movement is replaced by fades. FX thread only.
 */
public final class Motion {

    /** Ease-out used for entrances and lifts. */
    public static final Interpolator EASE_OUT = Interpolator.ofSpline(0.16, 1, 0.3, 1);
    public static final Duration ROUTE = Duration.millis(180);
    public static final Duration HOVER = Duration.millis(150);
    public static final Duration PROGRESS = Duration.millis(600);

    private static final BooleanProperty REDUCE_MOTION = new SimpleBooleanProperty(false);

    private Motion() {
    }

    /** Mirrors the "Reduce motion" setting. */
    public static BooleanProperty reduceMotionProperty() {
        return REDUCE_MOTION;
    }

    public static boolean reduced() {
        return REDUCE_MOTION.get();
    }

    /** Fades {@code node} in while it rises 8 px (fade only when motion is reduced). Plays immediately. */
    public static Animation enter(Node node) {
        FadeTransition fade = new FadeTransition(ROUTE, node);
        fade.setFromValue(0);
        fade.setToValue(1);
        fade.setInterpolator(EASE_OUT);
        Animation animation = fade;
        if (!reduced()) {
            TranslateTransition rise = new TranslateTransition(ROUTE, node);
            rise.setFromY(8);
            rise.setToY(0);
            rise.setInterpolator(EASE_OUT);
            animation = new ParallelTransition(fade, rise);
        }
        animation.play();
        return animation;
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
}
