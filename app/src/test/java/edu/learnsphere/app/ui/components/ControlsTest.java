package edu.learnsphere.app.ui.components;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.error.UserMessage;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javafx.css.PseudoClass;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Behaviour of the small controls: progress easing, choices and status pills. */
@ExtendWith(ApplicationExtension.class)
class ControlsTest {

    private enum Size { SMALL, MEDIUM, LARGE }

    private VBox root;
    private Scene scene;

    @Start
    void start(Stage stage) {
        Typography.load();
        root = new VBox(12);
        scene = new Scene(root, 640, 480);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void restoreMotion(FxRobot robot) {
        robot.interact(() -> Motion.reduceMotionProperty().set(false));
    }

    @Test
    void progressRingEasesToItsValue(FxRobot robot) throws TimeoutException {
        ProgressRing ring = new ProgressRing(80, 8);
        robot.interact(() -> {
            root.getChildren().add(ring);
            ring.setProgress(0.6);
        });

        assertThat(ring.getProgress()).isEqualTo(0.6);
        assertThat(ring.shownProgress()).as("still easing in").isLessThan(0.6);
        WaitForAsyncUtils.waitFor(2, TimeUnit.SECONDS, () -> Math.abs(ring.shownProgress() - 0.6) < 1e-6);
        assertThat(ring.getAccessibleText()).isEqualTo("60% complete");
    }

    @Test
    void progressJumpsWhenMotionIsReduced(FxRobot robot) {
        ProgressRing ring = new ProgressRing(80, 8);
        ProgressBarSlim bar = new ProgressBarSlim();
        robot.interact(() -> {
            Motion.reduceMotionProperty().set(true);
            root.getChildren().addAll(ring, bar);
            ring.setProgress(0.4);
            bar.setProgress(0.75);
        });

        assertThat(ring.shownProgress()).isEqualTo(0.4);
        assertThat(bar.shownProgress()).isEqualTo(0.75);
    }

    @Test
    void progressIsClampedAndMarksCompletion(FxRobot robot) {
        ProgressRing ring = new ProgressRing(80, 8);
        ProgressBarSlim bar = new ProgressBarSlim();
        robot.interact(() -> {
            ring.setProgressNow(1.7);
            bar.setProgressNow(-0.2);
        });

        assertThat(ring.shownProgress()).isEqualTo(1.0);
        assertThat(ring.getPseudoClassStates()).contains(PseudoClass.getPseudoClass("complete"));
        assertThat(bar.shownProgress()).isEqualTo(0.0);
    }

    @Test
    void segmentedControlAlwaysHasOneChoice(FxRobot robot) {
        SegmentedControl<Size> sizes = new SegmentedControl<>(List.of(Size.values()), Size::name);
        robot.interact(() -> root.getChildren().add(sizes));
        assertThat(sizes.getValue()).isEqualTo(Size.SMALL);

        robot.clickOn(sizes.button(Size.LARGE));
        assertThat(sizes.getValue()).isEqualTo(Size.LARGE);

        robot.clickOn(sizes.button(Size.LARGE));
        assertThat(sizes.button(Size.LARGE).isSelected()).as("clicking the choice keeps it").isTrue();

        robot.interact(() -> sizes.setValue(Size.MEDIUM));
        assertThat(sizes.button(Size.MEDIUM).isSelected()).isTrue();
        assertThat(sizes.button(Size.LARGE).isSelected()).isFalse();
    }

    @Test
    void segmentedControlMovesWithArrowKeys(FxRobot robot) {
        SegmentedControl<Size> sizes = new SegmentedControl<>(List.of(Size.values()), Size::name);
        robot.interact(() -> root.getChildren().add(sizes));
        robot.clickOn(sizes.button(Size.SMALL));

        robot.type(KeyCode.RIGHT);
        assertThat(sizes.getValue()).isEqualTo(Size.MEDIUM);
        robot.type(KeyCode.LEFT);
        robot.type(KeyCode.LEFT);
        assertThat(sizes.getValue()).isEqualTo(Size.SMALL);
    }

    @Test
    void confidenceStartsUnsetAndKeepsAChoice(FxRobot robot) {
        ConfidenceChips chips = new ConfidenceChips();
        robot.interact(() -> root.getChildren().add(chips));
        assertThat(chips.getConfidence()).isNull();

        robot.clickOn(ConfidenceChips.label(Confidence.FAIRLY_SURE));
        assertThat(chips.getConfidence()).isEqualTo(Confidence.FAIRLY_SURE);

        robot.clickOn(ConfidenceChips.label(Confidence.FAIRLY_SURE));
        assertThat(chips.getConfidence()).as("clicking the choice keeps it").isEqualTo(Confidence.FAIRLY_SURE);

        robot.interact(() -> chips.setConfidence(Confidence.CERTAIN));
        assertThat(chips.chip(Confidence.CERTAIN).isSelected()).isTrue();
        robot.interact(() -> chips.setConfidence(null));
        assertThat(chips.chip(Confidence.CERTAIN).isSelected()).isFalse();
    }

    @Test
    void statusPillDescribesEachImportStep() {
        assertThat(StatusPill.statusOf(DocumentStatus.QUEUED)).isEqualTo(StatusPill.Status.WORKING);
        assertThat(StatusPill.statusOf(DocumentStatus.INDEXING)).isEqualTo(StatusPill.Status.WORKING);
        assertThat(StatusPill.statusOf(DocumentStatus.NEEDS_REVIEW)).isEqualTo(StatusPill.Status.NEEDS_REVIEW);
        assertThat(StatusPill.statusOf(DocumentStatus.FAILED)).isEqualTo(StatusPill.Status.FAILED);
        assertThat(StatusPill.describe(DocumentStatus.EXTRACTING, 14, 120)).isEqualTo("Reading your PDF… page 14 of 120");
        assertThat(StatusPill.describe(DocumentStatus.EXTRACTING, 0, 0)).isEqualTo("Reading your PDF…");
        assertThat(StatusPill.describe(DocumentStatus.READY, 0, 0)).isEqualTo("Ready");
    }

    @Test
    void failedPillExplainsWhyOnHover(FxRobot robot) {
        StatusPill pill = new StatusPill(StatusPill.Status.WORKING);
        robot.interact(() -> {
            root.getChildren().add(pill);
            pill.show(DocumentStatus.FAILED, 0, 0, UserMessage.of("This PDF is locked. Open it, save an unlocked copy and add that."));
        });

        assertThat(pill.status()).isEqualTo(StatusPill.Status.FAILED);
        assertThat(pill.text()).isEqualTo("Couldn't import");
        assertThat(pill.tooltipText()).isEqualTo("This PDF is locked. Open it, save an unlocked copy and add that.");
        assertThat(pill.getStyleClass()).contains("tone-danger");

        robot.interact(() -> pill.set(StatusPill.Status.READY));
        assertThat(pill.tooltipText()).isNull();
        assertThat(pill.getStyleClass()).contains("tone-success").doesNotContain("tone-danger");
    }

    @Test
    void themeFollowsThePreferenceAndScopes(FxRobot robot) throws Exception {
        Theme theme = onFx(() -> Theme.install(new Scene(new VBox()), ThemePreference.DARK));
        assertThat(theme.preference()).isEqualTo(ThemePreference.DARK);
        robot.interact(() -> theme.setPreference(ThemePreference.LIGHT));
        assertThat(theme.preference()).isEqualTo(ThemePreference.LIGHT);
        robot.interact(theme::dispose);

        VBox darkPanel = new VBox();
        darkPanel.getStyleClass().add(Theme.SCOPE_DARK);
        robot.interact(() -> root.getChildren().add(darkPanel));
        assertThat(Theme.isDark(darkPanel)).isTrue();
        assertThat(Theme.isDark(root)).isFalse();
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> action) throws Exception {
        return WaitForAsyncUtils.asyncFx(action).get(5, java.util.concurrent.TimeUnit.SECONDS);
    }
}
