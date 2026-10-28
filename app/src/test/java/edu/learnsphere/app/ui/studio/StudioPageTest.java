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
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.ModulePlan;
import edu.learnsphere.core.service.ExportOptions;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.PackageInfo;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.ThemePreference;
import edu.learnsphere.core.service.Workspace;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import javafx.event.Event;
import javafx.event.EventType;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Labeled;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
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
        assertThat(robot.lookup(".new-course-button").queryButton().isVisible())
                .as("the empty state's New course is the only one").isFalse();

        robot.clickOn(robot.lookup(".empty-state-actions .button").queryButton());
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

    @Test
    void archivesACourseAndRestoresIt(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Old syllabus"));
        open(robot, new Destination.Studio(course.id(), StudioTab.DETAILS));

        Button archive = robot.lookup(".details-archive").queryButton();
        robot.interact(archive::requestFocus);
        settle();
        robot.clickOn(archive);
        waitFor(() -> ws().courses().summary(course.id()).isArchived());
        waitFor(() -> texts(robot, ".details-archive").contains("Restore course"));
        waitFor(() -> texts(robot, ".studio-header-meta").equals(List.of("ARCHIVED")));

        // The button became Restore course and kept the keyboard focus.
        Button restore = robot.lookup(".details-archive").queryButton();
        waitFor(restore::isFocused);
        robot.push(KeyCode.SPACE);
        waitFor(() -> !ws().courses().summary(course.id()).isArchived());
        waitFor(() -> texts(robot, ".details-archive").contains("Archive"));
    }

    @Test
    void picksWhoCanOpenTheCourseWithTheMouseAndTheArrowKeys(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Genetics"));
        open(robot, new Destination.Studio(course.id(), StudioTab.DETAILS));
        DetailsSection details = (DetailsSection) page().section();
        VisibilityPicker picker = details.visibilityPicker();

        for (Visibility visibility : Visibility.values()) {
            assertThat(picker.option(visibility, true).getHeight()).as("the %s row is one compact row", visibility)
                    .isBetween(56.0, 88.0);
        }
        Node shareable = picker.option(Visibility.SHAREABLE, true);
        robot.interact(shareable::requestFocus);
        settle();
        robot.clickOn(shareable);
        assertThat(picker.getValue()).isEqualTo(Visibility.SHAREABLE);
        robot.push(KeyCode.DOWN);
        assertThat(picker.getValue()).isEqualTo(Visibility.PROTECTED);
        assertThat(picker.option(Visibility.PROTECTED, true).isFocused()).isTrue();
        assertThat(picker.option(Visibility.PROTECTED, true).getPseudoClassStates())
                .anyMatch(state -> state.getPseudoClassName().equals("selected"));
        assertThat(details.hasUnsavedChanges()).isTrue();
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

        // Dragging the first module by its handle and dropping it on the lower half of the last one.
        String alkanes = ws().courses().modules(course.id()).getFirst().id();
        robot.interact(() -> modules.startDrag(alkanes));
        HBox last = (HBox) robot.lookup(".module-row").nth(2).query();
        robot.interact(() -> {
            Event.fireEvent(last, drag(DragEvent.DRAG_OVER, last, last.getHeight() - 4));
            assertThat(last.getStyleClass()).contains("drop-below");
            Event.fireEvent(last, drag(DragEvent.DRAG_DROPPED, last, last.getHeight() - 4));
        });
        waitFor(() -> moduleTitles().equals(List.of("Alkynes", "Alkenes", "Alkanes")));
        settle();
        assertThat(modules.titles()).containsExactly("Alkynes", "Alkenes", "Alkanes");
        assertThat(ws().courses().modules(course.id()).stream().map(CourseModule::position).toList())
                .as("positions stay dense").containsExactly(0, 1, 2);

        // Dropping the last module on the upper half of the first puts it first.
        String alkanesNow = ws().courses().modules(course.id()).get(2).id();
        robot.interact(() -> modules.startDrag(alkanesNow));
        HBox first = (HBox) robot.lookup(".module-row").nth(0).query();
        robot.interact(() -> Event.fireEvent(first, drag(DragEvent.DRAG_DROPPED, first, 4)));
        waitFor(() -> moduleTitles().equals(List.of("Alkanes", "Alkynes", "Alkenes")));
    }

    @Test
    void keepsTheLastOrderWhenModulesAreMovedQuickly(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Organic Chemistry"));
        for (String title : List.of("One", "Two", "Three", "Four")) {
            ws().courses().addModule(course.id(), title);
        }
        open(robot, new Destination.Studio(course.id(), StudioTab.MODULES));
        ModulesSection modules = (ModulesSection) page().section();

        robot.interact(() -> robot.lookup(".module-row").nth(0).query().requestFocus());
        for (int i = 0; i < 3; i++) {
            robot.push(KeyCode.ALT, KeyCode.DOWN);
        }
        waitFor(() -> !modules.savingOrder());
        settle();
        assertThat(modules.titles()).containsExactly("Two", "Three", "Four", "One");
        assertThat(moduleTitles()).containsExactly("Two", "Three", "Four", "One");
        assertThat(ws().courses().modules(course.id()).stream().map(CourseModule::position).toList())
                .containsExactly(0, 1, 2, 3);
    }

    @Test
    void doesNotMoveAModuleWhileOneIsBeingRenamed(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Organic Chemistry"));
        ws().courses().addModule(course.id(), "Alkanes");
        ws().courses().addModule(course.id(), "Alkenes");
        open(robot, new Destination.Studio(course.id(), StudioTab.MODULES));
        ModulesSection modules = (ModulesSection) page().section();

        robot.clickOn(robot.lookup(".module-edit").queryButton());
        settle();
        robot.interact(() -> robot.lookup(".module-title-field").queryAs(TextField.class).setText("Alkanes and cycloalkanes"));
        robot.interact(() -> modules.move(0, 1));
        robot.interact(() -> robot.lookup(".module-row").nth(1).query().requestFocus());
        robot.push(KeyCode.ALT, KeyCode.UP);
        settle();
        assertThat(moduleTitles()).containsExactly("Alkanes", "Alkenes");

        robot.clickOn(robot.lookup(".module-save").queryButton());
        waitFor(() -> moduleTitles().equals(List.of("Alkanes and cycloalkanes", "Alkenes")));
        settle();
        assertThat(modules.titles()).containsExactly("Alkanes and cycloalkanes", "Alkenes");
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

    @Test
    void suggestsModulesFromThePdfsAndCreatesThemAfterReview(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Biology"));
        List<String> documents = new ArrayList<>();
        for (String title : List.of("Cells", "Plants", "Fungi")) {
            DocumentSummary pdf = ws().documents().importPdf(course.id(), null,
                    real.textPdf(title.toLowerCase(Locale.ROOT) + ".pdf", title, 1));
            real.awaitSettled(pdf.id());
            documents.add(pdf.id());
        }
        open(robot, new Destination.Studio(course.id(), StudioTab.MODULES));
        ModulesSection modules = (ModulesSection) page().section();

        robot.clickOn(robot.lookup(".modules-empty .empty-state-actions .button").queryButton());
        waitFor(() -> modules.plan() != null);
        settle();
        assertThat(texts(robot, ".studio-panel-title")).contains("Suggested modules");
        assertThat(modules.plan().units()).extracting(ModulePlan.Unit::title)
                .containsExactly("Cells part 1", "Plants part 1", "Fungi part 1");
        assertThat(texts(robot, ".module-row-covers")).containsExactly("Covers 1. Cells part 1",
                "Covers 1. Plants part 1", "Covers 1. Fungi part 1");

        robot.clickOn(robot.lookup(".module-remove").nth(1).queryButton());
        settle();
        robot.interact(() -> robot.lookup(".module-title-field").queryAs(TextField.class).setText(" "));
        robot.clickOn(robot.lookup(".module-create").queryButton());
        settle();
        assertThat(texts(robot, ".field-error")).contains(ModulesSection.TITLES_NEEDED);
        assertThat(ws().courses().modules(course.id())).isEmpty();

        robot.interact(() -> robot.lookup(".module-title-field").queryAs(TextField.class).setText("Living things"));
        robot.clickOn(robot.lookup(".module-create").queryButton());
        waitFor(() -> moduleTitles().equals(List.of("Living things", "Fungi part 1")));
        settle();
        List<CourseModule> created = ws().courses().modules(course.id());
        assertThat(documents.stream().map(id -> ws().documents().get(id).moduleId()))
                .containsExactly(created.get(0).id(), created.get(0).id(), created.get(1).id());
        assertThat(modules.plan()).isNull();
        assertThat(modules.titles()).containsExactly("Living things", "Fungi part 1");
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
        assertThat(texts(robot, ".document-reason"))
                .containsExactly("This PDF is password-protected. Remove the password and import it again.");
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
    void showsLiveProgressOnAPdfWhileItIsRead(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Organic Chemistry"));
        Path big = real.textPdf("reactions.pdf", "Reactions", 120);
        open(robot, new Destination.Studio(course.id(), StudioTab.DOCUMENTS));
        DocumentsSection documents = (DocumentsSection) page().section();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        Runnable watch = () -> robot.lookup(".document-pill .status-pill-label").queryAll()
                .forEach(node -> seen.add(((Labeled) node).getText()));
        robot.interact(() -> window.getScene().addPostLayoutPulseListener(watch));
        try {
            robot.interact(() -> documents.importPdfs(List.of(big)));
            waitFor(() -> texts(robot, ".document-pill .status-pill-label").contains("Ready"));
        } finally {
            robot.interact(() -> window.getScene().removePostLayoutPulseListener(watch));
        }
        assertThat(seen).anyMatch(text -> text.matches("Reading your PDF… page \\d+ of 120"));
        assertThat(texts(robot, ".document-meta")).singleElement().asString().contains("120 pages");
    }

    @Test
    void asksBeforeLeavingAScannedPageWithUnsavedText(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Organic Chemistry"));
        DocumentSummary scanned = ws().documents().importPdf(course.id(), null, real.scannedPdf("handout.pdf", 3));
        assertThat(real.awaitSettled(scanned.id()).status()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        String readText = ws().documents().page(scanned.id(), 1).text();
        open(robot, new Destination.Studio(course.id(), StudioTab.PAGES));
        ScannedPagesSection pages = (ScannedPagesSection) page().section();
        waitFor(() -> pages.pageOnScreen() == 1 && !pages.textArea().isDisabled());

        robot.interact(() -> pages.textArea().setText("Corrected text of page one."));
        robot.clickOn(pageChip(robot, 2));
        settle();
        assertThat(texts(robot, ".modal-title")).containsExactly("Leave page 1 without saving?");
        robot.clickOn(robot.lookup(".modal-cancel").queryButton());
        settle();
        assertThat(pages.pageOnScreen()).isEqualTo(1);
        assertThat(pages.textArea().getText()).isEqualTo("Corrected text of page one.");
        assertThat(pageChip(robot, 1).isSelected()).isTrue();

        robot.clickOn(robot.lookup(".icon-button").queryAll().stream()
                .filter(n -> "Next page".equals(n.getAccessibleText())).findFirst().orElseThrow());
        settle();
        assertThat(texts(robot, ".modal-title")).containsExactly("Leave page 1 without saving?");
        robot.clickOn(robot.lookup(".modal-confirm").queryButton());
        waitFor(() -> pages.pageOnScreen() == 2 && !pages.textArea().isDisabled());
        assertThat(ws().documents().page(scanned.id(), 1).text()).isEqualTo(readText);
        assertThat(ws().documents().page(scanned.id(), 1).reviewed()).isFalse();

        // Without changes, moving between pages asks nothing.
        robot.clickOn(pageChip(robot, 3));
        waitFor(() -> pages.pageOnScreen() == 3 && !pages.textArea().isDisabled());
        assertThat(robot.lookup(".modal-title").queryAll()).isEmpty();
    }

    @Test
    void editsAScannedPageThenApprovesAllAndThePdfBecomesReady(FxRobot robot) throws TimeoutException {
        Course course = ws().courses().create(CourseDraft.titled("Cell Biology"));
        CourseModule cells = ws().courses().addModule(course.id(), "Cells");
        DocumentSummary scanned = ws().documents().importPdf(course.id(), cells.id(), real.scannedPdf("scanned.pdf", 3));
        assertThat(real.awaitSettled(scanned.id()).status()).isEqualTo(DocumentStatus.NEEDS_REVIEW);
        open(robot, new Destination.Studio(course.id(), StudioTab.PAGES));
        ScannedPagesSection pages = (ScannedPagesSection) page().section();
        waitFor(() -> pages.pageOnScreen() == 1 && !pages.textArea().isDisabled());

        robot.clickOn(pageChip(robot, 2));
        waitFor(() -> pages.pageOnScreen() == 2 && !pages.textArea().isDisabled());
        robot.interact(() -> pages.textArea().setText("# Mitochondria\n\nThe mitochondrion makes most of the cell's ATP."));
        robot.clickOn(robot.lookup(".approve-all").queryButton());
        settle();
        assertThat(texts(robot, ".modal-title")).containsExactly("Approve all 3 pages?");
        robot.clickOn(robot.lookup(".modal-confirm").queryButton());
        waitFor(() -> texts(robot, ".empty-state-title").contains("Every page is checked"));

        RealWorkspaces.await(() -> ws().documents().get(scanned.id()).status() == DocumentStatus.READY,
                Duration.ofSeconds(30), "the import to continue");
        assertThat(ws().documents().page(scanned.id(), 2).text())
                .isEqualTo("# Mitochondria\n\nThe mitochondrion makes most of the cell's ATP.");
        assertThat(ws().documents().pagesNeedingReview(scanned.id())).isEmpty();
        waitFor(() -> texts(robot, ".finished-pill .status-pill-label").contains("Ready"));
        waitFor(() -> texts(robot, ".empty-state-message").contains("“scanned” is ready, with the text you approved."));
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
        Button again = robot.lookup(".publish-version").queryButton();
        assertThat(again.getText()).isEqualTo("Publish version 2");
        assertThat(again.getStyleClass()).as("nothing new to publish").doesNotContain("primary");
        assertThat(texts(robot, ".publish-unchanged"))
                .containsExactly("Publish again once you've approved or changed questions.");
        assertThat(texts(robot, ".studio-panel-subtitle"))
                .contains("Learners have version 1, with every question you've approved.");
        assertThat(section.notesField().getText()).isEmpty();

        // Approving another question makes a new version worth publishing.
        LearningItem second = ws().items().create(course.id(), null,
                ItemContent.flashcard("What does the nucleus hold?", "The cell's DNA."));
        ws().items().approve(second.id());
        waitFor(() -> robot.lookup(".publish-version").queryButton().getStyleClass().contains("primary"));
        assertThat(robot.lookup(".publish-unchanged").queryAll()).isEmpty();
    }

    @Test
    void countsOnlyReadyPdfsInWhatLearnersGet(FxRobot robot) throws TimeoutException {
        Course course = courseWithApprovedQuestion("Cell Biology");
        DocumentSummary ready = ws().documents().importPdf(course.id(), null, real.textPdf("cells.pdf", "Cells", 2));
        DocumentSummary locked = ws().documents().importPdf(course.id(), null,
                real.lockedPdf(real.textPdf("paper.pdf", "Past paper", 1), "locked.pdf", "secret"));
        real.awaitSettled(ready.id());
        real.awaitSettled(locked.id());
        open(robot, new Destination.Studio(course.id(), StudioTab.PUBLISH));

        assertThat(texts(robot, ".learners-get .label")).contains(
                "Your PDF, so they can ask about it and see where each answer comes from",
                "1 PDF isn't included until it's ready");
    }

    @Test
    void addsLearnersProgressAndFeedbackFiles(FxRobot robot) throws TimeoutException {
        Course course = courseWithApprovedQuestion("Cell Biology");
        ws().courses().setVisibility(course.id(), Visibility.SHAREABLE);
        ws().courses().publish(course.id(), "First edition.");
        Path pkg = dir.resolve("Cell Biology.learnsphere");
        ws().sharing().exportCourse(course.id(), pkg, ExportOptions.standard());
        Path progress = dir.resolve("ana.lsprogress");
        Path feedback = dir.resolve("ana.lsfeedback");
        learnerStudiesAndFlags(pkg, progress, feedback);
        open(robot, new Destination.Studio(course.id(), StudioTab.PUBLISH));
        assertThat(texts(robot, ".field-hint")).contains(
                "Add progress files from your learners to see how the class is doing.",
                "No open flags. Add a feedback file from a learner to see the questions they flagged.");

        files.progress = List.of(progress);
        robot.clickOn(robot.lookup(".add-progress").queryButton());
        waitFor(() -> texts(robot, ".insight-name").contains("Ana"));
        assertThat(texts(robot, ".insight-accuracy")).contains("100%");

        files.feedback = feedback;
        robot.clickOn(robot.lookup(".add-feedback").queryButton());
        waitFor(() -> texts(robot, ".flag-prompt").contains("What is a cell membrane?"));
        assertThat(texts(robot, ".flag-note")).containsExactly("“The answer should mention proteins too.”");
        assertThat(page().tabCount(StudioTab.PUBLISH)).isEqualTo(1);

        robot.clickOn(robot.lookup(".flag-resolve").queryButton());
        waitFor(() -> robot.lookup(".flag-row").queryAll().isEmpty());
        assertThat(ws().items().flags(course.id(), false)).isEmpty();
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

    /** A learner, in a profile of their own, imports the course, studies it, flags its question and writes both files. */
    private void learnerStudiesAndFlags(Path pkg, Path progress, Path feedback) {
        try (RealWorkspaces learner = RealWorkspaces.open(dir.resolve("learner"))) {
            Workspace lw = learner.workspace();
            JobUpdate job = lw.sharing().importCourse(pkg, null);
            RealWorkspaces.await(() -> lw.sharing().importResult(job.jobId()).isPresent(), Duration.ofSeconds(30),
                    "the course to import");
            String courseId = lw.sharing().importResult(job.jobId()).orElseThrow().courseId();
            StudySession session = lw.practice().start(SessionRequest.studyNext(courseId));
            String itemId = session.items().getFirst().itemId();
            lw.practice().submit(session.id(), Response.flashcard(itemId, Rating.GOOD, 4_000));
            lw.practice().finish(session.id());
            lw.items().addFlag(itemId, FlagReason.WRONG_ANSWER, "The answer should mention proteins too.");
            lw.sharing().exportProgress(courseId, "Ana", progress);
            lw.sharing().exportFeedback(courseId, feedback);
        }
    }

    /** A drag event at {@code y} within {@code row}, as the mouse would deliver it during a drag. */
    private static DragEvent drag(EventType<DragEvent> type, Node row, double y) {
        Point2D scene = row.localToScene(20, y);
        Point2D screen = row.localToScreen(20, y);
        return new DragEvent(row, row, type, null, scene.getX(), scene.getY(),
                screen == null ? scene.getX() : screen.getX(), screen == null ? scene.getY() : screen.getY(),
                TransferMode.MOVE, row, row, null);
    }

    private static ToggleButton pageChip(FxRobot robot, int number) {
        return robot.lookup(".page-chip").queryAll().stream().filter(n -> Objects.equals(n.getUserData(), number))
                .map(ToggleButton.class::cast).findFirst().orElseThrow();
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
        volatile List<Path> progress = List.of();
        volatile Path feedback;
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
            return progress;
        }

        @Override
        public Path chooseFeedbackFile(Window owner) {
            return feedback;
        }

        @Override
        public void reveal(Path file) {
            revealed = file;
        }
    }
}
