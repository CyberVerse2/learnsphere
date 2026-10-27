package edu.learnsphere.app.theme;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Line;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** The motion helpers, with full motion and with reduce motion on (movement replaced by fades). */
@ExtendWith(ApplicationExtension.class)
class MotionTest {

    private StackPane root;

    @Start
    void start(Stage stage) {
        root = new StackPane();
        stage.setScene(new Scene(root, 320, 240));
        stage.show();
    }

    @AfterEach
    void restoreMotion(FxRobot robot) {
        robot.interact(() -> Motion.reduceMotionProperty().set(false));
    }

    @Test
    void followsTheReduceMotionSetting(FxRobot robot) {
        robot.interact(() -> {
            assertThat(Motion.reduced()).as("the test machine asks for full motion").isFalse();
            Motion.reduceMotionProperty().set(true);
            assertThat(Motion.reduced()).isTrue();
        });
    }

    @Test
    void routesRiseAsTheyFadeInUnlessMotionIsReduced(FxRobot robot) {
        Region full = node(robot);
        Region reduced = node(robot);
        AtomicReference<Animation> rising = new AtomicReference<>();
        AtomicReference<Animation> fading = new AtomicReference<>();
        robot.interact(() -> {
            rising.set(Motion.enter(full));
            rising.get().pause();
            rising.get().jumpTo(Duration.ZERO);
            Motion.reduceMotionProperty().set(true);
            fading.set(Motion.enter(reduced));
            fading.get().pause();
            fading.get().jumpTo(Duration.ZERO);
        });

        assertThat(rising.get()).isInstanceOf(ParallelTransition.class);
        assertThat(full.getTranslateY()).isEqualTo(Motion.RISE);
        assertThat(full.getOpacity()).isZero();
        assertThat(fading.get()).isInstanceOf(FadeTransition.class);
        assertThat(reduced.getTranslateY()).as("no movement").isZero();
        assertThat(reduced.getOpacity()).isZero();
    }

    @Test
    void shakeMovesSixPixelsAndReturns(FxRobot robot) {
        Region node = node(robot);
        AtomicReference<Animation> shake = new AtomicReference<>();
        robot.interact(() -> {
            shake.set(Motion.shake(node));
            shake.get().pause();
            shake.get().jumpTo(Motion.SHAKE.divide(4));
        });
        assertThat(node.getTranslateX()).isCloseTo(-Motion.SHAKE_DISTANCE, within(0.01));

        robot.interact(() -> shake.get().jumpTo(Motion.SHAKE.divide(2)));
        assertThat(node.getTranslateX()).isCloseTo(Motion.SHAKE_DISTANCE, within(0.01));

        robot.interact(() -> shake.get().jumpTo(Motion.SHAKE));
        assertThat(node.getTranslateX()).isZero();
        assertThat(node.getOpacity()).isEqualTo(1);
    }

    @Test
    void shakeDimsInPlaceWhenMotionIsReduced(FxRobot robot) {
        Region node = node(robot);
        AtomicReference<Animation> dim = new AtomicReference<>();
        robot.interact(() -> {
            Motion.reduceMotionProperty().set(true);
            dim.set(Motion.shake(node));
            dim.get().pause();
            dim.get().jumpTo(Duration.millis(120));
        });

        assertThat(dim.get()).isInstanceOf(FadeTransition.class);
        assertThat(node.getOpacity()).isCloseTo(0.55, within(0.01));
        assertThat(node.getTranslateX()).as("no movement").isZero();
    }

    @Test
    void flipScalesThroughTheEdgeAndSwapsFacesOnce(FxRobot robot) throws TimeoutException {
        Region card = node(robot);
        AtomicInteger swaps = new AtomicInteger();
        SimpleDoubleProperty narrowest = new SimpleDoubleProperty(1);
        robot.interact(() -> {
            card.scaleXProperty().addListener((obs, old, value) -> narrowest.set(Math.min(narrowest.get(), value.doubleValue())));
            Motion.flip(card, swaps::incrementAndGet);
        });

        WaitForAsyncUtils.waitFor(3, TimeUnit.SECONDS, () -> swaps.get() == 1 && card.getScaleX() == 1
                && card.getOpacity() == 1);
        assertThat(narrowest.get()).as("turned edge-on halfway").isLessThan(0.1);
        assertThat(swaps.get()).isEqualTo(1);
    }

    @Test
    void flipCrossFadesWhenMotionIsReduced(FxRobot robot) throws TimeoutException {
        Region card = node(robot);
        AtomicInteger swaps = new AtomicInteger();
        AtomicInteger scaleChanges = new AtomicInteger();
        SimpleDoubleProperty faintest = new SimpleDoubleProperty(1);
        robot.interact(() -> {
            Motion.reduceMotionProperty().set(true);
            card.scaleXProperty().addListener((obs, old, value) -> scaleChanges.incrementAndGet());
            card.opacityProperty().addListener((obs, old, value) -> faintest.set(Math.min(faintest.get(), value.doubleValue())));
            Motion.flip(card, swaps::incrementAndGet);
        });

        WaitForAsyncUtils.waitFor(3, TimeUnit.SECONDS, () -> swaps.get() == 1 && card.getOpacity() == 1);
        assertThat(scaleChanges.get()).as("no movement").isZero();
        assertThat(faintest.get()).as("faded through").isLessThan(0.5);
    }

    @Test
    void drawInTracesTheStrokeThenClearsTheDashes(FxRobot robot) throws TimeoutException {
        Line check = new Line(0, 0, 30, 0);
        robot.interact(() -> {
            root.getChildren().setAll(check);
            Motion.drawIn(check, 30, Duration.millis(200));
        });

        assertThat(check.getStrokeDashArray()).containsExactly(30.0, 30.0);
        WaitForAsyncUtils.waitFor(3, TimeUnit.SECONDS, () -> check.getStrokeDashArray().isEmpty());
        assertThat(check.getStrokeDashOffset()).isZero();
    }

    @Test
    void drawInFadesWhenMotionIsReduced(FxRobot robot) throws TimeoutException {
        Line check = new Line(0, 0, 30, 0);
        AtomicReference<Animation> fade = new AtomicReference<>();
        robot.interact(() -> {
            Motion.reduceMotionProperty().set(true);
            root.getChildren().setAll(check);
            check.getStrokeDashArray().setAll(5.0, 5.0);
            fade.set(Motion.drawIn(check, 30, Duration.millis(200)));
        });

        assertThat(fade.get()).isInstanceOf(FadeTransition.class);
        assertThat(check.getStrokeDashArray()).as("drawn whole").isEmpty();
        WaitForAsyncUtils.waitFor(3, TimeUnit.SECONDS, () -> check.getOpacity() == 1);
    }

    @Test
    void valuesEaseToTheirTargetOrJumpWhenMotionIsReduced(FxRobot robot) throws TimeoutException {
        DoubleProperty eased = new SimpleDoubleProperty(0);
        DoubleProperty jumped = new SimpleDoubleProperty(0);
        AtomicReference<Animation> easing = new AtomicReference<>();
        AtomicReference<Animation> none = new AtomicReference<>(null);
        robot.interact(() -> {
            easing.set(Motion.animateProgress(eased, 0.8));
            Motion.reduceMotionProperty().set(true);
            none.set(Motion.animateProgress(jumped, 0.8));
        });

        assertThat(none.get()).isNull();
        assertThat(jumped.get()).isEqualTo(0.8);
        assertThat(easing.get()).isNotNull();
        WaitForAsyncUtils.waitFor(3, TimeUnit.SECONDS, () -> Math.abs(eased.get() - 0.8) < 1e-9);
    }

    @Test
    void liftJumpsWhenMotionIsReduced(FxRobot robot) {
        Region node = node(robot);
        robot.interact(() -> {
            Motion.reduceMotionProperty().set(true);
            Motion.liftTo(node, -2);
        });

        assertThat(node.getTranslateY()).isEqualTo(-2);
    }

    private Region node(FxRobot robot) {
        Region node = new Region();
        node.setPrefSize(60, 40);
        robot.interact(() -> root.getChildren().add(node));
        return node;
    }
}
