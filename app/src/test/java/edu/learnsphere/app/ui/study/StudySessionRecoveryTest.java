package edu.learnsphere.app.ui.study;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.learning.StudyMessages;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.AskService;
import edu.learnsphere.core.service.AssistantService;
import edu.learnsphere.core.service.CourseService;
import edu.learnsphere.core.service.DocumentService;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.GenerationService;
import edu.learnsphere.core.service.Hint;
import edu.learnsphere.core.service.ItemService;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.PracticeService;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.ProgressService;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.SessionSummary;
import edu.learnsphere.core.service.SettingsService;
import edu.learnsphere.core.service.SharingService;
import edu.learnsphere.core.service.StudyNext;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.service.WorkspaceFactory;
import edu.learnsphere.core.service.impl.WorkspaceImpl;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The study session when its services stumble: a session that can't start, a written answer the study
 * assistant can't check, and a check that takes a while. The real services sit behind a practice service that
 * fails or waits on demand.
 */
@ExtendWith(ApplicationExtension.class)
class StudySessionRecoveryTest {

    private static final UserMessage CANT_START = UserMessage.of("Your courses couldn't be opened. Try again.");

    @TempDir
    Path dir;

    private final Trouble trouble = new Trouble();
    private Stage stage;
    private AppContext context;
    private MainWindow window;

    @Start
    void start(Stage stage) {
        this.stage = stage;
    }

    @BeforeEach
    void setUp(FxRobot robot) {
        FakeAi ai = FakeAi.create();
        AppPaths paths = AppPaths.at(dir.resolve("home"));
        WorkspaceFactory real = WorkspaceImpl.factory(AppContext.capabilities(paths, ai.capabilities()));
        context = new AppContext(paths, Clock.systemDefaultZone(), false,
                profile -> new TroubledWorkspace(real.open(profile), trouble));
        ProfileSummary profile = context.profiles().create(new ProfileDraft("Ada Lovelace", 210, null));
        Workspace opened = context.open(profile.id(), null);
        RealWorkspaces.await(() -> context.workspace() == opened, Duration.ofSeconds(30), "the profile to open");
        StudySeed.course(opened, dir);
        robot.interact(() -> {
            Typography.load();
            window = new MainWindow(context);
            Scene scene = new Scene(window, 1360, 860);
            Theme.install(scene, ThemePreference.LIGHT);
            stage.setScene(scene);
            stage.show();
        });
        settle();
    }

    @AfterEach
    void tearDown(FxRobot robot) {
        trouble.release();
        if (window != null) {
            robot.interact(window::dispose);
        }
        if (context != null) {
            context.close();
        }
    }

    @Test
    void aSessionThatCannotStartSaysWhyAndTriesAgain(FxRobot robot) {
        trouble.failStart = true;
        robot.interact(() -> window.navigator().navigate(new Destination.Study(SessionRequest.studyNext())));
        settle();

        assertThat(page().state()).isEqualTo(StudyPage.State.ERROR);
        assertThat(texts(page().root(), ".empty-state-title")).containsExactly("This session couldn't start");
        assertThat(texts(page().root(), ".empty-state-message")).containsExactly(CANT_START.text());

        trouble.failStart = false;
        robot.type(KeyCode.ENTER);
        settle();

        assertThat(page().state()).isEqualTo(StudyPage.State.QUESTION);
        assertThat(page().counterText()).isEqualTo("1 of 5");
    }

    @Test
    void anAnswerTheAssistantCannotCheckCanBeCheckedAgain(FxRobot robot) {
        startAt(robot, ItemType.SHORT_ANSWER);
        trouble.failGrading = true;
        TextArea area = ((ShortAnswerQuestion) page().question()).area();
        robot.interact(() -> {
            area.setText("Ribosomes join amino acids into proteins.");
            area.requestFocus();
        });

        robot.type(KeyCode.ENTER);
        settle();

        assertThat(page().state()).isEqualTo(StudyPage.State.QUESTION);
        assertThat(texts(page().root(), ".check-error")).containsExactly(StudyMessages.GRADING_UNAVAILABLE.text());
        Button check = page().checkButton();
        assertThat(check.getParent()).as("Check answer is back").isNotNull();
        assertThat(check.isDisabled()).isFalse();
        assertThat(window.getScene().getFocusOwner()).isSameAs(check);
        assertThat(area.getText()).as("the answer is kept").isEqualTo("Ribosomes join amino acids into proteins.");

        trouble.failGrading = false;
        robot.type(KeyCode.ENTER);
        settle();

        assertThat(page().state()).isEqualTo(StudyPage.State.FEEDBACK);
        assertThat(texts(page().root(), ".check-error")).isEmpty();
    }

    @Test
    void enterWhileTheAssistantReadsAClickedAnswerStaysInTheSession(FxRobot robot) {
        startAt(robot, ItemType.SHORT_ANSWER);
        TextArea area = ((ShortAnswerQuestion) page().question()).area();
        robot.interact(() -> area.setText("Ribosomes join amino acids into proteins."));
        trouble.hold();

        robot.clickOn(page().checkButton());
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page().state()).isEqualTo(StudyPage.State.CHECKING);
        assertThat(window.getScene().getFocusOwner()).as("the answer, not Leave session").isSameAs(area);
        robot.type(KeyCode.ENTER);
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(window.navigator().current()).isInstanceOf(Destination.Study.class);
        assertThat(window.getScene().getRoot().lookup(".modal")).isNull();
        assertThat(page().state()).isEqualTo(StudyPage.State.CHECKING);

        trouble.release();
        settle();

        assertThat(page().state()).isEqualTo(StudyPage.State.FEEDBACK);
        assertThat(window.getScene().getFocusOwner()).isSameAs(page().feedback().continueButton());
    }

    @Test
    void enterWhileAClickedRatingIsSavedStaysInTheSession(FxRobot robot) {
        robot.interact(() -> window.navigator().navigate(new Destination.Study(SessionRequest.studyNext())));
        settle();
        advanceTo(robot, ItemType.FLASHCARD);
        robot.type(KeyCode.SPACE);
        settle();
        trouble.hold();

        robot.clickOn(page().root().lookup(".rating-good"));
        WaitForAsyncUtils.waitForFxEvents();
        robot.type(KeyCode.ENTER);
        WaitForAsyncUtils.waitForFxEvents();

        assertThat(page().state()).isEqualTo(StudyPage.State.CHECKING);
        assertThat(window.getScene().getRoot().lookup(".modal")).isNull();
        trouble.release();
        settle();
        assertThat(page().feedback().feedback().rating()).isEqualTo(Rating.GOOD);
    }

    // ---- helpers ------------------------------------------------------------------------------------------

    private void startAt(FxRobot robot, ItemType type) {
        robot.interact(() -> window.navigator().navigate(new Destination.Study(SessionRequest.studyNext())));
        settle();
        advanceTo(robot, type);
    }

    /** Moves on (rating flashcards Good, answering anything else wrongly) until a card of {@code type} shows. */
    private void advanceTo(FxRobot robot, ItemType type) {
        for (int guard = 0; guard < 12; guard++) {
            SessionItem item = page().currentItem();
            if (page().state() == StudyPage.State.QUESTION && item.type() == type) {
                return;
            }
            switch (item.type()) {
                case MCQ -> robot.type(KeyCode.DIGIT1);
                case TRUE_FALSE -> robot.type(KeyCode.T);
                case CLOZE, SHORT_ANSWER -> robot.interact(() -> {
                    Node input = page().root().lookup(".study-input");
                    ((javafx.scene.control.TextInputControl) input).setText("something");
                    input.requestFocus();
                });
                case FLASHCARD -> {
                    robot.type(KeyCode.SPACE);
                    settle();
                    robot.type(KeyCode.DIGIT3);
                    settle();
                    robot.type(KeyCode.ENTER);
                    settle();
                    continue;
                }
            }
            robot.type(KeyCode.ENTER);
            settle();
            robot.type(KeyCode.ENTER);
            settle();
        }
        throw new AssertionError("No " + type + " card in the session");
    }

    private StudyPage page() {
        return (StudyPage) window.navigator().currentView();
    }

    private static List<String> texts(Node root, String query) {
        return root.lookupAll(query).stream().filter(Node::isVisible).filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText()).toList();
    }

    private static void settle() {
        for (int round = 0; round < 3; round++) {
            UiExecutors.awaitIdle(Duration.ofSeconds(20));
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    /** What goes wrong, switched on and off by the tests. */
    private static final class Trouble {
        volatile boolean failStart;
        volatile boolean failGrading;
        private volatile CountDownLatch gate;

        /** Makes the next answers wait until {@link #release}. */
        void hold() {
            gate = new CountDownLatch(1);
        }

        void release() {
            CountDownLatch open = gate;
            gate = null;
            if (open != null) {
                open.countDown();
            }
        }

        void waitIfHeld() {
            CountDownLatch held = gate;
            if (held != null) {
                try {
                    held.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** The real practice service, failing or waiting when {@link Trouble} says so. */
    private record TroubledPractice(PracticeService real, Trouble trouble) implements PracticeService {

        @Override
        public StudyNext studyNext(String courseId) {
            return real.studyNext(courseId);
        }

        @Override
        public StudySession start(SessionRequest request) {
            if (trouble.failStart) {
                throw new UnavailableException(CANT_START, "test: start fails");
            }
            return real.start(request);
        }

        @Override
        public Feedback submit(String sessionId, Response response) {
            trouble.waitIfHeld();
            if (trouble.failGrading) {
                throw new UnavailableException(StudyMessages.GRADING_UNAVAILABLE, "test: grading fails");
            }
            return real.submit(sessionId, response);
        }

        @Override
        public Hint hint(String sessionId, String itemId) {
            return real.hint(sessionId, itemId);
        }

        @Override
        public List<Hint> hints(String sessionId, String itemId) {
            return real.hints(sessionId, itemId);
        }

        @Override
        public SessionSummary finish(String sessionId) {
            return real.finish(sessionId);
        }

        @Override
        public Map<Rating, Long> nextReviews(String itemId) {
            return real.nextReviews(itemId);
        }

        @Override
        public Feedback dispute(String attemptId) {
            return real.dispute(attemptId);
        }
    }

    /** The real workspace with {@link TroubledPractice} in place of its practice service. */
    private record TroubledWorkspace(Workspace real, PracticeService practice) implements Workspace {

        TroubledWorkspace(Workspace real, Trouble trouble) {
            this(real, new TroubledPractice(real.practice(), trouble));
        }

        @Override
        public ProfileSummary profile() {
            return real.profile();
        }

        @Override
        public CourseService courses() {
            return real.courses();
        }

        @Override
        public DocumentService documents() {
            return real.documents();
        }

        @Override
        public AskService ask() {
            return real.ask();
        }

        @Override
        public GenerationService generation() {
            return real.generation();
        }

        @Override
        public ItemService items() {
            return real.items();
        }

        @Override
        public ProgressService progress() {
            return real.progress();
        }

        @Override
        public SharingService sharing() {
            return real.sharing();
        }

        @Override
        public SettingsService settings() {
            return real.settings();
        }

        @Override
        public AssistantService assistant() {
            return real.assistant();
        }

        @Override
        public JobQueue jobs() {
            return real.jobs();
        }

        @Override
        public EventBus events() {
            return real.events();
        }

        @Override
        public void close() {
            real.close();
        }
    }
}
