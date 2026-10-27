package edu.learnsphere.app;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.dev.DemoData;
import edu.learnsphere.app.testing.TestWindows;
import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.settings.SettingsPage;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.service.ThemePreference;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The application as {@code runDemo} starts it: the title follows the open profile, Settings changes the
 * window's look at once, and quitting saves the window's place and closes the profile.
 */
class LearnSphereAppTest {

    @TempDir
    Path home;

    private Stage stage;
    private LearnSphereApp app;

    @BeforeEach
    void start() throws Exception {
        // A stage of its own: the app sizes and places it, which must not carry over to other tests.
        FxToolkit.registerPrimaryStage();
        stage = FxToolkit.registerStage(Stage::new);
        String oldHome = System.getProperty(AppPaths.HOME_PROPERTY);
        String oldDemo = System.getProperty(AppContext.DEMO_PROPERTY);
        System.setProperty(AppPaths.HOME_PROPERTY, home.toString());
        System.setProperty(AppContext.DEMO_PROPERTY, "true");
        try {
            app = (LearnSphereApp) FxToolkit.setupApplication(LearnSphereApp.class);
        } finally {
            restore(AppPaths.HOME_PROPERTY, oldHome);
            restore(AppContext.DEMO_PROPERTY, oldDemo);
        }
        TestWindows.settle();
    }

    @AfterEach
    void stop() throws Exception {
        if (app != null && !app.context().isClosed()) {
            WaitForAsyncUtils.asyncFx(app::shutDown).get(30, TimeUnit.SECONDS);
        }
        WaitForAsyncUtils.asyncFx(() -> {
            Motion.reduceMotionProperty().set(false);
            stage.hide();
        }).get(5, TimeUnit.SECONDS);
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void theTitleNamesTheOpenProfile() {
        assertThat(app.context().profile().displayName()).isEqualTo(DemoData.PROFILE_NAME);
        assertThat(stage.getTitle()).isEqualTo(DemoData.PROFILE_NAME + " — LearnSphere");
        assertThat(app.window().navigator().current()).isEqualTo(Destination.HOME);
    }

    @Test
    void settingsChangeTheLookAtOnce() {
        FxRobot robot = new FxRobot();
        robot.interact(() -> app.window().navigator().navigate(Destination.SETTINGS));
        TestWindows.settle();
        SettingsPage settings = TestWindows.current(app.window(), SettingsPage.class);
        assertThat(settings).isNotNull();
        assertThat(TestWindows.fx(() -> Theme.isDark(app.window()))).isFalse();

        robot.clickOn("Dark");
        TestWindows.waitFor("the dark theme", () -> Theme.isDark(app.window()));
        robot.clickOn(".toggle-switch");
        TestWindows.waitFor("reduced motion", Motion::reduced);
        assertThat(app.context().preferences().get().theme()).isEqualTo(ThemePreference.DARK);

        robot.clickOn("Light");
        TestWindows.waitFor("the light theme", () -> !Theme.isDark(app.window()));
    }

    @Test
    void quittingSavesTheWindowPlaceAndClosesTheProfile() throws Exception {
        WaitForAsyncUtils.asyncFx(() -> {
            stage.setX(140);
            stage.setY(90);
            stage.setWidth(1200);
            stage.setHeight(780);
        }).get(5, TimeUnit.SECONDS);
        WaitForAsyncUtils.waitForFxEvents();

        WaitForAsyncUtils.asyncFx(app::shutDown).get(30, TimeUnit.SECONDS);

        assertThat(app.context().isClosed()).isTrue();
        assertThat(app.context().workspace()).isNull();
        assertThat(app.context().profile()).isNull();
        WindowState saved = WindowStateStore.in(AppPaths.at(home)).load().orElseThrow();
        assertThat(saved).isEqualTo(new WindowState(140, 90, 1200, 780, false));
        assertThat(stage.titleProperty().isBound()).isFalse();
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
