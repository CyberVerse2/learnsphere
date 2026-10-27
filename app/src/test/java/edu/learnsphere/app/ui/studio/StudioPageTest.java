package edu.learnsphere.app.ui.studio;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.app.testing.RealWorkspaces;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.MainWindow;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.UiExecutors;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.PackageInfo;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.service.Workspace;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

/**
 * The Studio over the real services (SQLite, the pdf module, search and the study assistant's fakes): making a
 * course, planning its modules, importing PDFs, checking scanned pages, publishing and exporting.
 */
@ExtendWith(ApplicationExtension.class)
class StudioPageTest {

    @TempDir
    Path dir;

    private RealWorkspaces real;
    private MainWindow window;
    private final FakeFiles files = new FakeFiles();

    @Start
    void start(Stage stage) {
        real = RealWorkspaces.open(dir);
        window = new MainWindow(real.context());
        window.navigator().register(Destination.Studio.class,
                d -> new StudioPage(real.context(), window.navigator(), d, files));
        Scene scene = new Scene(window, 1360, 900);
        Theme.install(scene, ThemePreference.LIGHT);
        stage.setScene(scene);
        stage.show();
    }

    @AfterEach
    void tearDown(FxRobot robot) {
        robot.interact(window::dispose);
        real.close();
    }

    // -------------------------------------------------------------------------------------------------
    // Courses
    // -------------------------------------------------------------------------------------------------

    @Test
    void createsACourseFromTheEmptyStudio(FxRobot robot) throws TimeoutException {
        open(robot, Destination.Studio.courses());
        assertThat(texts(robot, ".empty-state-title")).contains("Make your first course");

        robot.clickOn(robot.lookup(".new-course-button").queryButton());
        settle();
        robot.clickOn(robot.lookup(".modal-confirm").queryButton());
        settle();
        assertThat(texts(robot, ".studio-dialog-error")).containsExactly(NewCourseDialog.TITLE_NEEDED);

        robot.clickOn(".new-course-title").write("Organic Chemistry").push(KeyCode.ENTER);
        waitFor(() -> window.navigator().current() instanceof Destination.Studio s && s.courseId() != null);
        settle();

        Destination.Studio shown = (Destination.Studio) window.navigator().current();
        assertThat(shown.tab()).isEqualTo(StudioTab.MODULES);
        Course created = ws().courses().get(shown.courseId()).course();
        assertThat(created.title()).isEqualTo("Organic Chemistry");
        assertThat(created.authorName()).isEqualTo("Ada Lovelace");
        assertThat(created.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(texts(robot, ".studio-header-title")).containsExactly("Organic Chemistry");
    }

    @Test
    void savesDetailsAfterCheckingTheTitleAndAsksBeforeLeavingUnsavedChanges(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Cell Biology"));
        open(robot, new Destination.Studio(course.id(), StudioTab.DETAILS));
        DetailsSection details = (DetailsSection) page().section();

        robot.interact(() -> details.titleField().setText("   "));
        robot.clickOn(robot.lookup(".details-save").queryButton());
        settle();
        assertThat(texts(robot, ".field-error")).contains(DetailsSection.TITLE_NEEDED);
        assertThat(ws().courses().get(course.id()).course().title()).isEqualTo("Cell Biology");

        robot.interact(() -> {
            details.titleField().setText("Cell Biology for Nurses");
            details.visibilityPicker().setValue(Visibility.SHAREABLE);
        });
        robot.clickOn(details.titleField()).push(KeyCode.SHORTCUT, KeyCode.S);
        waitFor(() -> ws().courses().get(course.id()).course().visibility() == Visibility.SHAREABLE);
        settle();
        Course saved = ws().courses().get(course.id()).course();
        assertThat(saved.title()).isEqualTo("Cell Biology for Nurses");
        assertThat(details.hasUnsavedChanges()).isFalse();
        assertThat(texts(robot, ".studio-header-title")).containsExactly("Cell Biology for Nurses");

        robot.interact(() -> details.titleField().setText("Cell Biology, second draft"));
        robot.clickOn(tabButton(robot, StudioTab.MODULES));
        settle();
        assertThat(texts(robot, ".modal-title")).containsExactly("Leave without saving?");
        robot.clickOn(robot.lookup(".modal-cancel").queryButton());
        settle();
        assertThat(page().tab()).isEqualTo(StudioTab.DETAILS);
        assertThat(details.titleField().getText()).isEqualTo("Cell Biology, second draft");
    }

    @Test
    void deletesACourseOnlyAfterTheWordIsTyped(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Throwaway"));
        open(robot, new Destination.Studio(course.id(), StudioTab.DETAILS));

        // The delete row is below the fold: moving focus to it scrolls it into view, as Tab would.
        Button delete = robot.lookup(".details-delete").queryButton();
        robot.interact(delete::requestFocus);
        settle();
        robot.clickOn(delete);
        settle();
        Button confirm = robot.lookup(".modal-confirm").queryButton();
        assertThat(confirm.isDisabled()).isTrue();
        robot.clickOn(".delete-confirm-field").write("Delete ");
        assertThat(confirm.isDisabled()).isFalse();
        robot.clickOn(confirm);
        waitFor(() -> window.navigator().current().equals(Destination.Studio.courses()));
        settle();

        assertThat(ws().courses().list(edu.learnsphere.core.service.CourseFilter.creating().withArchived(true)))
                .noneMatch(c -> c.id().equals(course.id()));
    }

    // -------------------------------------------------------------------------------------------------
    // Modules
    // -------------------------------------------------------------------------------------------------

    @Test
    void addsModulesAndReordersThemWithTheKeyboardAndByDragging(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Organic Chemistry"));
        open(robot, new Destination.Studio(course.id(), StudioTab.MODULES));
        assertThat(texts(robot, ".empty-state-title")).contains("Plan the course in modules");

        robot.clickOn(".module-add-button");
        settle();
        assertThat(texts(robot, ".field-error")).contains(ModulesSection.TITLE_NEEDED);

        for (String title : List.of("Alkanes", "Alkenes", "Alkynes")) {
            robot.clickOn(".module-add-field").write(title).push(KeyCode.ENTER);
            waitFor(() -> moduleTitles().contains(title));
        }
        settle();
        ModulesSection modules = (ModulesSection) page().section();
        assertThat(modules.titles()).containsExactly("Alkanes", "Alkenes", "Alkynes");
        assertThat(page().tabCount(StudioTab.MODULES)).isEqualTo(3);

        Node third = robot.lookup(".module-row").nth(2).query();
        robot.interact(third::requestFocus);
        robot.push(KeyCode.ALT, KeyCode.UP);
        waitFor(() -> moduleTitles().equals(List.of("Alkanes", "Alkynes", "Alkenes")));
        settle();
        assertThat(modules.titles()).containsExactly("Alkanes", "Alkynes", "Alkenes");

        // Dropping the first module below the last one.
        robot.interact(() -> modules.move(0, 2));
        waitFor(() -> moduleTitles().equals(List.of("Alkynes", "Alkenes", "Alkanes")));
        List<Integer> positions = ws().courses().modules(course.id()).stream().map(CourseModule::position).toList();
        assertThat(positions).isSorted().doesNotHaveDuplicates();
    }

    @Test
    void renamesAModuleInPlaceAndAsksBeforeDeletingOneWithContent(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Organic Chemistry"));
        CourseModule module = ws().courses().addModule(course.id(), "Bonds");
        ws().documents().importPdf(course.id(), module.id(), real.textPdf("bonds.pdf", "Bonds", 2));
        open(robot, new Destination.Studio(course.id(), StudioTab.MODULES));

        robot.clickOn(robot.lookup(".module-edit").queryButton());
        settle();
        robot.interact(() -> robot.lookup(".module-title-field").queryAs(TextField.class).setText("Bonding"));
        robot.interact(() -> robot.lookup(".module-summary-field").queryAs(TextField.class).setText("Covalent and ionic bonds."));
        robot.clickOn(robot.lookup(".module-save").queryButton());
        waitFor(() -> moduleTitles().equals(List.of("Bonding")));
        assertThat(ws().courses().modules(course.id()).getFirst().summary()).isEqualTo("Covalent and ionic bonds.");

        settle();
        robot.clickOn(robot.lookup(".module-delete").queryButton());
        settle();
        assertThat(texts(robot, ".modal-title")).containsExactly("Delete “Bonding”?");
        assertThat(texts(robot, ".modal-message")).containsExactly("Its 1 PDF stays in the course without a module.");
        robot.clickOn(robot.lookup(".modal-confirm").queryButton());
        waitFor(() -> moduleTitles().isEmpty());
    }

    // -------------------------------------------------------------------------------------------------
    // PDFs and scanned pages
    // -------------------------------------------------------------------------------------------------

    @Test
    void importsATextPdfAndExplainsWhyAPasswordProtectedOneCouldNot(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Organic Chemistry"));
        Path text = real.textPdf("bonding.pdf", "Bonding", 3);
        Path locked = real.lockedPdf(real.textPdf("paper.pdf", "Past paper", 2), "past-paper.pdf", "secret");
        open(robot, new Destination.Studio(course.id(), StudioTab.DOCUMENTS));
        assertThat(texts(robot, ".empty-state-title")).contains("Add the PDFs this course is built from");

        files.pdfs = List.of(text, locked);
        robot.clickOn(robot.lookup(".empty-state-actions .button").queryButton());
        waitFor(() -> ws().documents().list(course.id()).size() == 2);
        for (DocumentSummary document : ws().documents().list(course.id())) {
            real.awaitSettled(document.id());
        }
        waitFor(() -> robot.lookup(".document-row").queryAll().size() == 2
                && texts(robot, ".document-pill .status-pill-label").contains("Ready")
                && !robot.lookup(".document-reason").queryAll().isEmpty());
        settle();

        assertThat(texts(robot, ".document-title")).containsExactly("bonding", "past-paper");
        assertThat(texts(robot, ".document-reason")).containsExactly(DocumentError.ENCRYPTED.userMessage().text());
        assertThat(texts(robot, ".document-pill .status-pill-label")).containsExactly("Ready", "Couldn't import");
        assertThat(robot.lookup(".document-retry").queryAll()).as("a locked PDF needs a new file, not another try").isEmpty();
        assertThat(robot.lookup(".document-remove").queryAll()).hasSize(1);
        assertThat(page().tabCount(StudioTab.DOCUMENTS)).isEqualTo(2);

        // The same PDF again is turned away with the reason.
        DocumentsSection documents = (DocumentsSection) page().section();
        robot.interact(() -> documents.importPdfs(List.of(text)));
        waitFor(() -> !documents.problemLines().isEmpty());
        assertThat(documents.problemLines()).containsExactly("bonding.pdf: This PDF is already in the course.");
    }

    @Test
    void approvesScannedPagesAndTheImportContinues(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Organic Chemistry"));
        DocumentSummary scanned = ws().documents().importPdf(course.id(), null, real.scannedPdf("handout.pdf", 2));
        assertThat(real.awaitSettled(scanned.id()).status()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        open(robot, new Destination.Studio(course.id(), StudioTab.DOCUMENTS));

        robot.clickOn(robot.lookup(".document-check").queryButton());
        waitFor(() -> page().section() instanceof ScannedPagesSection pages && pages.pageOnScreen() == 1
                && !pages.textArea().isDisabled());
        settle();
        assertThat(page().tab()).isEqualTo(StudioTab.PAGES);
        assertThat(texts(robot, ".scanned-note .label")).containsExactly(ScannedPagesSection.NOTE);
        assertThat(page().tabCount(StudioTab.PAGES)).isEqualTo(2);

        ScannedPagesSection pages = (ScannedPagesSection) page().section();
        robot.interact(() -> pages.textArea().setText("# Esters\n\nEsters form when an acid reacts with an alcohol."));
        assertThat(robot.lookup(".approve-page").queryButton().getText()).isEqualTo("Save and approve");
        robot.clickOn(pages.textArea()).push(KeyCode.SHORTCUT, KeyCode.ENTER);
        waitFor(() -> pages.pageOnScreen() == 2 && !pages.textArea().isDisabled());
        assertThat(ws().documents().page(scanned.id(), 1).text())
                .isEqualTo("# Esters\n\nEsters form when an acid reacts with an alcohol.");
        assertThat(ws().documents().page(scanned.id(), 1).reviewed()).isTrue();

        robot.clickOn(robot.lookup(".approve-page").queryButton());
        waitFor(() -> texts(robot, ".empty-state-title").contains("Every page is checked"));
        RealWorkspaces.await(() -> ws().documents().get(scanned.id()).status() == DocumentStatus.READY,
                Duration.ofSeconds(30), "the import to continue");
        waitFor(() -> texts(robot, ".finished-pill .status-pill-label").contains("Ready"));
    }

    // -------------------------------------------------------------------------------------------------
    // Publishing and sharing
    // -------------------------------------------------------------------------------------------------

    @Test
    void publishesAVersionOfTheApprovedQuestionsWithItsNote(FxRobot robot) throws TimeoutException {
        Course course = courseWithApprovedQuestion("Cell Biology");
        open(robot, new Destination.Studio(course.id(), StudioTab.PUBLISH));
        Button publish = robot.lookup(".publish-version").queryButton();
        assertThat(publish.getText()).isEqualTo("Publish version 1");

        PublishSection section = (PublishSection) page().section();
        robot.interact(() -> section.notesField().setText("First edition for the spring term."));
        robot.clickOn(publish);
        waitFor(() -> !ws().courses().versions(course.id()).isEmpty());
        settle();

        CourseVersion version = ws().courses().versions(course.id()).getFirst();
        assertThat(version.number()).isEqualTo(1);
        assertThat(version.notes()).isEqualTo("First edition for the spring term.");
        waitFor(() -> texts(robot, ".version-notes").contains("First edition for the spring term."));
        assertThat(robot.lookup(".publish-version").queryButton().getText()).isEqualTo("Publish version 2");
        assertThat(section.notesField().getText()).isEmpty();
    }

    @Test
    void refusesToPublishWithoutApprovedQuestions(FxRobot robot) {
        Course course = ws().courses().create(CourseDraft.titled("Empty course"));
        open(robot, new Destination.Studio(course.id(), StudioTab.PUBLISH));

        assertThat(robot.lookup(".publish-version").queryButton().isDisabled()).isTrue();
        assertThat(texts(robot, ".publish-blocked")).containsExactly(PublishSection.NOTHING_APPROVED);
    }

    @Test
    void exportsAProtectedCourseThatAsksForItsCode(FxRobot robot) throws TimeoutException {
        Course course = courseWithApprovedQuestion("Cell Biology");
        ws().courses().publish(course.id(), "First edition.");
        ws().courses().setVisibility(course.id(), Visibility.PROTECTED);
        files.courseFile = dir.resolve("Cell Biology.learnsphere");
        open(robot, new Destination.Studio(course.id(), StudioTab.PUBLISH));

        robot.clickOn(robot.lookup(".export-course").queryButton());
        settle();
        assertThat(files.suggestedName).isEqualTo("Cell Biology.learnsphere");
        robot.clickOn(".protection-code").write("abc").push(KeyCode.ENTER);
        settle();
        assertThat(texts(robot, ".studio-dialog-error"))
                .containsExactly("Choose a code of at least 4 characters to protect this course.");
        robot.clickOn(".protection-code").write("d-2026").push(KeyCode.ENTER);
        waitFor(() -> !robot.lookup(".export-done").queryAll().isEmpty());

        PublishSection section = (PublishSection) page().section();
        assertThat(section.lastExport().protectedByCode()).isTrue();
        PackageInfo info = ws().sharing().inspect(files.courseFile);
        assertThat(info.title()).isEqualTo("Cell Biology");
        assertThat(info.protectedByCode()).isTrue();
        assertThat(info.versionNumber()).isEqualTo(1);
        assertThat(info.createdHere()).isTrue();

        robot.clickOn(robot.lookup(".export-reveal").queryButton());
        waitFor(() -> files.revealed != null);
        assertThat(files.revealed).isEqualTo(files.courseFile);
    }

    @Test
    void explainsWhyAPrivateCourseCannotBeShared(FxRobot robot) {
        Course course = courseWithApprovedQuestion("Cell Biology");
        ws().courses().publish(course.id(), null);
        open(robot, new Destination.Studio(course.id(), StudioTab.PUBLISH));

        assertThat(robot.lookup(".export-course").tryQuery()).isEmpty();
        assertThat(texts(robot, ".studio-note .label"))
                .contains("Only you can open this course. Choose who can open it to share it.");
        robot.clickOn(robot.lookup(".share-change").queryButton());
        settle();
        assertThat(page().tab()).isEqualTo(StudioTab.DETAILS);
        assertThat(window.navigator().current()).isEqualTo(new Destination.Studio(course.id(), StudioTab.DETAILS));
    }

    // -------------------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------------------

    private Course courseWithApprovedQuestion(String title) {
        Course course = ws().courses().create(CourseDraft.titled(title));
        LearningItem item = ws().items().create(course.id(), null,
                ItemContent.flashcard("What is a cell membrane?", "A lipid bilayer that surrounds the cell."));
        ws().items().approve(item.id());
        return course;
    }

    private Workspace ws() {
        return real.workspace();
    }

    private StudioPage page() {
        return (StudioPage) window.navigator().currentView();
    }

    private List<String> moduleTitles() {
        return ws().courses().modules(currentCourseId()).stream().map(CourseModule::title).toList();
    }

    private String currentCourseId() {
        return ((Destination.Studio) window.navigator().current()).courseId();
    }

    private void open(FxRobot robot, Destination.Studio destination) {
        robot.interact(() -> window.navigator().navigate(destination));
        settle();
        settle();
    }

    private static Node tabButton(FxRobot robot, StudioTab tab) {
        return robot.lookup(".tab-button").queryAll().stream().filter(n -> n.getUserData() == tab).findFirst()
                .orElseThrow();
    }

    private static void settle() {
        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(10))).isTrue();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(10))).isTrue();
        WaitForAsyncUtils.waitForFxEvents();
    }

    private static void waitFor(BooleanSupplier condition) throws TimeoutException {
        WaitForAsyncUtils.waitFor(30, TimeUnit.SECONDS, () -> {
            WaitForAsyncUtils.waitForFxEvents();
            return condition.getAsBoolean();
        });
    }

    private static List<String> texts(FxRobot robot, String query) {
        List<String> texts = new ArrayList<>();
        for (Node node : robot.lookup(query).queryAll()) {
            if (node.isVisible() && node instanceof Labeled labeled) {
                texts.add(labeled.getText());
            } else if (node.isVisible() && node instanceof TextArea area) {
                texts.add(area.getText());
            }
        }
        return texts;
    }

    /** File dialogs that answer at once with what the test set. */
    private static final class FakeFiles implements StudioFiles {

        volatile List<Path> pdfs = List.of();
        volatile Path courseFile;
        volatile String suggestedName;
        volatile Path revealed;

        @Override
        public List<Path> choosePdfs(Window owner) {
            return pdfs;
        }

        @Override
        public Path chooseCourseFile(Window owner, String suggestedName) {
            this.suggestedName = suggestedName;
            return courseFile;
        }

        @Override
        public List<Path> chooseProgressFiles(Window owner) {
            return List.of();
        }

        @Override
        public Path chooseFeedbackFile(Window owner) {
            return null;
        }

        @Override
        public void reveal(Path file) {
            revealed = file;
        }
    }
}
