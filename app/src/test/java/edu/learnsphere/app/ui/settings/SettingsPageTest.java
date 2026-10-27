package edu.learnsphere.app.ui.settings;

import static edu.learnsphere.app.testing.TestWindows.current;
import static edu.learnsphere.app.testing.TestWindows.fx;
import static edu.learnsphere.app.testing.TestWindows.settle;
import static edu.learnsphere.app.testing.TestWindows.texts;
import static edu.learnsphere.app.testing.TestWindows.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import atlantafx.base.controls.ToggleSwitch;
import edu.learnsphere.app.testing.GatedRuntime;
import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.testing.RecordingFiles;
import edu.learnsphere.app.testing.TestWindows;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.SystemFiles;
import edu.learnsphere.app.ui.settings.SettingsPage.Section;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.spi.ModelChoice;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/** Settings over real services: every change is saved at once, and each section shows what it controls. */
@ExtendWith(ApplicationExtension.class)
class SettingsPageTest {

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

    private SettingsPage open(FxRobot robot) {
        window = TestWindows.show(robot, stage, app.context(), files);
        robot.interact(() -> window.navigator().navigate(Destination.SETTINGS));
        settle();
        return current(window, SettingsPage.class);
    }

    @Test
    void appearanceChangesThePreferenceForEveryProfile(FxRobot robot) {
        app = RealWorkspaces.open(dir);
        SettingsPage settings = open(robot);
        assertThat(texts(robot, ".settings-index-item"))
                .containsExactly("Appearance", "Study", "Study assistant", "Profile", "Storage", "About");

        robot.clickOn(robot.from(settings.section(Section.APPEARANCE)).lookup("Dark").queryAs(ToggleButton.class));
        waitFor("the dark theme saved", () -> app.context().preferences().get().theme() == ThemePreference.DARK);
        ToggleSwitch reduce = robot.from(settings.section(Section.APPEARANCE)).lookup(".toggle-switch")
                .queryAs(ToggleSwitch.class);
        robot.clickOn(reduce);
        waitFor("reduce motion saved", () -> app.context().preferences().get().reduceMotion());

        // A change made elsewhere (the View menu) shows here too.
        app.context().preferences().update(p -> p.withTheme(ThemePreference.SYSTEM).withReduceMotion(false));
        waitFor("the page to follow", () -> !reduce.isSelected());
        assertThat(fx(() -> robot.from(settings.section(Section.APPEARANCE)).lookup("Match computer")
                .queryAs(ToggleButton.class).isSelected())).isTrue();
    }

    @Test
    void studySettingsAreSavedForThisProfile(FxRobot robot) {
        app = RealWorkspaces.open(dir);
        SettingsPage settings = open(robot);
        Node study = settings.section(Section.STUDY);
        assertThat(texts(robot, ".retention-value")).containsExactly("90%");
        assertThat(texts(robot, ".settings-row-detail")).contains("You'll remember about 9 in 10 questions when they come back.");

        Slider retention = robot.from(study).lookup(".slider").queryAs(Slider.class);
        robot.interact(() -> retention.setValue(0.85));
        waitFor("the retention saved", () -> app.workspace().settings().current().desiredRetention() == 0.85);
        assertThat(texts(robot, ".retention-value")).containsExactly("85%");
        assertThat(texts(robot, ".settings-row-detail")).contains("You'll remember about 85 in 100 questions when they come back.");

        robot.clickOn(retention);
        robot.type(KeyCode.RIGHT);
        waitFor("one more percent", () -> app.workspace().settings().current().desiredRetention() == 0.86);

        @SuppressWarnings("unchecked")
        List<Spinner<Integer>> spinners = robot.from(study).lookup(".spinner").queryAllAs(Spinner.class).stream()
                .map(s -> (Spinner<Integer>) s).toList();
        assertThat(spinners).hasSize(2);
        Spinner<Integer> size = spinners.get(0);
        robot.doubleClickOn(size.getEditor());
        robot.interact(() -> size.getEditor().selectAll());
        robot.write("35").type(KeyCode.TAB);
        waitFor("the session length saved", () -> app.workspace().settings().get(SettingKeys.SESSION_SIZE) == 35);

        Spinner<Integer> perDay = spinners.get(1);
        robot.interact(() -> perDay.increment());
        waitFor("new questions per day saved", () -> app.workspace().settings().get(SettingKeys.NEW_ITEMS_PER_DAY) == 20);

        robot.clickOn(size.getEditor());
        robot.interact(() -> size.getEditor().selectAll());
        robot.write("lots").type(KeyCode.TAB);
        assertThat(fx(() -> size.getEditor().getText())).as("the last good value comes back").isEqualTo("35");
    }

    @Test
    void theStudyAssistantShowsItsStateChoicesAndFigures(FxRobot robot) {
        GatedRuntime assistant = new GatedRuntime(EnumSet.of(ModelChoice.CHAT_4B));
        app = RealWorkspaces.at(dir, assistant);
        String id = app.context().profiles().create(new ProfileDraft("Ada Lovelace", 210, null)).id();
        app.context().open(id, null);
        RealWorkspaces.await(() -> app.context().workspace() != null, Duration.ofSeconds(10), "the profile");
        SettingsPage settings = open(robot);
        Node section = settings.section(Section.ASSISTANT);
        robot.interact(() -> settings.scrollTo(Section.ASSISTANT));
        assertThat(texts(robot, ".status-pill-label")).contains("Getting ready");
        assertThat(robot.from(section).lookup("Restart").tryQuery().filter(TestWindows::shown)).isEmpty();

        assistant.release();
        waitFor("ready", () -> texts(robot, ".status-pill-label").contains("Ready"));
        assertThat(texts(robot, ".settings-row-detail")).contains("Your study assistant is ready");

        List<RadioButton> options = robot.from(section).lookup(".model-option").queryAllAs(RadioButton.class)
                .stream().toList();
        assertThat(options).extracting(RadioButton::getText).containsExactly("Automatic", "Quick", "Thorough");
        assertThat(options.get(0).isSelected()).isTrue();
        assertThat(options.get(2).isDisabled()).isTrue();
        assertThat(texts(robot, ".model-option-detail")).anyMatch(text -> text.endsWith("Not available on this computer."));
        assertThat(texts(robot, ".chip")).contains("Best fit here");

        robot.clickOn(options.get(1));
        waitFor("the choice saved", () -> app.workspace().settings().get(SettingKeys.MODEL_CHOICE) == ModelChoice.CHAT_4B);

        assistant.fail();
        waitFor("stopped", () -> texts(robot, ".status-pill-label").contains("Stopped"));
        int starts = assistant.starts();
        robot.clickOn(robot.from(section).lookup("Restart").queryButton());
        settle();
        assertThat(assistant.starts()).isEqualTo(starts + 1);

        ToggleSwitch figures = robot.from(section).lookup(".toggle-switch").queryAs(ToggleSwitch.class);
        assertThat(figures.isSelected()).isTrue();
        robot.clickOn(figures);
        waitFor("figures off", () -> !app.workspace().settings().get(SettingKeys.DESCRIBE_FIGURES));
    }

    @Test
    void profileStorageAndAboutAct(FxRobot robot) {
        app = RealWorkspaces.open(dir);
        String id = app.context().openProfileId();
        files.saveTo(dir.resolve("ada.zip"));
        SettingsPage settings = open(robot);

        // Profile: add a password; the line and the sidebar follow.
        Node profile = settings.section(Section.PROFILE);
        robot.interact(() -> settings.scrollTo(Section.PROFILE));
        assertThat(texts(robot, ".settings-row-detail")).contains("No password");
        robot.clickOn(robot.from(profile).lookup("Add a password").queryButton());
        settle();
        robot.write("lamp");
        robot.type(KeyCode.TAB).write("lamp").type(KeyCode.ENTER);
        settle();
        assertThat(app.context().profiles().find(id).orElseThrow().passwordProtected()).isTrue();
        waitFor("the profile line", () -> texts(robot, ".settings-row-detail").contains("Has a password"));
        assertThat(robot.from(settings.section(Section.PROFILE)).lookup("Change password").tryQuery()).isPresent();

        // Storage: the space used, the folder, a backup.
        robot.interact(() -> settings.scrollTo(Section.STORAGE));
        waitFor("the index to mark Storage", () -> settings.selectedSection() == Section.STORAGE);
        waitFor("the space used", () -> settings.spaceUsedText().matches("\\d+(\\.\\d)? (bytes|KB|MB)"));
        Node storage = settings.section(Section.STORAGE);
        robot.clickOn(robot.from(storage).lookup("Show in " + SystemFiles.fileManagerName()).queryButton());
        assertThat(files.revealed()).containsExactly(app.context().paths().profile(id).root());
        robot.clickOn(robot.from(storage).lookup("Back up…").queryButton());
        waitFor("the backup", () -> texts(robot, ".toast-message").contains("Backup saved"));
        assertThat(dir.resolve("ada.zip")).exists();

        // About: the version and the licences.
        robot.interact(() -> settings.scrollTo(Section.ABOUT));
        settle();
        assertThat(fx(() -> ((ToggleButton) robot.lookup("About").match(n -> n instanceof ToggleButton).query())
                .isSelected())).isTrue();
        assertThat(texts(robot, ".text-caption")).anyMatch(text -> text.matches("Version \\d+\\.\\d+\\.\\d+.*"));
        robot.clickOn(robot.from(settings.section(Section.ABOUT)).lookup("Licences").queryButton());
        settle();
        assertThat(texts(robot, ".licence-name")).contains("Inter 4.1", "Newsreader", "llama.cpp", "OpenJFX 27");
        assertThat(robot.lookup(".licence-text").tryQuery().filter(TestWindows::shown)).isEmpty();
        robot.clickOn(robot.lookup("Read the licence").queryButton());
        assertThat(fx(() -> robot.lookup(".licence-text").queryAll().stream().filter(TestWindows::shown)
                .map(n -> ((Label) n).getText()).findFirst().orElse(""))).contains("SIL OPEN FONT LICENSE");
        robot.type(KeyCode.ESCAPE);
        settle();
        assertThat(robot.lookup(".form-dialog").tryQuery()).isEmpty();
    }

    @Test
    void theCopyReadsPlainly() {
        assertThat(SettingsPage.retentionMeaning(0.9)).isEqualTo("You'll remember about 9 in 10 questions when they come back.");
        assertThat(SettingsPage.retentionMeaning(0.97)).isEqualTo("You'll remember about 97 in 100 questions when they come back.");
        assertThat(SettingsPage.modelLabel(ModelChoice.CHAT_9B)).isEqualTo("Thorough");
        assertThat(SettingsPage.modelDetail(new edu.learnsphere.core.service.ModelOption(ModelChoice.AUTO, true, false, true),
                ModelChoice.CHAT_4B)).isEqualTo("Picks the best fit for this computer: Quick.");
        assertThat(SettingsPage.modelDetail(new edu.learnsphere.core.service.ModelOption(ModelChoice.CHAT_9B, false, false, false),
                ModelChoice.CHAT_4B)).endsWith("Needs 12 GB of memory or more; best with 16 GB. Not available on this computer.");
        assertThat(SettingsPage.themeLabel(ThemePreference.SYSTEM)).isEqualTo("Match computer");
    }
}
