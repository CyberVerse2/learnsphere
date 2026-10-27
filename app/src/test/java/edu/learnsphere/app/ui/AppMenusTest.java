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
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/** The in-window menus: their shortcuts, where they go, and what needs an open profile. */
@ExtendWith(ApplicationExtension.class)
class AppMenusTest {

    private AppContext context;
    private MainWindow window;
    private AppMenus menus;
    private Scene scene;

    @Start
    void start(Stage stage) throws Exception {
        Path home = Files.createTempDirectory("learnsphere-menus");
        context = new AppContext(AppPaths.at(home), Clock.systemDefaultZone(), true, DemoWorkspace.instantFactory());
        DemoData.seedProfiles(context);
        context.openLastProfile().orElseThrow();
        Typography.load();
        window = new MainWindow(context);
        menus = new AppMenus(window, false);
        BorderPane root = new BorderPane(window);
        root.setTop(menus.bar());
        scene = new Scene(root, 1360, 860);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown(FxRobot robot) {
        robot.interact(() -> {
            menus.dispose();
            window.dispose();
        });
        context.close();
    }

    @Test
    void offersFileGoAndViewMenusInTheWindow() {
        assertThat(menus.bar().getMenus()).extracting(Menu::getText).containsExactly("File", "Go", "View");
        assertThat(menus.bar().isUseSystemMenuBar()).isFalse();
        assertThat(menus.bar().getStyleClass()).contains("in-window");
        assertThat(item("Quit LearnSphere").getAccelerator()).isEqualTo(shortcut(KeyCode.Q));
        assertThat(item("Components").getAccelerator())
                .as("the gallery in demo mode")
                .isEqualTo(new KeyCodeCombination(KeyCode.G, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
    }

    @Test
    void shortcutsGoToEachPlace(FxRobot robot) {
        settle();

        assertThat(item("Library").getAccelerator()).isEqualTo(shortcut(KeyCode.DIGIT2));
        robot.interact(() -> scene.getAccelerators().get(shortcut(KeyCode.DIGIT2)).run());
        assertThat(window.navigator().current()).isEqualTo(Destination.LIBRARY);

        robot.interact(() -> scene.getAccelerators().get(shortcut(KeyCode.DIGIT3)).run());
        assertThat(window.navigator().current()).isEqualTo(Destination.Progress.overview());

        robot.interact(() -> scene.getAccelerators().get(shortcut(KeyCode.DIGIT4)).run());
        assertThat(window.navigator().current()).isEqualTo(Destination.Studio.courses());

        robot.interact(() -> scene.getAccelerators().get(shortcut(KeyCode.COMMA)).run());
        assertThat(window.navigator().current()).isEqualTo(Destination.SETTINGS);

        robot.interact(() -> fire("Home"));
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);

        robot.interact(() -> fire("Start studying"));
        assertThat(window.navigator().currentRoute()).isEqualTo(Route.STUDY);
    }

    @Test
    void backFollowsTheHistory(FxRobot robot) {
        settle();
        MenuItem back = item("Back");
        assertThat(back.getAccelerator()).isEqualTo(shortcut(KeyCode.OPEN_BRACKET));
        assertThat(back.isDisable()).as("nothing to go back to").isTrue();

        robot.interact(() -> fire("Library"));
        assertThat(back.isDisable()).isFalse();

        robot.interact(() -> fire("Back"));
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
        assertThat(back.isDisable()).isTrue();
    }

    @Test
    void placesNeedAnOpenProfile(FxRobot robot) throws TimeoutException {
        settle();
        List<String> needProfile = List.of("Settings", "Home", "Library", "Progress", "Studio", "Start studying");
        assertThat(needProfile).allSatisfy(label -> assertThat(item(label).isDisable()).as(label).isFalse());

        context.closeWorkspace();
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> context.workspace() == null);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(needProfile).allSatisfy(label -> assertThat(item(label).isDisable()).as(label).isTrue());
        assertThat(item("Switch profile").isDisable()).isFalse();
        robot.interact(() -> fire("Switch profile"));
        assertThat(window.navigator().current()).isEqualTo(Destination.PROFILES);
    }

    @Test
    void appearanceFollowsAndChangesThePreference(FxRobot robot) throws TimeoutException {
        RadioMenuItem dark = (RadioMenuItem) item("Dark");
        RadioMenuItem matchComputer = (RadioMenuItem) item("Match computer");
        assertThat(context.preferences().get().theme()).isEqualTo(ThemePreference.SYSTEM);
        assertThat(matchComputer.isSelected()).isTrue();

        robot.interact(dark::fire);
        WaitForAsyncUtils.waitFor(5, TimeUnit.SECONDS, () -> context.preferences().get().theme() == ThemePreference.DARK);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(dark.isSelected()).isTrue();
        assertThat(matchComputer.isSelected()).isFalse();
    }

    private MenuItem item(String label) {
        return menus.bar().getMenus().stream()
                .flatMap(menu -> items(menu.getItems()))
                .filter(item -> label.equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No menu item " + label));
    }

    private static Stream<MenuItem> items(List<MenuItem> list) {
        return list.stream().flatMap(item -> item instanceof Menu sub
                ? Stream.concat(Stream.of(item), items(sub.getItems())) : Stream.of(item));
    }

    private void fire(String label) {
        item(label).fire();
    }

    private static KeyCombination shortcut(KeyCode key) {
        return new KeyCodeCombination(key, KeyCombination.SHORTCUT_DOWN);
    }

    private static void settle() {
        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(10))).isTrue();
        WaitForAsyncUtils.waitForFxEvents();
    }
}
