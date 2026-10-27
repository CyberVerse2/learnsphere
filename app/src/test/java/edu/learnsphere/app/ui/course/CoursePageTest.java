package edu.learnsphere.app.ui.course;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.library.CourseFixtures;
import edu.learnsphere.app.ui.library.CourseFixtures.Built;
import edu.learnsphere.app.ui.library.ScreenHarness;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.testing.MutableClock;
import java.nio.file.Path;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;

/** The course page over the real services: module progress on the path, actions, and a missing course. */
@ExtendWith(ApplicationExtension.class)
class CoursePageTest {

    @TempDir
    Path dir;

    private Stage stage;
    private RealWorkspaces real;
    private ScreenHarness screen;
    private final MutableClock clock = MutableClock.fixed();

    @Start
    void start(Stage stage) {
        this.stage = stage;
    }

    @AfterEach
    void tearDown() {
        if (screen != null) {
            screen.close();
        }
        if (real != null) {
            real.close();
        }
    }

    @Test
    void modulePathShowsHowMuchOfEachModuleIsStudied(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        Workspace ws = real.workspace();
        Built course = CourseFixtures.flashcardCourse(ws, "Cell Biology", true, 3, 2);
        assertThat(CourseFixtures.study(ws, SessionRequest.moduleQuiz(course.id(), course.modules().get(0).id())))
                .isEqualTo(3);

        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), new Destination.Course(course.id()));
        CoursePage page = screen.view(CoursePage.class);
        List<ModulePath.Step> steps = page.modulePath().steps();
        assertThat(steps).hasSize(2);
        assertThat(steps.get(0).module().title()).isEqualTo("Module 1");
        assertThat(steps.get(0).studiedLabel().getText()).isEqualTo("All 3 studied");
        assertThat(steps.get(0).ring().getProgress()).isEqualTo(1.0);
        assertThat(steps.get(0).getStyleClass()).contains("complete");
        assertThat(steps.get(1).studiedLabel().getText()).isEqualTo("0 of 2 studied");
        assertThat(steps.get(1).ring().getProgress()).isZero();
        assertThat(texts(robot, ".section-subtitle")).contains("3 of 5 questions studied");

        // Two new cards wait in module 2, so Study is the main action.
        assertThat(page.primaryButton().getText()).isEqualTo("Start studying");

        // Studying one card of module 2 moves its step while the page is open.
        StudySession session = ws.practice().start(SessionRequest.moduleQuiz(course.id(), course.modules().get(1).id()));
        ws.practice().submit(session.id(), Response.flashcard(session.items().getFirst().itemId(), Rating.GOOD, 9_000));
        ScreenHarness.until(() -> page.modulePath().steps().get(1).studiedLabel().getText().equals("1 of 2 studied"));
        assertThat(page.modulePath().steps().get(1).ring().getProgress()).isEqualTo(0.5);

        robot.clickOn(page.modulePath().steps().get(1).quizButton());
        assertThat(screen.window().navigator().current()).isEqualTo(
                new Destination.Study(SessionRequest.moduleQuiz(course.id(), course.modules().get(1).id())));
    }

    @Test
    void aCourseTheLearnerLeftOffersToStudyItAgain(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        Workspace ws = real.workspace();
        Built course = CourseFixtures.flashcardCourse(ws, "Cell Biology", false, 2);

        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), new Destination.Course(course.id()));
        CoursePage page = screen.view(CoursePage.class);
        assertThat(texts(robot, ".course-today-headline")).containsExactly("You're not studying this course");
        assertThat(page.primaryButton().getText()).isEqualTo("Start studying");

        robot.clickOn(page.primaryButton());
        ScreenHarness.until(() -> page.detail() != null && page.detail().summary().enrolled());
        assertThat(ws.courses().summary(course.id()).enrolled()).isTrue();
        assertThat(texts(robot, ".course-today-unit")).containsExactly("new questions");
    }

    @Test
    void aMissingCourseLeadsBackToTheLibrary(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), new Destination.Course("no-such-course"));

        assertThat(texts(robot, ".empty-state-title")).containsExactly("This course isn't in your library");
        robot.clickOn("Go to Library");
        assertThat(screen.window().navigator().current()).isEqualTo(Destination.LIBRARY);
    }

    @Test
    void conceptsAreListedWeakestFirstWithTheirAccuracy() {
        ConceptMastery strong = new ConceptMastery("a", "Paging", "m", 0.9, 1.0, 4, 4, 6);
        ConceptMastery weak = new ConceptMastery("b", "Deadlock", "m", 0.35, 0.4, 5, 5, 10);
        ConceptMastery fresh = new ConceptMastery("c", "Threads", "m", null, null, 3, 0, 0);
        ConceptMastery middling = new ConceptMastery("d", "Scheduling", "m", 0.7, 0.75, 4, 4, 4);

        assertThat(CoursePage.weakestFirst(List.of(fresh, strong, weak, middling)))
                .extracting(ConceptMastery::name).containsExactly("Deadlock", "Scheduling", "Paging", "Threads");
        assertThat(CoursePage.conceptTooltip(weak))
                .isEqualTo("Deadlock: 35% mastered. Right in 4 of your last 10 answers. 5 questions.");
        assertThat(CoursePage.conceptTooltip(middling))
                .isEqualTo("Scheduling: 70% mastered. Right in 3 of your last 4 answers. 4 questions.");
        assertThat(CoursePage.conceptTooltip(fresh)).isEqualTo("Threads: not studied yet. 3 questions.");
    }

    private static List<String> texts(FxRobot robot, String query) {
        return robot.lookup(query).queryAll().stream()
                .filter(Node::isVisible)
                .filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText())
                .toList();
    }
}
