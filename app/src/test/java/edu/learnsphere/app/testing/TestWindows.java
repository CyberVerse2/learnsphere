package edu.learnsphere.app.testing;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.SystemFiles;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.core.service.ThemePreference;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import org.testfx.api.FxRobot;
import org.testfx.util.WaitForAsyncUtils;

/** Shows the main window over a test's app context and waits for the screens to settle. */
public final class TestWindows {

    private TestWindows() {
    }

    /** A main window for {@code context} on {@code stage}, in the light theme, using {@code files}. */
    public static MainWindow show(FxRobot robot, Stage stage, AppContext context, SystemFiles files) {
        AtomicReference<MainWindow> window = new AtomicReference<>();
        robot.interact(() -> {
            Typography.load();
            MainWindow main = new MainWindow(context);
            main.setSystemFiles(files);
            Scene scene = new Scene(main, 1360, 860);
            Theme.install(scene, ThemePreference.LIGHT);
            stage.setScene(scene);
            stage.show();
            window.set(main);
        });
        settle();
        return window.get();
    }

    /** Takes {@code window} down, as quitting does. */
    public static void dispose(FxRobot robot, MainWindow window) {
        if (window != null) {
            robot.interact(window::dispose);
        }
    }

    /** Waits until background work and the FX events it queued are done. */
    public static void settle() {
        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(15))).as("background work finished").isTrue();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(15))).as("background work finished").isTrue();
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** Waits up to ten seconds for {@code condition}, checked on the FX thread. */
    public static void waitFor(String what, BooleanSupplier condition) {
        try {
            WaitForAsyncUtils.waitFor(10, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(condition::getAsBoolean).get());
        } catch (TimeoutException e) {
            throw new AssertionError("Timed out waiting for " + what, e);
        }
        WaitForAsyncUtils.waitForFxEvents();
    }

    /** The screen on display, as {@code type}. */
    public static <T extends View> T current(MainWindow window, Class<T> type) {
        View view = window.navigator().currentView();
        assertThat(view).as("screen on display").isInstanceOf(type);
        return type.cast(view);
    }

    /** The texts of the visible labelled nodes matching {@code query}. */
    public static List<String> texts(FxRobot robot, String query) {
        return robot.lookup(query).queryAll().stream()
                .filter(Node::isVisible)
                .filter(TestWindows::shown)
                .filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText())
                .toList();
    }

    /** True when {@code node} and every parent is visible. */
    public static boolean shown(Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (!n.isVisible()) {
                return false;
            }
        }
        return node.getScene() != null;
    }
}
