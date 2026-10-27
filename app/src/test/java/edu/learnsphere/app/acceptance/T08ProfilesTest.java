package edu.learnsphere.app.acceptance;

import static edu.learnsphere.app.testing.TestWindows.current;
import static edu.learnsphere.app.testing.TestWindows.fx;
import static edu.learnsphere.app.testing.TestWindows.settle;
import static edu.learnsphere.app.testing.TestWindows.texts;
import static edu.learnsphere.app.testing.TestWindows.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.ai.fake.FakeEmbedder;
import edu.learnsphere.app.LearnSphereApp;
import edu.learnsphere.app.testing.GatedEmbedder;
import edu.learnsphere.app.testing.GatedRuntime;
import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.testing.RecordingFiles;
import edu.learnsphere.app.testing.TestWindows;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.profiles.ProfilesPage;
import edu.learnsphere.app.ui.profiles.UnlockPage;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.ChatThread;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.Answer;
import edu.learnsphere.core.service.AnswerListener;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.ItemStats;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.Workspace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javafx.scene.control.Button;
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
 * T08 Profiles survive restarts, backups and restores: a PDF import cut short by quitting carries on when the
 * app starts again and ends ready; a backup restored as another profile has the same courses, PDFs and
 * progress, and its search is rebuilt; switching between profiles (with and without a password), renaming the
 * open profile, and the delete rules: the open profile has to be closed first, and deleting asks for the
 * profile's name.
 */
@ExtendWith(ApplicationExtension.class)
class T08ProfilesTest {

    private static final String PROFILE_OPEN = "This profile is open. Close it and try again.";

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
    void anImportCutShortByQuittingCarriesOnWhenTheAppStartsAgain(FxRobot robot) throws Exception {
        // Steps 1 and 2: start importing cells.pdf, quit while it is being read, start again.
        GatedEmbedder reading = new GatedEmbedder(new FakeEmbedder());
        RealWorkspaces first = RealWorkspaces.at(dir, RealWorkspaces.withEmbedder(FakeAi.create(), reading));
        DocumentSummary importing;
        Course course;
        try {
            String adaId = first.context().profiles().create(new ProfileDraft("Ada Lovelace", 210, null)).id();
            assertThat(first.context().openLastProfile()).isPresent();
            RealWorkspaces.await(() -> first.context().workspace() != null, Duration.ofSeconds(10), "Ada to open");
            window = TestWindows.show(robot, stage, first.context(), files);
            Workspace workspace = first.workspace();
            course = workspace.courses().create(CourseDraft.titled("Cell Biology"));
            Path cells = first.textPdf("cells.pdf", "Cells", 6);
            importing = workspace.documents().importPdf(course.id(), null, cells);

            reading.awaitHeld(Duration.ofSeconds(30));
            DocumentSummary halfWay = workspace.documents().get(importing.id());
            assertThat(halfWay.status()).as("being made searchable").isEqualTo(DocumentStatus.INDEXING);
            assertThat(halfWay.pageCount()).isEqualTo(6);

            // Quit as the app does: the screens go, then the profile closes (its jobs stop) and the assistant.
            TestWindows.dispose(robot, window);
            window = null;
            long quitting = System.nanoTime();
            first.close();
            assertThat(Duration.ofNanos(System.nanoTime() - quitting)).as("quitting doesn't wait for the import")
                    .isLessThan(Duration.ofSeconds(10));
            assertThat(reading.cancelledCalls()).as("the held read stopped").isPositive();
            assertThat(first.context().openProfileId()).isNull();
            assertThat(adaId).isNotNull();
        } finally {
            reading.release();
            first.close();
        }

        // Start again over the same app home: Ada opens, and the import carries on to ready by itself.
        app = RealWorkspaces.at(dir, FakeAi.create().capabilities());
        assertThat(app.context().openLastProfile()).isPresent();
        RealWorkspaces.await(() -> app.context().workspace() != null, Duration.ofSeconds(10), "Ada to open again");
        window = TestWindows.show(robot, stage, app.context(), files);
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);

        DocumentSummary done = app.awaitSettled(importing.id());
        assertThat(done.status()).isEqualTo(DocumentStatus.READY);
        assertThat(done.pageCount()).isEqualTo(6);
        assertThat(app.workspace().documents().list(course.id())).extracting(DocumentSummary::id)
                .containsExactly(importing.id());
        Answer answer = ask(app.workspace(), course.id(), "What does Cells part 3 cover?");
        assertThat(answer.citations()).as("its passages can be searched").isNotEmpty()
                .allMatch(citation -> citation.documentId().equals(importing.id()));
    }

    @Test
    void backsUpAndRestoresAProfileFromTheProfileChoice(FxRobot robot) throws Exception {
        app = RealWorkspaces.open(dir);
        String adaId = app.context().openProfileId();
        Workspace ada = app.workspace();
        Course course = ada.courses().create(CourseDraft.titled("Cell Biology"));
        DocumentSummary cells = app.awaitSettled(
                ada.documents().importPdf(course.id(), null, app.textPdf("cells.pdf", "Cells", 4)).id());
        assertThat(cells.status()).isEqualTo(DocumentStatus.READY);
        // Progress: one question answered in a study session.
        String itemId = ada.items().create(course.id(), null,
                ItemContent.flashcard("What holds a cell's genetic material?", "The nucleus")).id();
        ada.items().approve(itemId);
        StudySession session = ada.practice().start(SessionRequest.flashcards(course.id(), null));
        assertThat(session.items()).extracting(item -> item.itemId()).containsExactly(itemId);
        ada.practice().submit(session.id(), Response.flashcard(itemId, Rating.GOOD, 4_000));
        ada.practice().finish(session.id());
        ItemStats reviewed = ada.items().get(itemId).stats();
        assertThat(reviewed.attempts()).isEqualTo(1);
        assertThat(reviewed.dueAt()).isNotNull();

        Path zip = dir.resolve("ada.zip");
        files.saveTo(zip).openFrom(zip);
        window = TestWindows.show(robot, stage, app.context(), files);

        // Step 3: back up from the profile choice.
        robot.clickOn(".profile-chip");
        settle();
        ProfilesPage profiles = current(window, ProfilesPage.class);
        assertThat(profiles.profileNames()).containsExactly("Ada Lovelace");
        assertThat(texts(robot, ".profile-tile-meta")).containsExactly("Open now");

        openMenu(robot, "Ada Lovelace");
        robot.clickOn("Back up…");
        waitFor("the backup", () -> toasts(robot).contains("Backup saved"));
        assertThat(zip).exists();
        assertThat(files.suggestedNames()).singleElement().asString().startsWith("Ada Lovelace backup ").endsWith(".zip");

        // Step 4: restore it, as a second profile with the same name.
        robot.clickOn("Restore from a backup");
        waitFor("the restore", () -> toasts(robot).contains("Profile restored"));
        settle();
        assertThat(profiles.profileNames()).containsExactlyInAnyOrder("Ada Lovelace", "Ada Lovelace");
        ProfileSummary restored = app.context().profiles().list().stream()
                .filter(p -> !p.id().equals(adaId)).findFirst().orElseThrow();
        assertThat(restored.displayName()).isEqualTo("Ada Lovelace");

        robot.clickOn(profiles.tile(restored.id()));
        settle();
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
        assertThat(app.context().openProfileId()).isEqualTo(restored.id());
        assertThat(texts(robot, ".profile-chip-name")).containsExactly("Ada Lovelace");
        Workspace copy = app.workspace();

        // The same courses, PDFs and progress.
        assertThat(copy.courses().list(CourseFilter.all()).stream().map(CourseSummary::title))
                .containsExactly("Cell Biology");
        DocumentSummary restoredPdf = copy.documents().list(course.id()).stream().findFirst().orElseThrow();
        assertThat(restoredPdf.title()).isEqualTo(cells.title());
        assertThat(restoredPdf.status()).isEqualTo(DocumentStatus.READY);
        assertThat(restoredPdf.pageCount()).isEqualTo(4);
        try (Stream<Path> stored = Files.list(app.context().paths().profile(restored.id()).filesDir())) {
            assertThat(stored.filter(Files::isRegularFile).map(T08ProfilesTest::size).toList())
                    .as("the PDF itself, in the restored profile's files").contains(cells.byteSize());
        }
        ItemStats restoredProgress = copy.items().get(itemId).stats();
        assertThat(restoredProgress.attempts()).isEqualTo(1);
        assertThat(restoredProgress.phase()).isEqualTo(reviewed.phase());
        assertThat(restoredProgress.dueAt()).isEqualTo(reviewed.dueAt());
        assertThat(restoredProgress.stability()).isEqualTo(reviewed.stability());

        // The search index is rebuilt in the background, then the PDF answers questions again.
        RealWorkspaces.await(() -> copy.jobs().active().isEmpty(), Duration.ofSeconds(30), "the index to rebuild");
        assertThat(app.context().paths().profile(restored.id()).indexDir()).isNotEmptyDirectory();
        Answer answer = ask(copy, course.id(), "What does Cells part 2 cover?");
        assertThat(answer.citations()).isNotEmpty().extracting(Citation::documentId).containsOnly(cells.id());
    }

    @Test
    void switchingProfilesClosesTheOneOpenAndAPasswordIsCheckedFirst(FxRobot robot) {
        GatedRuntime assistant = new GatedRuntime();
        app = RealWorkspaces.at(dir, assistant);
        ProfileSummary ada = app.context().profiles().create(new ProfileDraft("Ada Lovelace", 210, null));
        ProfileSummary grace = app.context().profiles().create(
                new ProfileDraft("Grace Hopper", 30, "compiler".toCharArray()));
        Workspace adaWorkspace = app.context().open(ada.id(), null);
        RealWorkspaces.await(() -> app.context().workspace() == adaWorkspace, Duration.ofSeconds(10), "Ada to open");
        assistant.release();
        window = TestWindows.show(robot, stage, app.context(), files);
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
        assertThat(LearnSphereApp.title(app.context().profile())).isEqualTo("Ada Lovelace — LearnSphere");

        robot.clickOn(".profile-chip");
        settle();
        ProfilesPage profiles = current(window, ProfilesPage.class);
        robot.clickOn(profiles.tile(grace.id()));
        settle();
        UnlockPage unlock = current(window, UnlockPage.class);

        // A wrong password leaves Ada open and her study assistant running.
        robot.clickOn(unlock.passwordField()).write("compile").type(KeyCode.ENTER);
        settle();
        assertThat(unlock.errorText()).isEqualTo("That password isn't right. Try again.");
        assertThat(app.context().openProfileId()).isEqualTo(ada.id());
        assertThat(assistant.stops()).isZero();

        robot.write("compiler").type(KeyCode.ENTER);
        settle();
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
        assertThat(app.context().openProfileId()).isEqualTo(grace.id());
        assertThat(app.context().workspace()).isNotSameAs(adaWorkspace);
        assertThat(assistant.stops()).as("Ada's study assistant stopped").isEqualTo(1);
        assertThat(assistant.starts()).as("and started again for Grace").isEqualTo(2);
        assertThat(texts(robot, ".profile-chip-name")).containsExactly("Grace Hopper");
        assertThat(texts(robot, ".profile-chip-detail")).containsExactly("Switch profile");
        assertThat(LearnSphereApp.title(app.context().profile())).isEqualTo("Grace Hopper — LearnSphere");
        assertThat(app.context().preferences().get().lastProfileId()).isEqualTo(grace.id());
    }

    @Test
    void renamingTheOpenProfileUpdatesTheSidebarAndTitle(FxRobot robot) {
        app = RealWorkspaces.open(dir);
        window = TestWindows.show(robot, stage, app.context(), files);
        robot.clickOn(".profile-chip");
        settle();

        openMenu(robot, "Ada Lovelace");
        robot.clickOn("Edit name and colour");
        TextField name = robot.lookup(".profile-name-field").queryAs(TextField.class);
        robot.interact(name::clear);
        robot.clickOn(name).write("Ada King").type(KeyCode.ENTER);
        settle();

        assertThat(toasts(robot)).contains("Changes saved");
        assertThat(current(window, ProfilesPage.class).profileNames()).containsExactly("Ada King");
        assertThat(app.context().profile().displayName()).isEqualTo("Ada King");
        assertThat(LearnSphereApp.title(app.context().profile())).isEqualTo("Ada King — LearnSphere");
        robot.type(KeyCode.ESCAPE);
        settle();
        assertThat(window.navigator().current()).isEqualTo(Destination.HOME);
        assertThat(texts(robot, ".profile-chip-name")).containsExactly("Ada King");
    }

    @Test
    void theOpenProfileIsClosedBeforeItCanBeDeleted(FxRobot robot) {
        app = RealWorkspaces.open(dir);
        String adaId = app.context().openProfileId();
        ProfileSummary grace = app.context().profiles().create(new ProfileDraft("Grace Hopper", 30, null));
        window = TestWindows.show(robot, stage, app.context(), files);
        robot.interact(() -> window.navigator().navigate(Destination.PROFILES));
        settle();
        ProfilesPage profiles = current(window, ProfilesPage.class);

        // Ada is open: the rule, and nothing deleted.
        openMenu(robot, "Ada Lovelace");
        robot.clickOn("Delete profile…");
        settle();
        assertThat(texts(robot, ".modal-message")).containsExactly(PROFILE_OPEN);
        robot.type(KeyCode.ESCAPE);
        settle();
        assertThat(robot.lookup(".form-dialog").tryQuery()).isEmpty();
        assertThat(app.context().profiles().find(adaId)).isPresent();

        // Switch to Grace, then delete Ada once her name is typed.
        robot.clickOn(profiles.tile(grace.id()));
        settle();
        assertThat(app.context().openProfileId()).isEqualTo(grace.id());
        robot.clickOn(".profile-chip");
        settle();
        profiles = current(window, ProfilesPage.class);
        openMenu(robot, "Ada Lovelace");
        robot.clickOn("Delete profile…");
        settle();
        Button delete = robot.lookup(".modal-confirm").queryButton();
        assertThat(delete.getText()).isEqualTo("Delete profile");
        assertThat(fx(delete::isDisabled)).isTrue();
        robot.clickOn(".delete-confirm-field").write("Ada");
        assertThat(fx(delete::isDisabled)).as("the whole name is needed").isTrue();
        robot.write(" lovelace");
        assertThat(fx(delete::isDisabled)).as("case doesn't matter").isFalse();
        robot.clickOn(delete);
        waitFor("the delete", () -> toasts(robot).contains("Profile deleted"));
        settle();
        assertThat(profiles.profileNames()).containsExactly("Grace Hopper");
        assertThat(app.context().profiles().find(adaId)).isEmpty();
        assertThat(app.context().paths().profile(adaId).exists()).as("her folder is gone").isFalse();

        // Grace is open: closing her from the rule leaves no profile open; then she can go too.
        openMenu(robot, "Grace Hopper");
        robot.clickOn("Delete profile…");
        settle();
        assertThat(texts(robot, ".modal-message")).containsExactly(PROFILE_OPEN);
        robot.clickOn("Close profile");
        settle();
        assertThat(app.context().workspace()).isNull();
        assertThat(window.navigator().current()).isEqualTo(Destination.PROFILES);
        assertThat(texts(robot, ".profile-tile-meta")).doesNotContain("Open now");
        openMenu(robot, "Grace Hopper");
        robot.clickOn("Delete profile…");
        settle();
        robot.clickOn(".delete-confirm-field").write("Grace Hopper").type(KeyCode.ENTER);
        settle();
        assertThat(app.context().profiles().list()).isEmpty();
    }

    private static void openMenu(FxRobot robot, String profileName) {
        IconButton more = robot.lookup(node -> node instanceof IconButton button
                && ("More for " + profileName).equals(button.getAccessibleText()) && TestWindows.shown(button))
                .queryAs(IconButton.class);
        robot.clickOn(more);
        settle();
    }

    private static List<String> toasts(FxRobot robot) {
        return texts(robot, ".toast-message");
    }

    /** Asks {@code question} about a course in a new conversation and waits for the whole answer. */
    private static Answer ask(Workspace workspace, String courseId, String question) throws Exception {
        ChatThread thread = workspace.ask().createThread(courseId, null);
        CompletableFuture<Answer> answered = new CompletableFuture<>();
        workspace.ask().ask(thread.id(), question, new AnswerListener() {
            @Override
            public void onToken(String token) {
            }

            @Override
            public void onAnswer(Answer answer) {
                answered.complete(answer);
            }

            @Override
            public void onInsufficientEvidence(Answer answer) {
                answered.complete(answer);
            }

            @Override
            public void onError(UserMessage message) {
                answered.completeExceptionally(new AssertionError(message.text()));
            }
        });
        return answered.get(30, TimeUnit.SECONDS);
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
