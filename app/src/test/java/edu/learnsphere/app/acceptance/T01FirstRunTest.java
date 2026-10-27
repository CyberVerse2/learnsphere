package edu.learnsphere.app.acceptance;

import static edu.learnsphere.app.testing.TestWindows.current;
import static edu.learnsphere.app.testing.TestWindows.fx;
import static edu.learnsphere.app.testing.TestWindows.settle;
import static edu.learnsphere.app.testing.TestWindows.texts;
import static edu.learnsphere.app.testing.TestWindows.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.LearnSphereApp;
import edu.learnsphere.app.testing.GatedRuntime;
import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.testing.RecordingFiles;
import edu.learnsphere.app.testing.TestWindows;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.Route;
import edu.learnsphere.app.ui.onboarding.OnboardingPage;
import edu.learnsphere.app.ui.profiles.AvatarPicker;
import edu.learnsphere.app.ui.profiles.ProfilesPage;
import edu.learnsphere.app.ui.profiles.UnlockPage;
import edu.learnsphere.core.profile.AppPreferences;
import edu.learnsphere.core.service.ProfileSummary;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * T01 First run: a computer with no profiles opens on the welcome, makes a profile with a password, waits for
 * the study assistant to get ready and lands on an empty Home; started again, it asks for that password,
 * turns a wrong one away with one sentence and opens Home with the right one.
 */
@ExtendWith(ApplicationExtension.class)
class T01FirstRunTest {

    private static final String WRONG_PASSWORD = "That password isn't right. Try again.";

    @TempDir
    Path dir;

    private Stage stage;
    private RealWorkspaces app;
    private MainWindow window;

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
    void firstRunMakesAProfileThenThePasswordOpensIt(FxRobot robot) throws Exception {
        GatedRuntime assistant = new GatedRuntime();
        app = RealWorkspaces.at(dir, assistant);
        window = TestWindows.show(robot, stage, app.context(), new RecordingFiles());

        // Welcome, without the sidebar.
        assertThat(window.navigator().current()).isEqualTo(Destination.ONBOARDING);
        assertThat(window.isSidebarShown()).isFalse();
        assertThat(window.topBar().isVisible()).isFalse();
        assertThat(texts(robot, ".text-display")).containsExactly("Welcome to LearnSphere");
        OnboardingPage onboarding = current(window, OnboardingPage.class);
        assertThat(onboarding.step()).isEqualTo(OnboardingPage.Step.WELCOME);

        robot.clickOn("Get started");
        assertThat(onboarding.step()).isEqualTo(OnboardingPage.Step.PROFILE);
        assertThat(texts(robot, ".text-title")).containsExactly("Create your profile");

        // Name, password typed twice, a colour.
        robot.clickOn(onboarding.nameField()).write("Ada Lovelace");
        robot.clickOn(onboarding.passwordField()).write("correct horse");
        robot.clickOn(onboarding.confirmField()).write("correct hose");
        robot.clickOn("Create profile");
        settle();
        assertThat(onboarding.profileErrorText()).isEqualTo("The passwords don't match. Type them again.");
        assertThat(app.context().profiles().list()).isEmpty();

        robot.clickOn(onboarding.confirmField()).write("correct horse");
        int violet = AvatarPicker.COLOURS.get(5).hue();
        robot.clickOn(onboarding.colours().swatch(violet));
        robot.clickOn("Create profile");
        settle();

        // The study assistant gets ready while the person watches.
        assertThat(onboarding.step()).isEqualTo(OnboardingPage.Step.ASSISTANT);
        assertThat(onboarding.statusText()).isEqualTo("Getting your study assistant ready");
        assertThat(onboarding.orbit().isTravelling()).isTrue();
        assertThat(texts(robot, ".onboarding-privacy")).singleElement().asString()
                .startsWith("Everything stays on this computer.");
        assistant.release();
        waitFor("the assistant to be ready", () -> "Your study assistant is ready".equals(onboarding.statusText()));
        assertThat(onboarding.orbit().isTravelling()).isFalse();

        robot.clickOn("Start learning");
        settle();

        // Home, empty, with the new profile in the sidebar and the title.
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
        assertThat(window.isSidebarShown()).isTrue();
        assertThat(texts(robot, ".profile-chip-name")).containsExactly("Ada Lovelace");
        assertThat(robot.lookup("Your library is empty").tryQuery()).isPresent();
        ProfileSummary ada = app.context().profile();
        assertThat(ada.displayName()).isEqualTo("Ada Lovelace");
        assertThat(ada.avatarHue()).isEqualTo(violet);
        assertThat(ada.passwordProtected()).isTrue();
        assertThat(LearnSphereApp.title(ada)).isEqualTo("Ada Lovelace — LearnSphere");

        // Stored: the password only as a hash; the last profile and finished onboarding remembered.
        String registry = Files.readString(app.context().paths().profilesFile(), StandardCharsets.UTF_8);
        assertThat(registry).contains("Ada Lovelace").contains("passwordHash").doesNotContain("correct horse");
        AppPreferences preferences = app.context().preferences().get();
        assertThat(preferences.lastProfileId()).isEqualTo(ada.id());
        assertThat(preferences.onboardingCompleted()).isTrue();

        // Quit; the profile and the study assistant stop.
        TestWindows.dispose(robot, window);
        app.close();
        assertThat(app.context().workspace()).isNull();
        assertThat(assistant.stops()).isPositive();

        // Start again: the password screen for Ada, with no sidebar.
        GatedRuntime again = new GatedRuntime();
        app = RealWorkspaces.at(dir, again);
        window = TestWindows.show(robot, stage, app.context(), new RecordingFiles());
        assertThat(window.navigator().current()).isEqualTo(new Destination.Unlock(ada.id()));
        assertThat(window.isSidebarShown()).isFalse();
        UnlockPage unlock = current(window, UnlockPage.class);
        assertThat(texts(robot, ".unlock-name")).containsExactly("Ada Lovelace");
        assertThat(again.starts()).as("nothing starts before the password").isZero();

        robot.clickOn(unlock.passwordField()).write("wrong").type(KeyCode.ENTER);
        settle();
        assertThat(unlock.errorText()).isEqualTo(WRONG_PASSWORD);
        assertThat(texts(robot, ".unlock-error")).containsExactly(WRONG_PASSWORD);
        assertThat(app.context().workspace()).isNull();
        assertThat(unlock.passwordField().getSelection().getLength()).as("ready for another try").isEqualTo(5);

        robot.write("correct horse");
        assertThat(unlock.errorText()).as("typing clears the sentence").isEmpty();
        robot.clickOn("Open profile");
        settle();
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
        assertThat(window.isSidebarShown()).isTrue();
        assertThat(texts(robot, ".profile-chip-name")).containsExactly("Ada Lovelace");
        assertThat(app.context().openProfileId()).isEqualTo(ada.id());
        assertThat(again.starts()).isEqualTo(1);
    }

    @Test
    void theAssistantStepSaysWhatHappensWhenThisComputerCantRunIt(FxRobot robot) {
        GatedRuntime assistant = new GatedRuntime();
        app = RealWorkspaces.at(dir, assistant);
        window = TestWindows.show(robot, stage, app.context(), new RecordingFiles());
        OnboardingPage onboarding = current(window, OnboardingPage.class);
        robot.clickOn("Get started");
        robot.clickOn(onboarding.nameField()).write("Grace");
        robot.type(KeyCode.ENTER);
        settle();
        assertThat(onboarding.step()).isEqualTo(OnboardingPage.Step.ASSISTANT);
        assertThat(app.context().profile().passwordProtected()).isFalse();

        assistant.unavailable();
        waitFor("the unavailable state", () -> onboarding.orbit().state()
                == edu.learnsphere.core.service.AssistantState.UNAVAILABLE);
        assertThat(onboarding.statusText()).isEqualTo("Your study assistant can't run on this computer.");
        assertThat(onboarding.statusDetailText()).startsWith("You can still study, practise and follow your progress.");
        assertThat(texts(robot, ".onboarding-restart")).isEmpty();

        assistant.fail();
        waitFor("the stopped state", () -> !texts(robot, ".onboarding-restart").isEmpty());
        assertThat(onboarding.statusText()).isEqualTo("Your study assistant stopped. Restart it to keep going.");
        robot.clickOn(".onboarding-restart");
        settle();
        assertThat(assistant.starts()).isEqualTo(2);
        assertThat(onboarding.statusText()).isEqualTo("Getting your study assistant ready");

        robot.clickOn("Start learning");
        settle();
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
    }

    @Test
    void theWelcomeAndProfileStepsGoBackAndForth(FxRobot robot) {
        app = RealWorkspaces.at(dir, new GatedRuntime());
        window = TestWindows.show(robot, stage, app.context(), new RecordingFiles());
        OnboardingPage onboarding = current(window, OnboardingPage.class);

        robot.type(KeyCode.ENTER);
        assertThat(onboarding.step()).as("Get started is the default button").isEqualTo(OnboardingPage.Step.PROFILE);
        assertThat(robot.lookup("Create profile").queryButton().isDisabled()).as("a name is needed").isTrue();
        assertThat(texts(robot, ".field-label")).doesNotContain("Type the password again");
        robot.type(KeyCode.ESCAPE);
        assertThat(onboarding.step()).isEqualTo(OnboardingPage.Step.WELCOME);
        robot.clickOn("Get started");
        robot.clickOn("Back");
        assertThat(onboarding.step()).isEqualTo(OnboardingPage.Step.WELCOME);
        assertThat(app.context().profiles().list()).isEmpty();
    }

    @Test
    void clearingThePasswordMakesAProfileWithoutOne(FxRobot robot) {
        app = RealWorkspaces.at(dir, new GatedRuntime());
        window = TestWindows.show(robot, stage, app.context(), new RecordingFiles());
        OnboardingPage onboarding = current(window, OnboardingPage.class);
        robot.clickOn("Get started");
        robot.clickOn(onboarding.nameField()).write("Ada Lovelace");
        robot.clickOn(onboarding.passwordField()).write("lamp");
        robot.clickOn(onboarding.confirmField()).write("lamp");

        // Changing their mind: no password after all. The hidden confirmation doesn't keep the old one.
        robot.interact(() -> onboarding.passwordField().clear());
        assertThat(fx(() -> onboarding.confirmField().getText())).isEmpty();
        assertThat(texts(robot, ".field-label")).doesNotContain("Type the password again");
        robot.clickOn("Create profile");
        settle();

        assertThat(onboarding.profileErrorText()).isEmpty();
        assertThat(onboarding.step()).isEqualTo(OnboardingPage.Step.ASSISTANT);
        assertThat(app.context().profiles().list()).singleElement()
                .satisfies(profile -> assertThat(profile.passwordProtected()).isFalse());
    }

    @Test
    void theUnlockScreenSwitchesToAnotherProfile(FxRobot robot) {
        app = RealWorkspaces.at(dir, new GatedRuntime());
        ProfileSummary ada = app.context().profiles().create(
                new edu.learnsphere.core.service.ProfileDraft("Ada Lovelace", 210, "secret".toCharArray()));
        app.context().profiles().create(new edu.learnsphere.core.service.ProfileDraft("Grace Hopper", 30, null));
        app.context().preferences().update(p -> p.withLastProfileId(ada.id()));
        window = TestWindows.show(robot, stage, app.context(), new RecordingFiles());
        assertThat(window.navigator().current()).isEqualTo(new Destination.Unlock(ada.id()));

        robot.clickOn("Switch profile");
        settle();
        assertThat(window.navigator().currentRoute()).isEqualTo(Route.PROFILES);
        assertThat(current(window, ProfilesPage.class).profileNames()).containsExactlyInAnyOrder("Ada Lovelace", "Grace Hopper");

        robot.clickOn("Grace Hopper");
        settle();
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
        assertThat(texts(robot, ".profile-chip-name")).containsExactly("Grace Hopper");
    }
}
