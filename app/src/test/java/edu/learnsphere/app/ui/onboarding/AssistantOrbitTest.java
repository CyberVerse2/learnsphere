package edu.learnsphere.app.ui.onboarding;

import static edu.learnsphere.app.testing.TestWindows.fx;
import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.core.service.AssistantState;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/** The readiness drawing: the moon travels while the assistant gets ready and rests otherwise. */
@ExtendWith(ApplicationExtension.class)
class AssistantOrbitTest {

    private AssistantOrbit orbit;

    @Start
    void start(Stage stage) {
        orbit = new AssistantOrbit();
        stage.setScene(new Scene(new StackPane(orbit), 300, 200));
        stage.show();
    }

    @AfterEach
    void motionBack(FxRobot robot) {
        robot.interact(() -> Motion.reduceMotionProperty().set(false));
    }

    @Test
    void theMoonTravelsWhileGettingReadyAndRestsInFrontOnceReady(FxRobot robot) throws Exception {
        robot.interact(() -> orbit.show(AssistantState.STARTING));
        assertThat(fx(orbit::isTravelling)).isTrue();
        assertThat(orbit.getStyleClass()).contains("starting");
        double[] first = fx(orbit::moonPosition);
        Thread.sleep(400);
        double[] later = fx(orbit::moonPosition);
        assertThat(later[0] != first[0] || later[1] != first[1]).as("the moon moved").isTrue();

        robot.interact(() -> orbit.show(AssistantState.READY));
        assertThat(fx(orbit::isTravelling)).isFalse();
        double[] rest = fx(orbit::moonPosition);
        assertThat(rest[2]).as("in front of the sphere").isZero();
        assertThat(rest[0]).as("to the right of the sphere").isGreaterThan(20);
        assertThat(orbit.getStyleClass()).contains("ready").doesNotContain("starting");

        robot.interact(() -> orbit.show(AssistantState.FAILED));
        assertThat(orbit.getStyleClass()).contains("unavailable");
    }

    @Test
    void reducedMotionKeepsTheMoonStill(FxRobot robot) {
        robot.interact(() -> {
            Motion.reduceMotionProperty().set(true);
            orbit.show(AssistantState.STARTING);
        });
        assertThat(fx(orbit::isTravelling)).isFalse();
        robot.interact(() -> Motion.reduceMotionProperty().set(false));
        assertThat(fx(orbit::isTravelling)).isTrue();
    }
}
