package edu.learnsphere.app.ui.library;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.library.CourseFixtures.Built;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.ExportOptions;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.sharing.SharingMessages;
import edu.learnsphere.core.testing.MutableClock;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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

/** The Library over the real services: importing a shared course, refusing the profile's own, and New course. */
@ExtendWith(ApplicationExtension.class)
class LibraryPageTest {

    private static final String CODE = "orbit-42";

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
    void importsAProtectedCourseSharedByAnotherProfile(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        Path file = sharedCourse(real.workspace(), Visibility.PROTECTED);

        // A second learner on the same computer imports the file.
        ProfileSummary grace = real.context().profiles().create(new ProfileDraft("Grace Hopper", 150, null));
        Workspace before = real.workspace();
        real.context().open(grace.id(), null);
        RealWorkspaces.await(() -> real.context().workspace() != before && real.context().workspace() != null,
                Duration.ofSeconds(30), "the second profile to open");

        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), Destination.LIBRARY);
        LibraryPage library = screen.view(LibraryPage.class);
        assertThat(texts(robot, ".empty-state-title")).containsExactly("No courses to study yet");

        AtomicReference<ImportCourseDialog> dialog = new AtomicReference<>();
        robot.interact(() -> dialog.set(library.importFrom(file)));
        ScreenHarness.settle();
        ImportCourseDialog sheet = dialog.get();
        assertThat(sheet.step()).isEqualTo(ImportCourseDialog.Step.PREVIEW);
        assertThat(sheet.info().title()).isEqualTo("Cell Biology");
        assertThat(texts(robot, ".import-facts")).singleElement().asString()
                .startsWith("Version 1 · 1 module · 3 questions");
        assertThat(sheet.primaryButton().getText()).isEqualTo("Add to library");
        assertThat(sheet.primaryButton().isDisabled()).as("no code typed yet").isTrue();

        robot.clickOn(sheet.codeField()).write("wrong-code");
        robot.clickOn(sheet.primaryButton());
        ScreenHarness.settle();
        assertThat(sheet.step()).isEqualTo(ImportCourseDialog.Step.PREVIEW);
        assertThat(sheet.codeErrorText()).isEqualTo(UserMessage.WRONG_COURSE_CODE.text());

        robot.interact(() -> sheet.codeField().setText(CODE));
        robot.clickOn(sheet.primaryButton());
        ScreenHarness.until(() -> sheet.step() == ImportCourseDialog.Step.DONE);
        assertThat(sheet.sheet().titleText()).isEqualTo("Cell Biology is in your library");
        assertThat(texts(robot, ".import-summary")).singleElement().asString().startsWith("3 questions are ready to study");

        ScreenHarness.until(() -> library.cards().size() == 1);
        assertThat(library.cards().getFirst().course().title()).isEqualTo("Cell Biology");
        assertThat(library.cards().getFirst().course().enrolled()).isTrue();

        robot.clickOn(sheet.primaryButton());
        ScreenHarness.settle();
        String imported = library.cards().isEmpty() ? null : library.cards().getFirst().course().id();
        assertThat(screen.window().navigator().current()).isInstanceOf(Destination.Course.class);
        assertThat(((Destination.Course) screen.window().navigator().current()).courseId()).isEqualTo(imported);
    }

    @Test
    void refusesToImportACourseMadeInThisProfile(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        Path file = sharedCourse(real.workspace(), Visibility.SHAREABLE);

        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), Destination.LIBRARY);
        LibraryPage library = screen.view(LibraryPage.class);
        AtomicReference<ImportCourseDialog> dialog = new AtomicReference<>();
        robot.interact(() -> dialog.set(library.importFrom(file)));
        ScreenHarness.settle();

        ImportCourseDialog sheet = dialog.get();
        assertThat(sheet.step()).isEqualTo(ImportCourseDialog.Step.PREVIEW);
        assertThat(texts(robot, ".sheet-notice-text")).containsExactly(SharingMessages.COURSE_MADE_HERE.text());
        assertThat(sheet.primaryButton().getText()).isEqualTo("Open course");
        assertThat(real.workspace().courses().list(CourseFilter.all())).hasSize(1);
    }

    @Test
    void createsANewCourseAndOpensItInTheStudio(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), Destination.LIBRARY);
        LibraryPage library = screen.view(LibraryPage.class);
        robot.interact(() -> library.shelf().setValue(LibraryPage.Shelf.CREATING));
        assertThat(texts(robot, ".empty-state-title")).containsExactly("Turn your PDFs into a course");

        robot.clickOn("New course");
        ScreenHarness.settle();
        assertThat(texts(robot, ".sheet-title")).containsExactly("New course");
        assertThat(robot.lookup(".button").queryAllAs(javafx.scene.control.Button.class).stream()
                .filter(b -> b.getText() != null && b.getText().equals("Create course")).findFirst().orElseThrow()
                .isDisabled()).as("a title is needed").isTrue();

        robot.write("Cell Biology");
        robot.clickOn(robot.lookup(".hue-swatch").queryAllAs(javafx.scene.control.ToggleButton.class).stream()
                .filter(b -> b.getAccessibleText().equals("Cover colour: Coral")).findFirst().orElseThrow());
        robot.clickOn("Create course");
        ScreenHarness.settle();

        List<CourseSummary> courses = real.workspace().courses().list(CourseFilter.creating());
        assertThat(courses).singleElement().satisfies(course -> {
            assertThat(course.title()).isEqualTo("Cell Biology");
            assertThat(course.authorName()).isEqualTo("Ada Lovelace");
            assertThat(course.coverHue()).isZero();
        });
        assertThat(screen.window().navigator().current())
                .isEqualTo(new Destination.Studio(courses.getFirst().id(), StudioTab.DETAILS));
    }

    @Test
    void searchFiltersByTitleOrAuthorAndSaysWhenNothingMatches(FxRobot robot) {
        real = RealWorkspaces.open(dir, FakeAi.create(), clock);
        Workspace ws = real.workspace();
        CourseFixtures.flashcardCourse(ws, "Cell Biology", true, 1);
        CourseFixtures.flashcardCourse(ws, "Organic Chemistry", true, 1);
        screen = new ScreenHarness(robot);
        screen.show(stage, real.context(), Destination.LIBRARY);
        LibraryPage library = screen.view(LibraryPage.class);
        assertThat(library.cards()).hasSize(2);

        robot.interact(() -> library.searchField().setText("chem"));
        assertThat(library.cards()).extracting(card -> card.course().title()).containsExactly("Organic Chemistry");

        robot.interact(() -> library.searchField().setText("linear algebra"));
        assertThat(library.cards()).isEmpty();
        assertThat(texts(robot, ".empty-state-title")).containsExactly("No courses match “linear algebra”");
        robot.clickOn("Clear search");
        assertThat(library.cards()).hasSize(2);
    }

    /** A course made in the open profile with three approved cards, published and saved as a course file. */
    private Path sharedCourse(Workspace ws, Visibility visibility) {
        Built built = CourseFixtures.flashcardCourse(ws, "Cell Biology", true, 3);
        Course course = built.course();
        ws.courses().setVisibility(course.id(), visibility);
        ws.courses().publish(course.id(), "First release");
        Path file = dir.resolve("cell-biology.learnsphere");
        ws.sharing().exportCourse(course.id(), file, visibility == Visibility.PROTECTED
                ? ExportOptions.protectedWith(CODE.toCharArray()) : ExportOptions.standard());
        return file;
    }

    private static List<String> texts(FxRobot robot, String query) {
        return robot.lookup(query).queryAll().stream()
                .filter(Node::isVisible)
                .filter(Labeled.class::isInstance)
                .map(node -> ((Labeled) node).getText())
                .toList();
    }
}
