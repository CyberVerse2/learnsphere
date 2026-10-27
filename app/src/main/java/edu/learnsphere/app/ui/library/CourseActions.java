package edu.learnsphere.app.ui.library;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.components.ConfirmDialog;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.ExportOptions;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.sharing.PackageKey;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import javafx.beans.binding.Bindings;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;

/**
 * What can be done with one course from its card or its page: open it, study, ask, start or stop studying
 * it, archive or restore a course made here, and save a shareable course as a file. Every change runs in the
 * background, confirms what happened in a toast (with Undo where it can be undone) and reports problems as
 * one sentence. The screens reload from the events the changes publish.
 */
public final class CourseActions {

    /** Where the actions are offered; the course page leaves out the ones it shows as buttons. */
    public enum Place {
        LIBRARY, COURSE_PAGE
    }

    private final AppContext context;
    private final Navigator navigator;
    private final Supplier<Node> anchor;
    private final Disposer disposer;
    private FilePicker files = FilePicker.system();

    /**
     * @param anchor   a node in the window, for toasts and dialogs
     * @param disposer the view's disposer: work still running when the view goes is dropped
     */
    public CourseActions(AppContext context, Navigator navigator, Supplier<Node> anchor, Disposer disposer) {
        this.context = Objects.requireNonNull(context, "context");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.anchor = Objects.requireNonNull(anchor, "anchor");
        this.disposer = Objects.requireNonNull(disposer, "disposer");
    }

    /** Replaces the file dialogs (tests). */
    public void setFilePicker(FilePicker picker) {
        files = Objects.requireNonNull(picker, "picker");
    }

    public FilePicker filePicker() {
        return files;
    }

    // ---- navigation --------------------------------------------------------------------------

    public void open(CourseSummary course) {
        navigator.navigate(new Destination.Course(course.id()));
    }

    public void openStudio(CourseSummary course) {
        navigator.navigate(new Destination.Studio(course.id(), StudioTab.DETAILS));
    }

    public void study(CourseSummary course) {
        navigator.navigate(new Destination.Study(SessionRequest.studyNext(course.id())));
    }

    public void ask(CourseSummary course) {
        navigator.navigate(Destination.Ask.newThread(course.id()));
    }

    /** True when there is something a study session of the course would hold. */
    public static boolean canStudy(CourseSummary course) {
        return course.itemCount() > 0;
    }

    // ---- changes -----------------------------------------------------------------------------

    /** Starts studying the course: it joins Home and Study next. */
    public void enrol(CourseSummary course) {
        change(() -> {
            workspace().courses().enrol(course.id());
            return null;
        }, "You're studying this course. It's on Home and in Study next.", null);
    }

    /** Starts studying the course and opens a session of it once it has joined Home and Study next. */
    public void enrolAndStudy(CourseSummary course) {
        if (context.workspace() == null) {
            return;
        }
        disposer.add(UiTasks.run(() -> {
            workspace().courses().enrol(course.id());
            return null;
        }, ignored -> study(course), this::error));
    }

    /** What starting to study a course the learner isn't studying is called: in menus and on its page. */
    public static String enrolLabel(CourseSummary course) {
        return course.createdHere() ? "Study this course" : "Study again";
    }

    /** Asks first, then stops studying the course; Undo starts again. */
    public void leave(CourseSummary course) {
        ConfirmDialog.of("Leave this course?",
                        "It leaves Home and Study next. Your progress is kept, so you can pick it up again from the Library.",
                        "Leave course")
                .show(anchor.get())
                .thenAccept(confirmed -> {
                    if (confirmed) {
                        change(() -> {
                            workspace().courses().leave(course.id());
                            return null;
                        }, "You left the course. Your progress is kept.", () -> enrol(course));
                    }
                });
    }

    /** Hides a course made here from the library; Undo brings it back. */
    public void archive(CourseSummary course) {
        change(() -> {
            workspace().courses().archive(course.id());
            return null;
        }, "Course archived. Find it under Creating with Show archived.", () -> unarchive(course));
    }

    /** Brings an archived course back. */
    public void unarchive(CourseSummary course) {
        change(() -> {
            workspace().courses().unarchive(course.id());
            return null;
        }, "Course restored", null);
    }

    /** True when the course can be saved as a file to share: made here, published and not private. */
    public static boolean canExport(CourseSummary course) {
        return course.createdHere() && course.versionNumber() > 0 && course.visibility() != Visibility.PRIVATE;
    }

    /**
     * Saves the latest published version as a course file. A protected course asks for the code learners will
     * enter first.
     */
    public void export(CourseSummary course) {
        if (course.visibility() == Visibility.PROTECTED) {
            askForCode(course);
        } else {
            exportTo(course, null);
        }
    }

    private void askForCode(CourseSummary course) {
        ModalSheet sheet = new ModalSheet("Protect the course with a code");
        sheet.subtitle("Learners enter this code to import the course. Send it to them separately from the file.");
        TextField code = new TextField();
        code.setPromptText("At least " + PackageKey.MIN_CODE_LENGTH + " characters");
        code.setAccessibleText("Code");
        Label label = new Label("Code");
        label.getStyleClass().add("field-label");
        label.setLabelFor(code);
        sheet.body().getChildren().setAll(new VBox(8, label, code));
        Button cancel = new Button("Cancel");
        cancel.setOnAction(event -> sheet.close());
        Button next = new Button("Choose where to save");
        next.getStyleClass().add("primary");
        next.disableProperty().bind(Bindings.createBooleanBinding(
                () -> code.getText().strip().length() < PackageKey.MIN_CODE_LENGTH, code.textProperty()));
        next.setOnAction(event -> {
            char[] secret = code.getText().strip().toCharArray();
            code.clear();
            sheet.close();
            exportTo(course, secret);
        });
        sheet.setButtons(cancel, next);
        sheet.setDefaultButton(next);
        sheet.setInitialFocus(code);
        sheet.closeWith(disposer);
        sheet.show(anchor.get());
    }

    private void exportTo(CourseSummary course, char[] code) {
        Path target = files.chooseExportTarget(anchor.get(), FilePicker.fileNameFor(course.title()));
        if (target == null) {
            if (code != null) {
                Arrays.fill(code, '\0');
            }
            return;
        }
        ExportOptions options = code == null ? ExportOptions.standard() : ExportOptions.protectedWith(code);
        Toasts.info(anchor.get(), "Saving the course…");
        change(() -> workspace().sharing().exportCourse(course.id(), target, options),
                "Course saved. Share the file with your learners.", null);
    }

    // ---- menus -------------------------------------------------------------------------------

    /** The menu of a course: on its card in the Library, or behind the More button on its page. */
    public ContextMenu menu(CourseSummary course, Place place) {
        ContextMenu menu = new ContextMenu();
        menu.getStyleClass().add("course-menu");
        if (place == Place.LIBRARY) {
            if (course.createdHere() && !course.enrolled()) {
                menu.getItems().add(item("Open in Studio", Feather.EDIT_2, () -> openStudio(course)));
                menu.getItems().add(item("Open course page", Feather.BOOK_OPEN, () -> open(course)));
            } else {
                menu.getItems().add(item("Open course", Feather.BOOK_OPEN, () -> open(course)));
            }
            MenuItem study = item("Study now", Feather.PLAY, () -> study(course));
            study.setDisable(!canStudy(course));
            menu.getItems().addAll(study, item("Ask a question", Feather.MESSAGE_CIRCLE, () -> ask(course)));
            if (course.createdHere() && course.enrolled()) {
                menu.getItems().add(item("Open in Studio", Feather.EDIT_2, () -> openStudio(course)));
            }
        } else if (course.createdHere()) {
            menu.getItems().add(item("Edit in Studio", Feather.EDIT_2, () -> openStudio(course)));
        }
        menu.getItems().add(new SeparatorMenuItem());
        if (course.enrolled()) {
            menu.getItems().add(item("Leave course…", Feather.LOG_OUT, () -> leave(course)));
        } else if (!course.isArchived()) {
            menu.getItems().add(item(enrolLabel(course), Feather.PLUS_CIRCLE, () -> enrol(course)));
        }
        if (canExport(course)) {
            menu.getItems().add(item("Save course file…", Feather.SHARE, () -> export(course)));
        }
        if (course.createdHere()) {
            menu.getItems().add(course.isArchived()
                    ? item("Restore course", Feather.ROTATE_CCW, () -> unarchive(course))
                    : item("Archive course", Feather.ARCHIVE, () -> archive(course)));
        }
        if (menu.getItems().getLast() instanceof SeparatorMenuItem) {
            menu.getItems().removeLast();
        }
        return menu;
    }

    private static MenuItem item(String label, Ikon icon, Runnable action) {
        MenuItem item = new MenuItem(label, Icons.of(icon, 15));
        item.setMnemonicParsing(false);
        item.setOnAction(event -> action.run());
        return item;
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Workspace workspace() {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            throw new IllegalStateException("No profile is open");
        }
        return workspace;
    }

    private <T> void change(Callable<T> work, String done, Runnable undo) {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        disposer.add(UiTasks.run(work, result -> {
            Node node = anchor.get();
            if (node.getScene() != null) {
                Toasts.show(node, Toasts.Kind.SUCCESS, done, undo == null ? null : "Undo", undo);
            }
        }, this::error));
    }

    private void error(UserMessage message) {
        Node node = anchor.get();
        if (node.getScene() != null) {
            Toasts.error(node, message);
        }
    }
}
