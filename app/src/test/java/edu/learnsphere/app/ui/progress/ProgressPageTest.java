package edu.learnsphere.app.ui.progress;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.theme.Typography;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.charts.BarChart;
import edu.learnsphere.app.ui.components.charts.ColumnChart;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.CourseProgress;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.testing.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
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
 * The Progress screen over the real services with a simulated study history: four courses studied, of which
 * one was left and one archived, so the overview covers only the two still being studied.
 */
@ExtendWith(ApplicationExtension.class)
class ProgressPageTest {

    private Stage stage;
    @TempDir
    Path dir;
    private RealWorkspaces real;
    private MainWindow window;
    private MutableClock clock;

    /** Correct answers by item id, for answering sessions. */
    private final Map<String, ItemContent> answers = new HashMap<>();
    /** Answers given per course. */
    private final Map<String, Integer> answered = new HashMap<>();
    /** Prompts of the questions answered wrongly, in the order answered. */
    private final List<String> missed = new java.util.ArrayList<>();

    @Start
    void start(Stage stage) {
        this.stage = stage;
        Typography.load();
    }

    /** Opens the profile off the FX thread, after the stage is up. */
    @BeforeEach
    void openProfile() {
        clock = MutableClock.fixed();
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
    }

    @AfterEach
    void tearDown(FxRobot robot) {
        if (window != null) {
            robot.interact(window::dispose);
        }
        real.close();
    }

    @Test
    void theOverviewAddsUpOnlyTheCoursesStillBeingStudied(FxRobot robot) {
        Course biology = studiedCourse("Biology", 3, 1);
        Course chemistry = studiedCourse("Chemistry", 2, 0);
        Course left = studiedCourse("History", 3, 0);
        Course archived = studiedCourse("Art", 2, 1);
        ws().courses().leave(left.id());
        ws().courses().archive(archived.id());

        ProgressPage page = open(robot, Destination.Progress.overview());

        CourseProgress overview = page.data().progress();
        assertThat(overview.isOverview()).isTrue();
        assertThat(overview.totalReviews()).isEqualTo(answered.get(biology.id()) + answered.get(chemistry.id()));
        assertThat(page.picker().getItems()).extracting(CoursePicker.Choice::title)
                .containsExactlyInAnyOrder("All courses", "Biology", "Chemistry");
        assertThat(page.picker().getValue()).isEqualTo(CoursePicker.Choice.ALL);
        assertThat(texts(robot, ".page-subtitle")).contains("Across the 2 courses you're studying.");
        assertThat(page.data().days()).hasSize(ProgressData.STRIP_DAYS);
        assertThat(page.data().days().getLast().reviews()).as("every answer today, in any course")
                .isEqualTo(answered.values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void theForecastHasFourteenColumnsStartingToday(FxRobot robot) {
        studiedCourse("Biology", 3, 1);

        open(robot, Destination.Progress.overview());

        ColumnChart forecast = robot.lookup(".forecast-chart").queryAs(ColumnChart.class);
        assertThat(forecast.columns()).hasSize(CourseProgress.FORECAST_DAYS);
        assertThat(forecast.columns().getFirst().label()).isEqualTo("Today");
        assertThat(forecast.columns().getFirst().highlighted()).isTrue();
        assertThat(forecast.columns().stream().filter(ColumnChart.Column::highlighted)).hasSize(1);
        assertThat(forecast.getAccessibleText()).startsWith("Reviews coming up over the next 14 days: Today");
    }

    @Test
    void pickingACourseSwitchesToItsDataInPlace(FxRobot robot) {
        Course biology = studiedCourse("Biology", 3, 1);
        Course chemistry = studiedCourse("Chemistry", 2, 0);
        ProgressPage page = open(robot, Destination.Progress.overview());

        robot.clickOn(".progress-picker");
        robot.clickOn(robot.lookup(".progress-picker-cell").match(cell -> cell instanceof Labeled l
                && "Chemistry".equals(l.getText()) && cell.getScene() != stage.getScene()).queryLabeled());
        settle();

        assertThat(window.navigator().current()).isEqualTo(new Destination.Progress(chemistry.id()));
        assertThat(window.navigator().currentView()).as("switched in place").isSameAs(page);
        assertThat(page.data().progress().courseId()).isEqualTo(chemistry.id());
        assertThat(page.data().progress().totalReviews()).isEqualTo(answered.get(chemistry.id()));
        assertThat(texts(robot, ".page-title")).containsExactly("Chemistry");
        assertThat(robot.lookup(".modules-card").tryQuery()).isPresent();

        robot.interact(() -> page.picker().pick(biology.id()));
        settle();
        assertThat(page.data().progress().totalReviews()).isEqualTo(answered.get(biology.id()));
        assertThat(texts(robot, ".page-title")).containsExactly("Biology");

        robot.interact(() -> page.picker().pick(null));
        settle();
        assertThat(window.navigator().current()).isEqualTo(Destination.Progress.overview());
        assertThat(page.data().isOverview()).isTrue();
        assertThat(robot.lookup(".modules-card").tryQuery()).isEmpty();
    }

    @Test
    void weakConceptsCanBePractisedFromTheMasteryChart(FxRobot robot) {
        Course biology = studiedCourse("Biology", 3, 3);
        ProgressPage page = open(robot, new Destination.Progress(biology.id()));

        assertThat(page.mastery()).isNotNull();
        BarChart chart = page.mastery().chart();
        assertThat(chart.bars()).isNotEmpty();
        assertThat(chart.bars().getFirst().emphasized()).as("weakest first, in the warning colour").isTrue();

        robot.clickOn("Practise weak concepts");
        settle();

        assertThat(window.navigator().current()).isInstanceOfSatisfying(Destination.Study.class,
                study -> assertThat(study.request()).isEqualTo(SessionRequest.weakConcepts(biology.id())));
    }

    @Test
    void aCreatorSeesTheirQuestionsHardestFirstAndOpensThemInTheStudio(FxRobot robot) {
        Course biology = studiedCourse("Biology", 3, 1);
        open(robot, new Destination.Progress(biology.id()));

        assertThat(robot.lookup(".creator-section").tryQuery()).isPresent();
        List<Node> rows = List.copyOf(robot.lookup(".question-row").queryAll());
        assertThat(rows).as("the one question answered wrongly").hasSize(1);
        assertThat(rows.getFirst().getAccessibleText()).startsWith(missed.getFirst() + ". 0% right.");
        assertThat(rows.getFirst().isFocusTraversable()).isTrue();

        // From the keyboard, as the row is below the fold.
        robot.interact(() -> rows.getFirst().requestFocus());
        robot.type(javafx.scene.input.KeyCode.ENTER);
        settle();

        assertThat(window.navigator().current()).isEqualTo(new Destination.Studio(biology.id(), StudioTab.REVIEW));
    }

    @Test
    void aNewLearnerIsToldWhatAppearsAfterTheFirstSession(FxRobot robot) {
        Course biology = course("Biology", 3);
        ws().courses().enrol(biology.id());

        open(robot, Destination.Progress.overview());

        EmptyState empty = robot.lookup(".empty-state").queryAs(EmptyState.class);
        assertThat(empty.titleText()).isEqualTo("Your progress appears after your first session");
        assertThat(robot.lookup(".progress-ledger").tryQuery()).isEmpty();

        robot.clickOn("Start studying");
        settle();
        assertThat(window.navigator().current()).isInstanceOfSatisfying(Destination.Study.class,
                study -> assertThat(study.request().kind()).isEqualTo(SessionKind.STUDY_NEXT));
    }

    @Test
    void withNoCoursesTheLibraryIsOffered(FxRobot robot) {
        open(robot, Destination.Progress.overview());

        EmptyState empty = robot.lookup(".empty-state").queryAs(EmptyState.class);
        assertThat(empty.titleText()).isEqualTo("No courses to follow yet");
        assertThat(robot.lookup(".progress-picker").tryQuery()).as("nothing to pick").isEmpty();
        Button library = (Button) empty.actions().getChildren().getFirst();
        assertThat(library.getText()).isEqualTo("Open Library");
        assertThat(library.getStyleClass()).contains("primary");
    }

    @Test
    void aCourseThatCannotBeReadShowsAnErrorWithTryAgain(FxRobot robot) {
        open(robot, new Destination.Progress("a-course-that-does-not-exist"));

        EmptyState error = robot.lookup(".empty-state").queryAs(EmptyState.class);
        assertThat(error.titleText()).isEqualTo("Your progress couldn't be shown");
        assertThat(error.messageText()).isNotBlank().doesNotContain("Exception").doesNotContain("a-course-that-does-not-exist");
        assertThat(error.actions().getChildren()).extracting(node -> ((Button) node).getText())
                .containsExactly("Try again", "Go to Home");
    }

    @Test
    void theScreenHoldsItsShapeWhileLoading(FxRobot robot) {
        open(robot, Destination.Progress.overview());

        ProgressPage[] loading = new ProgressPage[1];
        robot.interact(() -> loading[0] = new ProgressPage(real.context(), window.navigator(), Destination.Progress.overview()));

        // The page scrolls its content, which joins the scene only once shown: look in the content itself.
        Node content = ((javafx.scene.control.ScrollPane) loading[0].root()).getContent();
        assertThat(loading[0].data()).isNull();
        assertThat(content.lookupAll(".progress-skeleton")).hasSize(4);
        assertThat(content.lookupAll(".skeleton")).isNotEmpty();
    }

    @Test
    void newAnswersElsewhereRefreshTheScreen(FxRobot robot) {
        Course biology = studiedCourse("Biology", 3, 0);
        ProgressPage page = open(robot, Destination.Progress.overview());
        int before = page.data().progress().totalReviews();

        clock.advance(Duration.ofDays(2));
        study(biology, 0);
        settle();

        assertThat(page.data().progress().totalReviews()).isGreaterThan(before);
    }

    // ---- setting up ---------------------------------------------------------------------------

    private Workspace ws() {
        return real.workspace();
    }

    /** A course made here with one module, one concept per item and {@code items} approved questions. */
    private Course course(String title, int items) {
        Course course = ws().courses().create(CourseDraft.titled(title));
        CourseModule module = ws().courses().addModule(course.id(), "Basics");
        for (int i = 1; i <= items; i++) {
            Concept concept = ws().generation().addConcept(course.id(), module.id(), title + " concept " + i, "");
            ItemContent content = new ItemContent(ItemType.MCQ, title + " question " + i, "Right " + i, "",
                    List.of("Wrong A" + i, "Wrong B" + i, "Wrong C" + i), Difficulty.MEDIUM, Set.of(concept.id()));
            LearningItem item = ws().items().create(course.id(), module.id(), content);
            ws().items().approve(item.id());
            answers.put(item.id(), content);
        }
        return course;
    }

    /** A course studied once: every question answered, the first {@code wrong} of them wrongly. */
    private Course studiedCourse(String title, int items, int wrong) {
        Course course = course(title, items);
        ws().courses().enrol(course.id());
        study(course, wrong);
        return course;
    }

    private void study(Course course, int wrong) {
        StudySession session = ws().practice().start(SessionRequest.studyNext(course.id()));
        int index = 0;
        for (SessionItem shown : session.items()) {
            ItemContent content = answers.get(shown.itemId());
            boolean right = index++ >= wrong;
            String answer = right ? content.answer() : content.distractors().getFirst();
            if (!right) {
                missed.add(content.prompt());
            }
            ws().practice().submit(session.id(), Response.answer(shown.itemId(), answer, null, false, 20_000));
            answered.merge(course.id(), 1, Integer::sum);
            clock.advance(Duration.ofSeconds(20));
        }
        ws().practice().finish(session.id());
    }

    private ProgressPage open(FxRobot robot, Destination.Progress destination) {
        robot.interact(() -> {
            window = new MainWindow(real.context());
            Scene scene = new Scene(window, 1360, 900);
            Theme.install(scene, ThemePreference.LIGHT);
            stage.setScene(scene);
            stage.show();
            window.navigator().reset(destination);
        });
        settle();
        return (ProgressPage) window.navigator().currentView();
    }

    private static void settle() {
        for (int round = 0; round < 3; round++) {
            assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(10))).isTrue();
            WaitForAsyncUtils.waitForFxEvents();
        }
    }

    private static List<String> texts(FxRobot robot, String query) {
        return robot.lookup(query).queryAll().stream()
                .filter(Node::isVisible)
                .filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText())
                .toList();
    }
}
