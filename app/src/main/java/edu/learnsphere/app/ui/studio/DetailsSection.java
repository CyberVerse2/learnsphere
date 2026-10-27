package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.components.CourseCard;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.CourseDetail;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.CourseUpdate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The Details tab: the course's title, subtitle, description and author, its cover colour and who can open
 * it, beside a live preview of the card learners see. Changes are kept until Save changes (or Command/Ctrl+S);
 * the title is required. Below the form, the course can be archived (and restored) or deleted after typing a
 * confirmation word.
 */
final class DetailsSection extends StudioSection {

    /** The cover colours offered as swatches, spread around the colour wheel. */
    static final int[] COVER_HUES = {210, 235, 262, 300, 340, 0, 28, 45, 95, 120, 150, 190};
    /** The word typed to confirm deleting a course. */
    static final String DELETE_WORD = "delete";
    static final String TITLE_NEEDED = NewCourseDialog.TITLE_NEEDED;

    private static final int MAX_TITLE = 200;
    private static final int MAX_SUBTITLE = 300;
    private static final int MAX_DESCRIPTION = 5_000;
    private static final int MAX_AUTHOR = 120;
    private static final double ASIDE_WIDTH = 320;

    private final VBox root = new VBox(24);
    private final TextField title = new TextField();
    private final TextField subtitle = new TextField();
    private final TextArea description = new TextArea();
    private final TextField author = new TextField();
    private final Label titleError = StudioUi.fieldError();
    private final VisibilityPicker visibility = new VisibilityPicker();
    private final Slider hue = new Slider(0, 359, 210);
    private final ToggleGroup swatches = new ToggleGroup();
    private final List<ToggleButton> swatchButtons = new ArrayList<>();
    private final VBox preview = new VBox();
    private final Label saveState = new Label();
    private final Button save = new Button("Save changes");
    private final Button discard = new Button("Discard");
    private final VBox lifecycle = new VBox();
    private CourseDetail loaded;
    private boolean filling;
    private boolean saving;

    DetailsSection(StudioHost host) {
        super(host);
        root.getStyleClass().add("studio-details");
        root.getChildren().setAll(loading());
        StudioUi.limit(title, MAX_TITLE);
        StudioUi.limit(subtitle, MAX_SUBTITLE);
        StudioUi.limit(description, MAX_DESCRIPTION);
        StudioUi.limit(author, MAX_AUTHOR);
        title.getStyleClass().add("details-title");
        subtitle.getStyleClass().add("details-subtitle");
        description.getStyleClass().add("details-description");
        author.getStyleClass().add("details-author");
        subtitle.setPromptText("For example, A first course for chemistry students");
        description.setPromptText("What learners will cover and who the course is for.");
        description.setWrapText(true);
        description.setPrefRowCount(5);
        author.setPromptText("Your name, as learners will see it");
        hue.getStyleClass().add("hue-slider");
        hue.setAccessibleText("Cover colour");
        hue.setBlockIncrement(5);
        save.getStyleClass().addAll("primary", "details-save");
        save.setOnAction(event -> save());
        discard.getStyleClass().addAll("quiet", "neutral", "details-discard");
        discard.setOnAction(event -> fill(loaded));
        saveState.getStyleClass().add("save-state");
    }

    @Override
    Region root() {
        return root;
    }

    @Override
    void onShown() {
        load();
        on(CourseChanged.class, event -> {
            if (courseId().equals(event.courseId()) && event.type() == ChangeType.UPDATED && !saving
                    && !hasUnsavedChanges()) {
                load();
            }
        });
        Shortcuts.install(root, new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN), () -> {
            if (loaded != null && hasUnsavedChanges()) {
                save();
            }
        }, disposer);
    }

    private void load() {
        run(() -> workspace().courses().get(courseId()), this::render, message ->
                root.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.SEARCH,
                        "The details couldn't load", message.text()).primary("Try again", this::load)));
    }

    @Override
    boolean hasUnsavedChanges() {
        if (loaded == null) {
            return false;
        }
        Course course = loaded.course();
        return !StudioUi.text(title).equals(course.title())
                || !StudioUi.text(subtitle).equals(course.subtitle())
                || !StudioUi.text(description).equals(course.description())
                || !StudioUi.text(author).equals(course.authorName())
                || hueValue() != course.coverHue()
                || visibility.getValue() != course.visibility();
    }

    @Override
    String unsavedChangesText() {
        return "Your changes to the course details aren't saved.";
    }

    // -------------------------------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------------------------------

    private void render(CourseDetail detail) {
        boolean first = loaded == null;
        loaded = detail;
        if (first) {
            build();
            title.textProperty().addListener((obs, old, text) -> {
                title.getStyleClass().remove("invalid");
                StudioUi.showFieldError(titleError, null);
                changed();
            });
            subtitle.textProperty().addListener((obs, old, text) -> changed());
            description.textProperty().addListener((obs, old, text) -> changed());
            author.textProperty().addListener((obs, old, text) -> changed());
            visibility.valueProperty().addListener((obs, old, value) -> changed());
            hue.valueProperty().addListener((obs, old, value) -> {
                if (!filling) {
                    selectSwatch(hueValue());
                }
                changed();
            });
        }
        fill(detail);
        renderLifecycle(detail.course());
    }

    private void build() {
        VBox about = StudioUi.panel("About this course", "Learners see this on the course card and the course page.",
                StudioUi.field("Title", new VBox(6, title, titleError), null),
                StudioUi.field("Subtitle", subtitle, "One line under the title."),
                StudioUi.field("Description", description, null),
                StudioUi.field("Author", author, "Leave it empty to share the course without a name."));
        VBox who = StudioUi.panel("Who can open it",
                "You can change this at any time. Learners who already have the course keep it.", visibility);

        Region spacer = StudioUi.grow();
        HBox saveBar = new HBox(12, saveState, spacer, discard, save);
        saveBar.setAlignment(Pos.CENTER_LEFT);
        saveBar.getStyleClass().add("save-bar");

        VBox form = new VBox(24, about, who, saveBar);
        form.getStyleClass().add("details-form");
        HBox.setHgrow(form, Priority.ALWAYS);
        form.setMinWidth(0);

        VBox aside = new VBox(24, previewFrame(), coverPicker());
        aside.getStyleClass().add("details-aside");
        aside.setMinWidth(ASIDE_WIDTH);
        aside.setPrefWidth(ASIDE_WIDTH);
        aside.setMaxWidth(ASIDE_WIDTH);

        HBox columns = new HBox(32, form, aside);
        root.getChildren().setAll(columns, lifecycle);
    }

    private Node previewFrame() {
        Label label = new Label("How learners see it");
        label.getStyleClass().addAll("text-overline", "preview-label");
        preview.getStyleClass().add("details-preview");
        VBox frame = new VBox(12, label, preview);
        frame.getStyleClass().add("preview-frame");
        return frame;
    }

    private Node coverPicker() {
        TilePane grid = new TilePane();
        grid.getStyleClass().add("hue-swatches");
        grid.setPrefColumns(6);
        grid.setAccessibleRole(AccessibleRole.LIST_VIEW);
        grid.setAccessibleText("Cover colours");
        for (int i = 0; i < COVER_HUES.length; i++) {
            int value = COVER_HUES[i];
            ToggleButton swatch = new ToggleButton();
            swatch.getStyleClass().setAll("hue-swatch");
            swatch.setGraphic(new CourseCover(value, courseId(), CourseCover.Size.SMALL));
            swatch.setUserData(value);
            swatch.setToggleGroup(swatches);
            swatch.setAccessibleRole(AccessibleRole.RADIO_BUTTON);
            swatch.setAccessibleText("Cover colour " + (i + 1) + " of " + COVER_HUES.length);
            swatch.setOnAction(event -> {
                if (swatch.isSelected()) {
                    hue.setValue(value);
                } else {
                    swatch.setSelected(true);
                }
            });
            swatchButtons.add(swatch);
            grid.getChildren().add(swatch);
        }
        Label fine = new Label("Fine-tune");
        fine.getStyleClass().add("field-hint");
        fine.setLabelFor(hue);
        fine.setMinWidth(Region.USE_PREF_SIZE);
        HBox.setHgrow(hue, Priority.ALWAYS);
        hue.setMaxWidth(Double.MAX_VALUE);
        HBox slider = new HBox(12, fine, hue);
        slider.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(12, StudioUi.heading("Cover", "Every course gets its own cover art. Pick its colour."),
                grid, slider);
        box.getStyleClass().add("cover-picker");
        return box;
    }

    private void renderLifecycle(Course course) {
        boolean archived = course.status() == CourseStatus.ARCHIVED;
        Button archive = new Button(archived ? "Restore course" : "Archive",
                Icons.of(archived ? Feather.ROTATE_CCW : Feather.ARCHIVE, 14));
        archive.getStyleClass().add("details-archive");
        archive.setMinWidth(Region.USE_PREF_SIZE);
        archive.setOnAction(event -> {
            archive.setDisable(true);
            run(() -> {
                if (archived) {
                    workspace().courses().unarchive(courseId());
                } else {
                    workspace().courses().archive(courseId());
                }
                return workspace().courses().get(courseId());
            }, detail -> {
                loaded = detail;
                renderLifecycle(detail.course());
                success(archived ? "Course restored" : "Course archived");
            }, message -> {
                archive.setDisable(false);
                error(message);
            });
        });
        HBox archiveRow = lifecycleRow(archived ? "This course is archived" : "Archive this course",
                archived ? "It's kept out of your Studio list. Restore it to keep working on it."
                        : "Keep it out of your Studio list without deleting anything. You can restore it later.",
                archive);

        Button delete = new Button("Delete course…");
        delete.getStyleClass().addAll("quiet", "danger-text", "details-delete");
        delete.setMinWidth(Region.USE_PREF_SIZE);
        delete.setOnAction(event -> confirmDelete());
        HBox deleteRow = lifecycleRow("Delete this course",
                "Removes the course, its PDFs and its questions from this computer. Copies you've shared stay with learners.",
                delete);
        lifecycle.getChildren().setAll(StudioUi.panel(null, null, archiveRow, StudioUi.divider(), deleteRow));
        lifecycle.getChildren().getFirst().getStyleClass().add("tight");
    }

    private static HBox lifecycleRow(String title, String detail, Button action) {
        Label name = new Label(title);
        name.getStyleClass().addAll("danger-row-title", "medium");
        Label line = new Label(detail);
        line.getStyleClass().add("field-hint");
        line.setWrapText(true);
        VBox text = new VBox(2, name, line);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(16, text, action);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("danger-row");
        return row;
    }

    // -------------------------------------------------------------------------------------------------
    // Editing
    // -------------------------------------------------------------------------------------------------

    private void fill(CourseDetail detail) {
        if (detail == null) {
            return;
        }
        Course course = detail.course();
        filling = true;
        try {
            title.setText(course.title());
            subtitle.setText(course.subtitle());
            description.setText(course.description());
            author.setText(course.authorName());
            visibility.setValue(course.visibility());
            hue.setValue(course.coverHue());
            selectSwatch(course.coverHue());
        } finally {
            filling = false;
        }
        title.getStyleClass().remove("invalid");
        StudioUi.showFieldError(titleError, null);
        changed();
    }

    private void changed() {
        if (loaded == null || filling) {
            return;
        }
        boolean dirty = hasUnsavedChanges();
        save.setDisable(!dirty || saving);
        discard.setVisible(dirty && !saving);
        discard.setManaged(dirty && !saving);
        saveState.getStyleClass().remove("unsaved");
        if (saving) {
            saveState.setText("Saving…");
        } else if (dirty) {
            saveState.setText("Unsaved changes");
            saveState.getStyleClass().add("unsaved");
        } else {
            saveState.setText("Edited " + Formats.ago(loaded.course().updatedAt(), host.context().clock()));
        }
        renderPreview();
    }

    private void renderPreview() {
        CourseSummary s = loaded.summary();
        String shownTitle = StudioUi.text(title).isEmpty() ? "Untitled course" : StudioUi.text(title);
        CourseSummary learner = new CourseSummary(s.id(), shownTitle, StudioUi.text(subtitle), StudioUi.text(author),
                hueValue(), s.origin(), s.status(), visibility.getValue(), Math.max(1, s.versionNumber()), false, true,
                s.moduleCount(), s.documentCount(), 0, 0, s.itemCount(), 0, s.itemCount(), 0, 0, 0, null,
                s.updatedAt());
        CourseCard card = new CourseCard(learner, CourseCard.Mode.STUDYING);
        card.setFocusTraversable(false);
        card.setMouseTransparent(true);
        card.setMaxWidth(Double.MAX_VALUE);
        preview.getChildren().setAll(card);
    }

    private int hueValue() {
        return (int) Math.round(hue.getValue()) % 360;
    }

    private void selectSwatch(int value) {
        for (ToggleButton swatch : swatchButtons) {
            boolean match = (int) swatch.getUserData() == value;
            if (swatch.isSelected() != match) {
                swatch.setSelected(match);
            }
        }
    }

    /** Saves the form; a missing title is shown under the field instead. */
    void save() {
        if (loaded == null || saving) {
            return;
        }
        String newTitle = StudioUi.text(title);
        if (newTitle.isEmpty()) {
            title.getStyleClass().add("invalid");
            StudioUi.showFieldError(titleError, TITLE_NEEDED);
            title.requestFocus();
            return;
        }
        Course before = loaded.course();
        CourseUpdate update = new CourseUpdate(newTitle, StudioUi.text(subtitle), StudioUi.text(description),
                StudioUi.text(author));
        int newHue = hueValue();
        Visibility newVisibility = visibility.getValue();
        saving = true;
        changed();
        run(() -> {
            var courses = workspace().courses();
            courses.update(courseId(), update);
            if (newHue != before.coverHue()) {
                courses.setCoverHue(courseId(), newHue);
            }
            if (newVisibility != before.visibility()) {
                courses.setVisibility(courseId(), newVisibility);
            }
            return courses.get(courseId());
        }, detail -> {
            saving = false;
            render(detail);
            success("Changes saved");
        }, message -> {
            saving = false;
            changed();
            error(message);
        });
    }

    private void confirmDelete() {
        Course course = loaded.course();
        CourseSummary summary = loaded.summary();
        TextField word = new TextField();
        word.getStyleClass().add("delete-confirm-field");
        word.setAccessibleText("Type " + DELETE_WORD + " to confirm");
        Label prompt = new Label("Type " + DELETE_WORD + " to confirm");
        prompt.getStyleClass().add("confirm-word");
        prompt.setLabelFor(word);
        VBox content = new VBox(8, prompt, word);
        List<String> parts = new ArrayList<>();
        parts.add(Formats.count(summary.documentCount(), "PDF", "PDFs"));
        parts.add(Formats.count(summary.itemCount() + summary.draftItemCount(), "question", "questions"));
        StudioDialog dialog = StudioDialog.of("Delete “" + course.title() + "”?")
                .message("This removes the course with its " + String.join(" and ", parts)
                        + " from this computer. It can't be undone.")
                .content(content)
                .confirm("Delete course")
                .danger()
                .focus(word)
                .onConfirm(d -> {
                    d.setBusy(true);
                    run(() -> {
                        workspace().courses().delete(courseId());
                        return null;
                    }, ignored -> {
                        d.close(true);
                        Toasts.success(root, "Deleted “" + course.title() + "”");
                        host.navigator().navigate(Destination.Studio.courses());
                    }, d::showError);
                });
        word.textProperty().addListener((obs, old, text) -> {
            if (dialog.confirmButton() != null) {
                dialog.confirmButton().setDisable(!matches(text));
            }
        });
        dialog.show(root);
        if (dialog.confirmButton() != null) {
            dialog.confirmButton().setDisable(true);
        }
    }

    /** True when {@code typed} is the confirmation word, ignoring case and surrounding spaces. */
    static boolean matches(String typed) {
        return typed != null && typed.strip().toLowerCase(Locale.ROOT).equals(DELETE_WORD);
    }

    private static Node loading() {
        VBox form = new VBox(20, Skeleton.line(160), Skeleton.block(-1, 40), Skeleton.block(-1, 40),
                Skeleton.block(-1, 120), Skeleton.block(-1, 40));
        form.getStyleClass().add("studio-panel");
        HBox.setHgrow(form, Priority.ALWAYS);
        VBox aside = new VBox(16, Skeleton.block(ASIDE_WIDTH, 300), Skeleton.block(ASIDE_WIDTH, 100));
        return new HBox(32, form, aside);
    }

    /** The form's fields, for tests. */
    TextField titleField() {
        return title;
    }

    VisibilityPicker visibilityPicker() {
        return visibility;
    }

    UserMessage titleError() {
        return titleError.isVisible() ? UserMessage.of(titleError.getText()) : null;
    }
}
