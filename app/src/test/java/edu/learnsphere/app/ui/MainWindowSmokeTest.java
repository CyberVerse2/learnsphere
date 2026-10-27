package edu.learnsphere.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.dev.DemoData;
import edu.learnsphere.app.dev.DemoWorkspace;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.service.SessionRequest;
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
        assertThat(texts(robot, ".text-display")).anyMatch(text -> text.startsWith("Good ") && text.endsWith("Amara"));
        assertThat(texts(robot, ".profile-chip-name")).containsExactly(DemoData.PROFILE_NAME);
        assertThat(texts(robot, ".sidebar-course-title"))
                .containsExactly("Operating Systems Principles", "Research Methods in Computing");
    }

    @Test
    void navigatesFromTheSidebarAndBack(FxRobot robot) {
        settle();

        robot.clickOn("Library");
        assertThat(window.navigator().current()).isEqualTo(Destination.LIBRARY);

        robot.interact(() -> window.navigator().navigate(new Destination.Course(DemoData.OPERATING_SYSTEMS)));
        settle();
        assertThat(window.topBar().crumbTexts()).containsExactly("Library", "Operating Systems Principles");

        robot.clickOn(".profile-chip");
        assertThat(window.navigator().currentRoute()).isEqualTo(Route.PROFILES);
        assertThat(window.isSidebarShown()).as("sidebar hidden on full-screen routes").isFalse();
        assertThat(window.topBar().isVisible()).isFalse();

        robot.interact(() -> window.navigator().back());
        assertThat(window.isSidebarShown()).isTrue();
        assertThat(window.navigator().current()).isInstanceOf(Destination.Course.class);
    }

    @Test
    void studyIsAFocusModeWithoutTheSidebar(FxRobot robot) {
        settle();

        robot.interact(() -> window.navigator().navigate(new Destination.Study(SessionRequest.studyNext())));
        settle();

        assertThat(window.isSidebarShown()).isFalse();
        assertThat(window.topBar().isVisible()).isFalse();
        assertThat(robot.lookup(".study-prompt").tryQuery()).isPresent();

        robot.interact(() -> window.navigator().navigate(Destination.HOME));
        assertThat(window.isSidebarShown()).isTrue();
    }

    @Test
    void offersTheComponentGalleryInDemoMode(FxRobot robot) {
        settle();

        robot.interact(() -> window.navigator().navigate(Destination.GALLERY));

        assertThat(window.navigator().currentView()).isInstanceOf(edu.learnsphere.app.dev.ComponentGallery.class);
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
