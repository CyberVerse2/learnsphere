package edu.learnsphere.app.ui.course;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.library.CourseFixtures;
import edu.learnsphere.app.ui.library.CourseFixtures.Built;
import edu.learnsphere.app.ui.library.ScreenHarness;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.testing.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
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

/**
 * The course page over the real services: module progress on the path, the main action, the practice options,
 * the update note and a missing course.
 */
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
    void startStudyingACourseTheLearnerIsNotStudyingOpensTheSession(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        Workspace ws = real.workspace();
        Built course = CourseFixtures.flashcardCourse(ws, "Cell Biology", false, 2);

        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), new Destination.Course(course.id()));
        CoursePage page = screen.view(CoursePage.class);
        assertThat(texts(robot, ".course-today-headline")).containsExactly("You're not studying this course");
        assertThat(page.primaryButton().getText()).isEqualTo("Start studying");

        robot.clickOn(page.primaryButton());
        ScreenHarness.until(() -> screen.window().navigator().current() instanceof Destination.Study);
        assertThat(screen.window().navigator().current())
                .isEqualTo(new Destination.Study(SessionRequest.studyNext(course.id())));
        assertThat(ws.courses().summary(course.id()).enrolled()).isTrue();
    }

    @Test
    void aCaughtUpCourseTheLearnerLeftCanBeStudiedAgainWithoutASession(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        Workspace ws = real.workspace();
        Built course = CourseFixtures.flashcardCourse(ws, "Cell Biology", true, 2);
        CourseFixtures.study(ws, SessionRequest.studyNext(course.id()));
        ws.courses().leave(course.id());

        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), new Destination.Course(course.id()));
        CoursePage page = screen.view(CoursePage.class);
        assertThat(page.primaryButton().getText()).as("the Library menu's words").isEqualTo("Study this course");

        robot.clickOn(page.primaryButton());
        ScreenHarness.until(() -> page.detail() != null && page.detail().summary().enrolled());
        assertThat(screen.window().navigator().current()).isEqualTo(new Destination.Course(course.id()));
        assertThat(texts(robot, ".course-today-headline")).containsExactly("All caught up");
    }

    @Test
    void flashcardsArePractisedOnlyInACourseThatHasThem(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        Workspace ws = real.workspace();
        Course course = ws.courses().create(new CourseDraft("Cell Biology", "", "", "Ada Lovelace", 150));
        LearningItem statement = ws.items().create(course.id(), null, new ItemContent(ItemType.TRUE_FALSE,
                "Every living thing is made of cells.", "true", "", List.of(), null, Set.of()));
        ws.items().approve(statement.id());
        ws.courses().enrol(course.id());

        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), new Destination.Course(course.id()));
        CoursePage page = screen.view(CoursePage.class);
        assertThat(page.flashcardsRow().isDisabled()).isTrue();
        assertThat(page.flashcardsRow().captionLabel().getText()).isEqualTo("No flashcards in this course");

        LearningItem card = ws.items().create(course.id(), null, ItemContent.flashcard("Cell", "The unit of life"));
        ws.items().approve(card.id());
        ScreenHarness.until(() -> page.flashcardsRow() != null && !page.flashcardsRow().isDisabled());
        assertThat(page.flashcardsRow().captionLabel().getText()).isEqualTo("Flip through the course's cards");
        robot.clickOn(page.flashcardsRow());
        assertThat(screen.window().navigator().current())
                .isEqualTo(new Destination.Study(SessionRequest.flashcards(course.id(), null)));
    }

    @Test
    void theUpdateNoteNamesTheNewestVersionWhileItIsRecent() {
        long now = clock.millis();
        long day = Duration.ofDays(1).toMillis();
        CourseVersion first = new CourseVersion("v1", "c1", 1, "", now - 60 * day, now - 60 * day);
        CourseVersion second = new CourseVersion("v2", "c1", 2, "A new chapter on mitosis", now - 3 * day, now - 3 * day);

        assertThat(CoursePage.recentUpdate(imported(2), List.of(second, first), now)).isEqualTo(second);
        assertThat(CoursePage.recentUpdate(imported(1), List.of(second, first), now))
                .as("going back to version 1 is not an update").isNull();
        assertThat(CoursePage.recentUpdate(imported(1), List.of(first), now)).as("the first import").isNull();
        assertThat(CoursePage.recentUpdate(imported(2), List.of(second, first), now + 40 * day))
                .as("an update weeks ago").isNull();
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

    private static CourseSummary imported(int version) {
        return new CourseSummary("c1", "Cell Biology", "", "Ada Lovelace", 150, CourseOrigin.IMPORTED,
                CourseStatus.PUBLISHED, Visibility.SHAREABLE, version, false, true, 1, 1, 0, 0, 10, 0, 0, 0, 0,
                0.5, null, 0L);
    }

    private static List<String> texts(FxRobot robot, String query) {
        return robot.lookup(query).queryAll().stream()
                .filter(Node::isVisible)
                .filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText())
                .toList();
    }
}
