package edu.learnsphere.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.dev.DemoData;
import edu.learnsphere.app.dev.DemoWorkspace;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.service.ThemePreference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** Boots the main window on the demo profile, as {@code runDemo} does, and walks the shell. */
@ExtendWith(ApplicationExtension.class)
class MainWindowSmokeTest {

    private AppContext context;
    private MainWindow window;

    @Start
    void start(Stage stage) throws Exception {
        Path home = Files.createTempDirectory("learnsphere-smoke");
        context = new AppContext(AppPaths.at(home), Clock.systemDefaultZone(), true, DemoWorkspace.factory());
        DemoData.seedProfiles(context);
        context.openLastProfile().orElseThrow();
        Typography.load();
        window = new MainWindow(context);
        Scene scene = new Scene(window, 1360, 860);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown(FxRobot robot) {
        robot.interact(window::dispose);
        context.close();
    }

    @Test
    void showsHomeWithTheDemoProfileAndItsCourses(FxRobot robot) {
        settle();

        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
        assertThat(texts(robot, ".text-display")).contains("Welcome to LearnSphere");
        assertThat(texts(robot, ".profile-chip-name")).containsExactly(DemoData.PROFILE_NAME);
        assertThat(texts(robot, ".sidebar-course"))
                .containsExactly("Cell Biology", "Organic Chemistry I", "Statistics for Research");
    }

    @Test
    void navigatesFromTheSidebarAndBack(FxRobot robot) {
        settle();

        robot.clickOn("Library");
        assertThat(window.navigator().current()).isEqualTo(Destination.LIBRARY);

        robot.clickOn("Cell Biology");
        assertThat(window.navigator().current()).isInstanceOf(Destination.Course.class);

        robot.clickOn(".profile-chip");
        assertThat(window.navigator().currentRoute()).isEqualTo(Route.PROFILES);
        assertThat(window.getLeft()).as("sidebar hidden on full-screen routes").isNull();

        robot.interact(() -> window.navigator().back());
        assertThat(window.getLeft()).isNotNull();
        assertThat(window.navigator().current()).isInstanceOf(Destination.Course.class);
    }

    private static void settle() {
        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(10))).isTrue();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(10))).isTrue();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static java.util.List<String> texts(FxRobot robot, String query) {
        return robot.lookup(query).queryAll().stream()
                .filter(Node::isVisible)
                .filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText())
                .toList();
    }
}
