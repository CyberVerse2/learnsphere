package edu.learnsphere.app.ui.components;

import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.service.CourseSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * A course in a grid: its cover, author, title and subtitle, then a footer that depends on what the
 * learner does with it. For a course being studied: a progress ring with the percentage and how many
 * reviews are due. For a course being created: what it holds and anything that needs attention (PDFs still
 * importing, drafts to review).
 *
 * <p>The whole card is one button: click, Enter or Space calls {@link #setOnOpen(Runnable)}. It lifts on
 * hover and shows a focus ring for the keyboard.
 */
public final class CourseCard extends Card {

    /** Which footer the card shows. */
    public enum Mode {
        STUDYING,
        CREATING
    }

    /** Most lines of title shown. */
    public static final int TITLE_LINES = 2;

    private final CourseSummary course;
    private final Mode mode;
    private final ProgressRing ring;
    private Runnable onOpen = () -> { };

    /** A card in the mode that fits the course: studying when enrolled, otherwise creating. */
    public CourseCard(CourseSummary course) {
        this(course, course.enrolled() ? Mode.STUDYING : Mode.CREATING);
    }

    public CourseCard(CourseSummary course, Mode mode) {
        this.course = Objects.requireNonNull(course, "course");
        this.mode = Objects.requireNonNull(mode, "mode");
        getStyleClass().add("course-card");
        setFocusTraversable(true);
        setAccessibleRole(AccessibleRole.BUTTON);
        setAccessibleText(course.title());

        CourseCover cover = new CourseCover(course.coverHue(), course.id(), CourseCover.Size.CARD);

        Label meta = new Label(metaLine(course, mode));
        meta.getStyleClass().addAll("text-caption", "course-card-meta");
        meta.setMnemonicParsing(false);
        Label title = new Label(course.title());
        title.getStyleClass().addAll("course-card-title");
        title.setWrapText(true);
        title.setMnemonicParsing(false);
        title.setMaxHeight(TITLE_LINES * 29);
        title.setMinHeight(Region.USE_PREF_SIZE);
        VBox body = new VBox(4, meta, title);
        if (!course.subtitle().isBlank()) {
            Label subtitle = new Label(course.subtitle());
            subtitle.getStyleClass().addAll("course-card-subtitle");
            subtitle.setMnemonicParsing(false);
            body.getChildren().add(subtitle);
        }
        body.getStyleClass().add("course-card-body");
        VBox.setVgrow(body, Priority.ALWAYS);

        ring = mode == Mode.STUDYING ? new ProgressRing(40, 4) : null;
        HBox footer = mode == Mode.STUDYING ? studyingFooter() : creatingFooter();
        footer.getStyleClass().add("course-card-footer");

        getChildren().setAll(cover, body, footer);
        setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                requestFocus();
                onOpen.run();
            }
        });
        setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                event.consume();
                onOpen.run();
            }
        });
    }

    public CourseSummary course() {
        return course;
    }

    public Mode mode() {
        return mode;
    }

    /** The progress ring of a studying card, or null for a creating card. */
    public ProgressRing ring() {
        return ring;
    }

    /** What opening the card does. */
    public void setOnOpen(Runnable action) {
        onOpen = Objects.requireNonNull(action, "action");
    }

    /** Eases the ring to the course's progress (call once the card is on screen). */
    public void animateIn() {
        if (ring != null) {
            ring.setProgress(course.progress());
        }
    }

    private HBox studyingFooter() {
        ring.setProgressNow(course.progress());
        Label percent = new Label(Formats.percent(course.progress()));
        percent.getStyleClass().addAll("semibold", "course-card-percent");
        Label done = new Label("complete");
        done.getStyleClass().add("text-caption");
        VBox progressText = new VBox(0, percent, done);
        progressText.setAlignment(Pos.CENTER_LEFT);
        HBox left = new HBox(10, ring, progressText);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Node right;
        if (course.dueCount() > 0) {
            right = new Chip(Formats.count(course.dueCount(), "review due", "reviews due"), Tone.ACCENT);
        } else if (course.newItemCount() > 0) {
            right = new Chip(Formats.count(course.newItemCount(), "new question", "new questions"), Tone.NEUTRAL);
        } else {
            Label caughtUp = new Label("All caught up");
            caughtUp.getStyleClass().addAll("text-caption", "course-card-caught-up");
            right = caughtUp;
        }
        HBox footer = new HBox(12, left, spacer, right);
        footer.setAlignment(Pos.CENTER_LEFT);
        return footer;
    }

    private HBox creatingFooter() {
        Node status = null;
        if (course.documentsProcessing() > 0) {
            status = new StatusPill(StatusPill.Status.WORKING,
                    Formats.count(course.documentsProcessing(), "PDF importing", "PDFs importing"));
        } else if (course.documentsNeedingAttention() > 0) {
            status = new StatusPill(StatusPill.Status.NEEDS_REVIEW,
                    Formats.count(course.documentsNeedingAttention(), "PDF to check", "PDFs to check"));
        } else if (course.draftItemCount() > 0) {
            status = new Chip(Formats.count(course.draftItemCount(), "draft to review", "drafts to review"), Tone.WARNING);
        }
        List<String> parts = new ArrayList<>();
        parts.add(Formats.count(course.moduleCount(), "module", "modules"));
        parts.add(Formats.count(course.documentCount(), "PDF", "PDFs"));
        if (status == null) {
            // With a status beside it there is only room for the modules and PDFs.
            parts.add(Formats.count(course.itemCount(), "question", "questions"));
        }
        Label contents = new Label(String.join(" · ", parts));
        contents.getStyleClass().addAll("text-caption", "course-card-contents");
        contents.setMinWidth(Region.USE_PREF_SIZE);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(12, contents, spacer);
        footer.setAlignment(Pos.CENTER_LEFT);
        if (status != null) {
            footer.getChildren().add(status);
        }
        return footer;
    }

    /** The small line above the title: the author for studied courses, the publishing state for created ones. */
    public static String metaLine(CourseSummary course, Mode mode) {
        if (mode == Mode.CREATING) {
            if (course.status() == CourseStatus.ARCHIVED) {
                return "Archived";
            }
            return course.versionNumber() > 0 ? "Published · version " + course.versionNumber() : "Draft · not published";
        }
        if (course.createdHere()) {
            return "Created by you";
        }
        return course.authorName().isBlank() ? "Shared course" : course.authorName();
    }
}
