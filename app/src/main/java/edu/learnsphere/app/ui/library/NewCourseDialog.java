package edu.learnsphere.app.ui.library;

import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.Workspace;
import java.util.Collection;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * "New course": a title, an optional subtitle, the author's name and a cover colour, beside a live preview
 * of the course's card. Creating the course hands it to {@code onCreated} (the Library opens it in the
 * Studio); a problem shows as one sentence under the form and keeps what was typed.
 */
public final class NewCourseDialog {

    /** Longest title the form accepts (the course service's limit). */
    public static final int MAX_TITLE = 200;

    private final Workspace workspace;
    private final Disposer disposer;
    private final Consumer<Course> onCreated;
    private final ModalSheet sheet = new ModalSheet("New course");
    private final TextField title = new TextField();
    private final TextField subtitle = new TextField();
    private final TextField author = new TextField();
    private final CoverHuePicker picker;
    private final Label error = new Label();
    private final Button create = new Button("Create course");
    private final CourseCover previewCover;
    private final Label previewTitle = new Label();
    private final Label previewSubtitle = new Label();
    private final Label previewMeta = new Label();
    private boolean creating;

    /**
     * @param workspace     the open profile
     * @param defaultAuthor the name the author field starts with (the profile's name)
     * @param usedHues      the cover hues of courses already in the library, so the suggested colour differs
     * @param disposer      the calling view's disposer: the sheet closes and its work is dropped with the view
     * @param onCreated     called on the FX thread with the new course after the sheet closes
     */
    public NewCourseDialog(Workspace workspace, String defaultAuthor, Collection<Integer> usedHues, Disposer disposer,
                           Consumer<Course> onCreated) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.disposer = Objects.requireNonNull(disposer, "disposer");
        this.onCreated = Objects.requireNonNull(onCreated, "onCreated");
        picker = new CoverHuePicker(CoverHuePicker.suggest(usedHues));
        previewCover = new CourseCover(picker.getHue(), "new-course-preview", CourseCover.Size.CARD);
        sheet.subtitle("Give it a name and a cover. You add modules and PDFs next, in the Studio.");
        sheet.setCardWidth(760);
        sheet.getStyleClass().add("new-course-sheet");

        title.setPromptText("For example, Cell Biology");
        title.setAccessibleText("Title");
        title.textProperty().addListener((obs, old, value) -> {
            if (value.length() > MAX_TITLE) {
                title.setText(value.substring(0, MAX_TITLE));
            }
        });
        subtitle.setPromptText("One line about what it covers");
        subtitle.setAccessibleText("Subtitle");
        author.setText(defaultAuthor == null ? "" : defaultAuthor);
        author.setPromptText("Your name, as learners will see it");
        author.setAccessibleText("Author");

        VBox form = new VBox(16,
                field("Title", title),
                field("Subtitle", subtitle, "Optional"),
                field("Author", author),
                field("Cover", picker));
        form.getStyleClass().add("new-course-form");
        HBox.setHgrow(form, Priority.ALWAYS);

        Node preview = preview();
        HBox columns = new HBox(32, form, preview);
        columns.setAlignment(Pos.TOP_LEFT);

        error.getStyleClass().addAll("sheet-error");
        error.setWrapText(true);
        error.setVisible(false);
        error.setManaged(false);
        sheet.body().getChildren().setAll(columns, error);

        Button cancel = new Button("Cancel");
        cancel.setCancelButton(false);
        cancel.setOnAction(event -> sheet.close());
        create.getStyleClass().add("primary");
        create.disableProperty().bind(Bindings.createBooleanBinding(() -> title.getText().isBlank(), title.textProperty()));
        create.setOnAction(event -> submit());
        sheet.setButtons(cancel, create);
        sheet.setDefaultButton(create);
        sheet.setInitialFocus(title);

        title.textProperty().addListener((obs, old, value) -> updatePreview());
        subtitle.textProperty().addListener((obs, old, value) -> updatePreview());
        author.textProperty().addListener((obs, old, value) -> updatePreview());
        picker.hueProperty().addListener((obs, old, value) -> previewCover.setHue(value.intValue()));
        updatePreview();
        disposer.onDispose(sheet::close);
    }

    /** Opens the sheet over the window of {@code anchor}. */
    public NewCourseDialog show(Node anchor) {
        sheet.show(anchor);
        return this;
    }

    public ModalSheet sheet() {
        return sheet;
    }

    public TextField titleField() {
        return title;
    }

    public TextField subtitleField() {
        return subtitle;
    }

    public TextField authorField() {
        return author;
    }

    public CoverHuePicker picker() {
        return picker;
    }

    public Button createButton() {
        return create;
    }

    /** The sentence shown under the form, or empty. */
    public String errorText() {
        return error.isVisible() ? error.getText() : "";
    }

    private void submit() {
        if (creating || title.getText().isBlank()) {
            return;
        }
        creating = true;
        create.setText("Creating…");
        showError(null);
        CourseDraft draft = new CourseDraft(title.getText().strip(), subtitle.getText().strip(), "",
                author.getText().strip(), picker.getHue());
        disposer.add(UiTasks.run(() -> workspace.courses().create(draft), course -> {
            sheet.close();
            onCreated.accept(course);
        }, message -> {
            creating = false;
            create.setText("Create course");
            showError(message);
        }));
    }

    private void showError(UserMessage message) {
        error.setText(message == null ? "" : message.text());
        error.setVisible(message != null);
        error.setManaged(message != null);
    }

    private Node preview() {
        previewMeta.getStyleClass().addAll("text-caption", "course-card-meta");
        previewMeta.setMnemonicParsing(false);
        previewTitle.getStyleClass().addAll("course-card-title");
        previewTitle.setWrapText(true);
        previewTitle.setMnemonicParsing(false);
        previewTitle.setMaxHeight(2 * 29);
        previewSubtitle.getStyleClass().addAll("course-card-subtitle");
        previewSubtitle.setMnemonicParsing(false);
        VBox text = new VBox(4, previewMeta, previewTitle, previewSubtitle);
        text.getStyleClass().add("course-card-body");
        VBox card = new VBox(previewCover, text);
        card.getStyleClass().addAll("card", "flat", "course-card", "new-course-preview");
        card.setMouseTransparent(true);
        card.setFocusTraversable(false);
        Label caption = new Label("Preview");
        caption.getStyleClass().addAll("text-overline", "new-course-preview-caption");
        VBox box = new VBox(8, caption, card);
        box.setMinWidth(280);
        box.setPrefWidth(280);
        box.setMaxWidth(280);
        return box;
    }

    private void updatePreview() {
        String name = title.getText().strip();
        previewTitle.setText(name.isEmpty() ? "Your course title" : name);
        previewTitle.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("placeholder"), name.isEmpty());
        String line = subtitle.getText().strip();
        previewSubtitle.setText(line);
        previewSubtitle.setVisible(!line.isEmpty());
        previewSubtitle.setManaged(!line.isEmpty());
        String by = author.getText().strip();
        previewMeta.setText(by.isEmpty() ? "Draft · not published" : by);
    }

    private static VBox field(String label, Node control) {
        return field(label, control, null);
    }

    private static VBox field(String label, Node control, String hint) {
        Label name = new Label(label);
        name.getStyleClass().add("field-label");
        HBox top = new HBox(8, name);
        top.setAlignment(Pos.BASELINE_LEFT);
        if (hint != null) {
            Label note = new Label(hint);
            note.getStyleClass().addAll("text-caption", "field-hint");
            top.getChildren().add(note);
        }
        if (control instanceof javafx.scene.control.Control c) {
            name.setLabelFor(c);
        }
        return new VBox(8, top, control);
    }
}
