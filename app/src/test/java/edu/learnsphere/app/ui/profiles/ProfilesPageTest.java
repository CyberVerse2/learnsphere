package edu.learnsphere.app.ui.profiles;

import static edu.learnsphere.app.testing.TestWindows.current;
import static edu.learnsphere.app.testing.TestWindows.fx;
import static edu.learnsphere.app.testing.TestWindows.settle;
import static edu.learnsphere.app.testing.TestWindows.texts;
import static edu.learnsphere.app.testing.TestWindows.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.testing.RecordingFiles;
import edu.learnsphere.app.testing.TestWindows;
import edu.learnsphere.app.ui.AppMenus;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.components.FormDialog;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.ProfileSummary;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.control.Button;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/**
 * The profile choice over real services: a protected profile that isn't open needs its password before it is
 * backed up, edited or deleted, and what the forms report reaches the window even when the page has changed.
 */
@ExtendWith(ApplicationExtension.class)
class ProfilesPageTest {

    private static final String WRONG = "That password isn't right. Try again.";

    @TempDir
    Path dir;

    private Stage stage;
    private RealWorkspaces app;
    private MainWindow window;
    private final RecordingFiles files = new RecordingFiles();

    @Start
    void start(Stage stage) {
        this.stage = stage;
    }

    @AfterEach
    void tearDown(FxRobot robot) {
        TestWindows.dispose(robot, window);
        if (app != null) {
            app.close();
        }
    }

    @Test
    void aProtectedProfileNeedsItsPasswordToBeBackedUpEditedOrDeleted(FxRobot robot) {
        app = RealWorkspaces.open(dir);
        ProfileSummary grace = app.context().profiles().create(
                new ProfileDraft("Grace Hopper", 30, "compiler".toCharArray()));
        Path zip = dir.resolve("grace.zip");
        files.saveTo(zip);
        window = TestWindows.show(robot, stage, app.context(), files);
        robot.interact(() -> window.navigator().navigate(Destination.PROFILES));
        settle();

        // Back up: the password first, and nothing is saved (or even asked where) until it is right.
        openMenu(robot, "Grace Hopper");
        robot.clickOn("Back up…");
        settle();
        assertThat(texts(robot, ".modal-title")).containsExactly("Back up Grace Hopper");
        robot.write("compile").type(KeyCode.ENTER);
        settle();
        assertThat(texts(robot, ".form-dialog-error")).containsExactly(WRONG);
        assertThat(files.suggestedNames()).isEmpty();
        robot.write("compiler").type(KeyCode.ENTER);
        waitFor("the backup", () -> texts(robot, ".toast-message").contains("Backup saved"));
        assertThat(zip).exists();

        // Edit: Save waits for the password; a wrong one changes nothing.
        openMenu(robot, "Grace Hopper");
        robot.clickOn("Edit name and colour");
        settle();
        TextField name = robot.lookup(".profile-name-field").queryAs(TextField.class);
        robot.interact(name::clear);
        robot.clickOn(name).write("Grace Murray Hopper");
        Button save = robot.lookup(".modal-confirm").queryButton();
        assertThat(fx(save::isDisabled)).as("the password is needed").isTrue();
        robot.clickOn(".profile-password-check").write("cobol").type(KeyCode.ENTER);
        settle();
        assertThat(texts(robot, ".form-dialog-error")).containsExactly(WRONG);
        assertThat(app.context().profiles().find(grace.id()).orElseThrow().displayName()).isEqualTo("Grace Hopper");
        robot.clickOn(".profile-password-check").write("compiler").type(KeyCode.ENTER);
        settle();
        assertThat(app.context().profiles().find(grace.id()).orElseThrow().displayName())
                .isEqualTo("Grace Murray Hopper");

        // Delete: the name and the password.
        openMenu(robot, "Grace Murray Hopper");
        robot.clickOn("Delete profile…");
        settle();
        robot.clickOn(".delete-confirm-field").write("Grace Murray Hopper");
        Button delete = robot.lookup(".modal-confirm").queryButton();
        assertThat(fx(delete::isDisabled)).as("the password is needed too").isTrue();
        robot.clickOn(".profile-password-check").write("fortran").type(KeyCode.ENTER);
        settle();
        assertThat(texts(robot, ".form-dialog-error")).containsExactly(WRONG);
        assertThat(app.context().profiles().find(grace.id())).isPresent();
        robot.clickOn(".profile-password-check").write("compiler").type(KeyCode.ENTER);
        waitFor("the delete", () -> texts(robot, ".toast-message").contains("Profile deleted"));
        assertThat(app.context().profiles().find(grace.id())).isEmpty();
    }

    @Test
    void theOpenProfileIsEditedWithoutAskingForItsPasswordAgain(FxRobot robot) {
        app = RealWorkspaces.open(dir);
        String adaId = app.context().openProfileId();
        app.context().profiles().setPassword(adaId, null, "lamp".toCharArray());
        app.context().refreshProfile();
        window = TestWindows.show(robot, stage, app.context(), files);
        robot.interact(() -> window.navigator().navigate(Destination.PROFILES));
        settle();

        openMenu(robot, "Ada Lovelace");
        robot.clickOn("Edit name and colour");
        settle();
        assertThat(texts(robot, ".field-label")).containsExactly("Name", "Avatar colour");
        assertThat(robot.lookup(".profile-password-check").tryQuery()).isEmpty();
        robot.type(KeyCode.ESCAPE);
        settle();
    }

    @Test
    void aFormFinishesInTheWindowWhileTheShortcutsThatLeaveThePageWait(FxRobot robot) {
        app = RealWorkspaces.open(dir);
        window = TestWindows.show(robot, stage, app.context(), files);
        AppMenus menus = fx(() -> new AppMenus(window, false));
        MenuItem home = item(menus, "Go", "Home");
        MenuItem switchProfile = item(menus, "File", "Switch profile");
        robot.interact(() -> window.navigator().navigate(Destination.PROFILES));
        settle();
        assertThat(fx(home::isDisable)).isFalse();

        ProfilesPage profiles = current(window, ProfilesPage.class);
        FormDialog.Handle form = fx(() -> profiles.actions().edit(app.context().profile(), profiles.root(), saved -> { }));
        settle();
        assertThat(fx(home::isDisable)).as("Go stays put under an open form").isTrue();
        assertThat(fx(switchProfile::isDisable)).isTrue();

        // Even when the page changes underneath (as a shortcut used to do), the form finishes in the window.
        robot.interact(() -> window.navigator().reset(Destination.HOME));
        settle();
        assertThat(fx(form::isOpen)).isTrue();
        TextField name = robot.lookup(".profile-name-field").queryAs(TextField.class);
        robot.interact(name::clear);
        robot.clickOn(name).write("Ada King").type(KeyCode.ENTER);
        waitFor("the notice", () -> texts(robot, ".toast-message").contains("Changes saved"));
        assertThat(app.context().profile().displayName()).isEqualTo("Ada King");
        waitFor("Go to come back", () -> !home.isDisable());
        robot.interact(menus::dispose);
    }

    private static MenuItem item(AppMenus menus, String menu, String label) {
        return menus.bar().getMenus().stream().filter(m -> m.getText().equals(menu)).findFirst()
                .map(Menu::getItems).orElse(javafx.collections.FXCollections.observableArrayList()).stream()
                .filter(i -> label.equals(i.getText())).findFirst()
                .orElseThrow(() -> new AssertionError("No " + menu + " > " + label));
    }

    private static void openMenu(FxRobot robot, String profileName) {
        List<IconButton> more = robot.lookup(node -> node instanceof IconButton button
                && ("More for " + profileName).equals(button.getAccessibleText()) && TestWindows.shown(button))
                .queryAllAs(IconButton.class).stream().toList();
        assertThat(more).hasSize(1);
        robot.clickOn(more.getFirst());
        settle();
    }
}
