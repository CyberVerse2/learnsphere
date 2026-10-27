package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.Workspace;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;

/**
 * Asks for a new course's title and creates it as a private draft authored by the open profile. Any screen
 * with a "New course" action can open it.
 *
 * <pre>{@code
 * NewCourseDialog.show(root, workspace).thenAccept(course -> {
 *     if (course != null) navigator.navigate(new Destination.Studio(course.id(), StudioTab.MODULES));
 * });
 * }</pre>
 */
public final class NewCourseDialog {

    /** The sentence shown when the title is left empty. */
    static final String TITLE_NEEDED = "Enter a title for your course.";

    private NewCourseDialog() {
    }

    /**
     * Opens the dialog in the window of {@code anchor}. Completes on the FX thread with the created course, or
     * with null when the creator cancels.
     */
    public static CompletableFuture<Course> show(Node anchor, Workspace workspace) {
        Objects.requireNonNull(workspace, "workspace");
        TextField title = new TextField();
        title.setPromptText("For example, Cell Biology");
        title.getStyleClass().add("new-course-title");
        title.setAccessibleText("Course title");
        Label label = new Label("Course title");
        label.getStyleClass().add("field-label");
        label.setLabelFor(title);
        VBox field = new VBox(6, label, title);

        CompletableFuture<Course> created = new CompletableFuture<>();
        StudioDialog dialog = StudioDialog.of("New course")
                .message("Give it a name. You can add a description, pick a cover and choose who can open it later.")
                .content(field)
                .confirm("Create course")
                .focus(title)
                .onConfirm(d -> {
                    String text = title.getText() == null ? "" : title.getText().strip();
                    if (text.isEmpty()) {
                        title.getStyleClass().add("invalid");
                        d.showError(TITLE_NEEDED);
                        title.requestFocus();
                        return;
                    }
                    d.clearError();
                    d.setBusy(true);
                    String author = workspace.profile().displayName();
                    UiTasks.run(() -> workspace.courses().create(new CourseDraft(text, null, null, author, null)),
                            course -> {
                                created.complete(course);
                                d.close(true);
                            },
                            d::showError);
                });
        title.textProperty().addListener((obs, old, text) -> {
            title.getStyleClass().remove("invalid");
            dialog.clearError();
        });
        dialog.show(anchor).thenAccept(confirmed -> {
            if (!confirmed) {
                created.complete(null);
            }
        });
        return created;
    }
}
