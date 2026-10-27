package edu.learnsphere.app.ui.home;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.library.CourseFixtures;
import edu.learnsphere.app.ui.library.CourseFixtures.Built;
import edu.learnsphere.app.ui.library.ScreenHarness;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DashboardData;
import edu.learnsphere.core.service.DayActivity;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.StudyNext;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.testing.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collections;
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

/** Home over the real services: the Study next numbers, the caught-up state and the welcome. */
@ExtendWith(ApplicationExtension.class)
class HomePageTest {

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
    void studyNextCountsDueReviewsAndNewQuestionsWithinTheDailyLimit(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        Workspace ws = real.workspace();
        ws.settings().set(SettingKeys.NEW_ITEMS_PER_DAY, 3);
        Built course = CourseFixtures.flashcardCourse(ws, "Cell Biology", true, 5);
        assertThat(CourseFixtures.study(ws, SessionRequest.studyNext(course.id()))).as("today's new cards").isEqualTo(3);

        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), Destination.HOME);
        HomePage home = screen.view(HomePage.class);
        assertThat(home.greetingLabel().getText()).isEqualTo("Good morning, Ada");
        StudyNextCard hero = home.hero();
        assertThat(hero.state()).isEqualTo(StudyNextCard.State.CAUGHT_UP);
        assertThat(hero.headlineLabel().getText()).isEqualTo("You're all caught up.");
        assertThat(hero.detailLabel().getText()).as("new cards come back after a short learning step")
                .isEqualTo("Your next review is later today.");
        assertThat(hero.startButton().isVisible()).isFalse();
        assertThat(hero.beads().beads()).as("today's reviews, done").hasSize(3)
                .containsOnly(SessionBeads.Kind.DONE);

        // A month on, the three cards are due and two unseen cards fit today's limit of three.
        clock.advance(Duration.ofDays(30));
        robot.interact(() -> screen.window().navigator().reset(Destination.HOME));
        ScreenHarness.settle();
        hero = screen.view(HomePage.class).hero();
        assertThat(hero.state()).isEqualTo(StudyNextCard.State.STUDY);
        assertThat(hero.headlineLabel().getText()).isEqualTo("3 reviews and 2 new questions");
        assertThat(hero.detailLabel().getText()).isEqualTo("About a minute");
        assertThat(hero.beads().beads()).containsExactly(SessionBeads.Kind.DUE, SessionBeads.Kind.DUE,
                SessionBeads.Kind.DUE, SessionBeads.Kind.NEW, SessionBeads.Kind.NEW);
        assertThat(hero.startButton().isVisible()).isTrue();

        // Lowering the daily limit updates the card while it is on screen.
        StudyNextCard live = hero;
        ws.settings().set(SettingKeys.NEW_ITEMS_PER_DAY, 1);
        ScreenHarness.until(() -> live.headlineLabel().getText().equals("3 reviews and 1 new question"));
        assertThat(live.beads().beads()).containsExactlyElementsOf(
                List.of(SessionBeads.Kind.DUE, SessionBeads.Kind.DUE, SessionBeads.Kind.DUE, SessionBeads.Kind.NEW));

        robot.clickOn(live.startButton());
        assertThat(screen.window().navigator().current())
                .isEqualTo(new Destination.Study(SessionRequest.studyNext()));
    }

    @Test
    void aProfileThatStudiesNothingIsPointedToTheLibrary(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        CourseFixtures.flashcardCourse(real.workspace(), "Cell Biology", false, 2);

        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), Destination.HOME);
        StudyNextCard hero = screen.view(HomePage.class).hero();

        assertThat(hero.state()).isEqualTo(StudyNextCard.State.NOT_STUDYING);
        assertThat(hero.headlineLabel().getText()).isEqualTo("Nothing to study yet");
        robot.clickOn(hero.libraryButton());
        assertThat(screen.window().navigator().current()).isEqualTo(Destination.LIBRARY);
    }

    @Test
    void aNewProfileIsInvitedToImportOrCreateACourse(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);

        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), Destination.HOME);

        assertThat(texts(robot, ".empty-state-title")).containsExactly("Start with a course");
        robot.clickOn("Create a course");
        ScreenHarness.settle();
        assertThat(texts(robot, ".sheet-title")).containsExactly("New course");
    }

    @Test
    void caughtUpWordsSayWhenTheNextReviewIs() {
        assertThat(StudyNextCard.headline(Dashboards.caughtUp(clock, 0, null))).isEqualTo("You're all caught up.");
        assertThat(StudyNextCard.detail(Dashboards.caughtUp(clock, 0, clock.millis() + Duration.ofHours(3).toMillis()),
                clock)).isEqualTo("Your next review is later today.");
        assertThat(StudyNextCard.detail(Dashboards.caughtUp(clock, 0, clock.millis() + Duration.ofDays(1).toMillis()),
                clock)).isEqualTo("New reviews unlock tomorrow.");
        assertThat(StudyNextCard.detail(Dashboards.caughtUp(clock, 0, clock.millis() + Duration.ofDays(4).toMillis()),
                clock)).isEqualTo("Your next review is in 4 days.");
        assertThat(StudyNextCard.detail(Dashboards.caughtUp(clock, 6, clock.millis() + Duration.ofDays(4).toMillis()),
                clock)).isEqualTo("New questions unlock tomorrow. Your next review is in 4 days.");
    }

    @Test
    void headlinesNameWhatIsWaiting() {
        assertThat(StudyNextCard.headline(Dashboards.waiting(clock, 1, 0))).isEqualTo("1 review is due");
        assertThat(StudyNextCard.headline(Dashboards.waiting(clock, 12, 0))).isEqualTo("12 reviews are due");
        assertThat(StudyNextCard.headline(Dashboards.waiting(clock, 0, 1))).isEqualTo("1 new question to learn");
        assertThat(StudyNextCard.headline(Dashboards.waiting(clock, 4, 1))).isEqualTo("4 reviews and 1 new question");
        assertThat(HomePage.greeting("Ada Lovelace", LocalTime.of(19, 30))).isEqualTo("Good evening, Ada");
        assertThat(HomePage.greeting("Ada Lovelace", LocalTime.of(13, 0))).isEqualTo("Good afternoon, Ada");
    }

    private static List<String> texts(FxRobot robot, String query) {
        return robot.lookup(query).queryAll().stream()
                .filter(Node::isVisible)
                .filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText())
                .toList();
    }

    /** Dashboards for the wording tests. */
    private static final class Dashboards {

        static DashboardData caughtUp(MutableClock clock, int unseen, Long nextReviewAt) {
            return data(clock, new StudyNext(null, 0, 0, 0, nextReviewAt), unseen);
        }

        static DashboardData waiting(MutableClock clock, int due, int fresh) {
            return data(clock, new StudyNext(null, due, fresh, 3, null), 10);
        }

        private static DashboardData data(MutableClock clock,
                                                                       StudyNext next,
                                                                       int unseen) {
            var course = new CourseSummary("c1", "Cell Biology", "", "Ada", 150,
                    CourseOrigin.LOCAL, CourseStatus.PUBLISHED,
                    Visibility.PRIVATE, 1, true, true, 1, 0, 0, 0, 20, 0, unseen,
                    next.dueCount(), 0, 0.5, null, 0L);
            return new DashboardData("Ada Lovelace", LocalDate.now(clock), next,
                    List.of(course), Collections.nCopies(7, new DayActivity(
                            LocalDate.now(clock), 0, false)), 0, 0, List.of());
        }
    }
}
