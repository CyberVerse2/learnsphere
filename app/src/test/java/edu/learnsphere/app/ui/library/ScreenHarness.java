package edu.learnsphere.app.ui.library;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.core.service.ThemePreference;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.testfx.api.FxRobot;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The main window of a test app on a TestFX stage, for the Home, Library and course page tests: shows a
 * destination, waits for background loads, and closes everything at the end.
 */
public final class ScreenHarness {

    private final FxRobot robot;
    private MainWindow window;
    private Theme theme;

    public ScreenHarness(FxRobot robot) {
        this.robot = robot;
    }

    /** Builds the main window for {@code context} on {@code stage} and shows {@code destination}. */
    public MainWindow show(Stage stage, AppContext context, Destination destination) {
        robot.interact(() -> {
            Typography.load();
            window = new MainWindow(context);
            Scene scene = new Scene(window, 1360, 860);
            theme = Theme.install(scene, ThemePreference.LIGHT);
            stage.setScene(scene);
            stage.show();
            window.navigator().reset(destination);
        });
        settle();
        return window;
    }

    public MainWindow window() {
        return window;
    }

    /** The view on screen, as {@code type}. */
    public <V extends View> V view(Class<V> type) {
        View view = window.navigator().currentView();
        assertThat(view).isInstanceOf(type);
        return type.cast(view);
    }

    /** Waits for background loads and the FX updates they queue. */
    public static void settle() {
        for (int round = 0; round < 3; round++) {
            assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(20))).isTrue();
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    /** Waits up to 10 seconds for {@code condition}, checked on the FX thread. */
    public static void until(BooleanSupplier condition) {
        try {
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(condition::getAsBoolean)
                    .get(5, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            throw new AssertionError("Timed out waiting for the screen to update", e);
        }
        settle();
    }

    public void close() {
        if (window != null) {
            robot.interact(() -> {
                window.dispose();
                theme.dispose();
            });
        }
    }
}
