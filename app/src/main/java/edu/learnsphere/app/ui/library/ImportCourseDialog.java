package edu.learnsphere.app.ui.library;

import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.JobProgress;
import edu.learnsphere.core.service.ImportOutcome;
import edu.learnsphere.core.service.ImportResult;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.PackageInfo;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.sharing.SharingMessages;
import edu.learnsphere.core.util.Subscription;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;

/**
 * Importing a course package, from reading the file to the finished course, in one sheet:
 *
 * <ol>
 *   <li>Reading the file, then the package's preview: cover, title, author, version and what it holds.</li>
 *   <li>What importing will do: add it, update the copy in the library (progress kept), go back to an older
 *       version (only after a warning), or nothing at all for a course made in this profile or a version the
 *       library already has.</li>
 *   <li>The code of a protected course, with the exact wrong-code sentence when it isn't right.</li>
 *   <li>The import as a background job with its progress line; closing the sheet lets it carry on.</li>
 *   <li>What the import did, with a button to open the course.</li>
 * </ol>
 */
public final class ImportCourseDialog {

    /** Where the sheet is. */
    public enum Step {
        READING, PREVIEW, IMPORTING, DONE, FAILED
    }

    private final Workspace workspace;
    private final Disposer disposer;
    private final Path file;
    private final Consumer<String> openCourse;
    private final Runnable chooseAnother;
    private final ModalSheet sheet = new ModalSheet("Import course");
    private final PasswordField code = new PasswordField();
    private final Label codeError = new Label();
    private Node anchor;
    private Step step = Step.READING;
    private PackageInfo info;
    private Button primary;
    private String jobId;
    private ProgressBarSlim bar;
    private Label progressLine;
    private Subscription jobEvents;
    private boolean busy;

    /**
     * @param file          the package the learner chose
     * @param openCourse    opens a course page by id (the sheet closes first)
     * @param chooseAnother lets the learner pick another file, or null to offer no such button
     */
    public ImportCourseDialog(Workspace workspace, Disposer disposer, Path file, Consumer<String> openCourse,
                              Runnable chooseAnother) {
        this.workspace = Objects.requireNonNull(workspace, "workspace");
        this.disposer = Objects.requireNonNull(disposer, "disposer");
        this.file = Objects.requireNonNull(file, "file");
        this.openCourse = Objects.requireNonNull(openCourse, "openCourse");
        this.chooseAnother = chooseAnother;
        sheet.setCardWidth(600);
        sheet.getStyleClass().add("import-sheet");
        code.setPromptText("Course code");
        code.setAccessibleText("Course code");
        codeError.getStyleClass().add("sheet-error");
        codeError.setWrapText(true);
        codeError.setVisible(false);
        codeError.setManaged(false);
        code.textProperty().addListener((obs, old, value) -> showCodeError(null));
        disposer.onDispose(() -> {
            sheet.close();
            stopFollowing();
        });
    }

    /** Opens the sheet over the window of {@code anchor} and reads the file. */
    public ImportCourseDialog show(Node anchor) {
        this.anchor = anchor;
        reading();
        sheet.show(anchor);
        disposer.add(UiTasks.run(() -> workspace.sharing().inspect(file), this::preview, this::unreadable));
        return this;
    }

    public ModalSheet sheet() {
        return sheet;
    }

    public Step step() {
        return step;
    }

    /** The package's details once read, or null. */
    public PackageInfo info() {
        return info;
    }

    public PasswordField codeField() {
        return code;
    }

    /** The sentence under the code field, or empty. */
    public String codeErrorText() {
        return codeError.isVisible() ? codeError.getText() : "";
    }

    /** The main button of the current step, or null. */
    public Button primaryButton() {
        return primary;
    }

    // ---- steps -------------------------------------------------------------------------------

    private void reading() {
        step = Step.READING;
        sheet.setTitle("Import course");
        sheet.subtitle("Reading the course file…");
        Node cover = Skeleton.block(200, 136);
        VBox lines = new VBox(10, Skeleton.line(90), Skeleton.block(240, 22), Skeleton.line(180), Skeleton.line(220));
        HBox row = new HBox(24, cover, lines);
        sheet.body().getChildren().setAll(row);
        Button cancel = new Button("Cancel");
        cancel.setOnAction(event -> sheet.close());
        primary = null;
        sheet.setButtons(cancel);
    }

    private void unreadable(UserMessage message) {
        step = Step.FAILED;
        sheet.setTitle("This file can't be imported");
        sheet.subtitle(null);
        sheet.body().getChildren().setAll(notice(Tone.DANGER, Feather.ALERT_CIRCLE, message.text()));
        Button close = new Button("Close");
        close.setOnAction(event -> sheet.close());
        if (chooseAnother != null) {
            Button again = new Button("Choose another file");
            again.getStyleClass().add("primary");
            again.setOnAction(event -> {
                sheet.close();
                chooseAnother.run();
            });
            primary = again;
            sheet.setButtons(close, again);
            sheet.setDefaultButton(again);
        } else {
            primary = null;
            sheet.setButtons(close);
        }
        sheet.focusFirst();
    }

    private void preview(PackageInfo info) {
        this.info = info;
        step = Step.PREVIEW;
        sheet.setTitle("Import course");
        sheet.subtitle(null);
        VBox body = new VBox(20, packageHeader(info));

        Button cancel = new Button("Cancel");
        cancel.setOnAction(event -> sheet.close());
        boolean sameVersion = info.existingCourseId() != null && info.installedVersion() != null
                && info.installedVersion() == info.versionNumber() && !info.createdHere();

        if (info.createdHere() || sameVersion) {
            String text = info.createdHere() ? SharingMessages.COURSE_MADE_HERE.text()
                    : "Your library already has version " + info.versionNumber() + " of this course.";
            body.getChildren().add(notice(info.createdHere() ? Tone.WARNING : Tone.ACCENT, Feather.INFO, text));
            cancel.setText("Close");
            Button open = new Button("Open course");
            open.getStyleClass().add("primary");
            open.setOnAction(event -> open(info.existingCourseId()));
            primary = open;
            sheet.setButtons(cancel, open);
            sheet.setDefaultButton(open);
            sheet.body().getChildren().setAll(body);
            sheet.focusFirst();
            return;
        }

        Button go = new Button();
        go.getStyleClass().add("primary");
        boolean older = info.isOlderThanInstalled();
        if (older) {
            body.getChildren().add(notice(Tone.WARNING, Feather.ALERT_TRIANGLE, "Your library has version "
                    + info.installedVersion() + ". This file has version " + info.versionNumber()
                    + ", an older one. Importing it takes the course back to that version; your progress is kept."));
            go.setText("Go back to version " + info.versionNumber());
        } else if (info.isUpdate()) {
            body.getChildren().add(notice(Tone.ACCENT, Feather.REFRESH_CW, "This updates your copy from version "
                    + info.installedVersion() + " to version " + info.versionNumber() + ". Your progress is kept."));
            go.setText("Update course");
        } else {
            go.setText("Add to library");
        }
        if (info.protectedByCode()) {
            Label label = new Label("Course code");
            label.getStyleClass().add("field-label");
            label.setLabelFor(code);
            Label hint = new Label("This course is protected. Enter the code from the course author.");
            hint.getStyleClass().addAll("text-caption", "field-hint");
            hint.setWrapText(true);
            VBox field = new VBox(8, label, code, codeError, hint);
            field.getStyleClass().add("import-code");
            body.getChildren().add(field);
            go.disableProperty().bind(Bindings.createBooleanBinding(() -> code.getText().isEmpty(), code.textProperty()));
            sheet.setInitialFocus(code);
        }
        go.setOnAction(event -> startImport(older));
        primary = go;
        sheet.setButtons(cancel, go);
        sheet.setDefaultButton(go);
        sheet.body().getChildren().setAll(body);
        sheet.focusFirst();
    }

    private void startImport(boolean allowOlder) {
        if (busy) {
            return;
        }
        busy = true;
        primary.setText("Starting…");
        char[] secret = info.protectedByCode() ? code.getText().toCharArray() : null;
        disposer.add(UiTasks.run(() -> workspace.sharing().importCourse(file, secret, allowOlder), this::importing,
                message -> {
                    busy = false;
                    if (secret != null) {
                        Arrays.fill(secret, '\0');
                    }
                    if (info.protectedByCode() && (message.equals(UserMessage.WRONG_COURSE_CODE)
                            || message.equals(SharingMessages.CODE_REQUIRED))) {
                        preview(info);
                        showCodeError(message);
                        code.selectAll();
                        code.requestFocus();
                    } else {
                        failed(message);
                    }
                }));
    }

    private void importing(JobUpdate job) {
        step = Step.IMPORTING;
        jobId = job.jobId();
        code.clear();
        sheet.setTitle("Importing " + info.title());
        sheet.subtitle("You can close this window. The import carries on, and the course appears in your library.");
        bar = new ProgressBarSlim(6);
        bar.setMaxWidth(Double.MAX_VALUE);
        bar.setProgressNow(job.progress());
        progressLine = new Label(job.message() == null || job.message().isBlank() ? "Waiting to start…" : job.message());
        progressLine.getStyleClass().addAll("text-caption", "import-progress-line");
        CourseCover cover = new CourseCover(info.coverHue(), info.packageId(), CourseCover.Size.MEDIUM);
        VBox progress = new VBox(10, bar, progressLine);
        HBox.setHgrow(progress, Priority.ALWAYS);
        progress.setAlignment(Pos.CENTER_LEFT);
        HBox row = new HBox(20, cover, progress);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("import-progress");
        sheet.body().getChildren().setAll(row);
        Button cancel = new Button("Cancel import");
        cancel.setOnAction(event -> {
            cancel.setDisable(true);
            disposer.add(UiTasks.run(() -> {
                workspace.jobs().cancel(jobId);
                return null;
            }, ignored -> { }, this::failed));
        });
        Button close = new Button("Close");
        close.setOnAction(event -> sheet.close());
        primary = null;
        sheet.setButtons(cancel, close);
        sheet.setDefaultButton(null);
        sheet.setInitialFocus(close);
        sheet.focusFirst();

        jobEvents = disposer.add(Fx.subscribe(workspace.events(), JobProgress.class, event -> {
            if (event.job().jobId().equals(jobId)) {
                jobChanged(event.job());
            }
        }));
        // The job may have moved on before the subscription started.
        disposer.add(UiTasks.run(() -> workspace.jobs().find(jobId).orElse(null), latest -> {
            if (latest != null) {
                jobChanged(latest);
            }
        }, message -> { }));
    }

    private void jobChanged(JobUpdate job) {
        if (step != Step.IMPORTING) {
            return;
        }
        switch (job.status()) {
            case QUEUED, RUNNING -> {
                bar.setProgress(job.progress());
                if (job.message() != null && !job.message().isBlank()) {
                    progressLine.setText(job.message());
                }
            }
            case DONE -> {
                stopFollowing();
                bar.setProgress(1);
                disposer.add(UiTasks.run(() -> workspace.sharing().importResult(job.jobId()).orElse(null),
                        this::done, this::failed));
            }
            case CANCELLED -> {
                stopFollowing();
                failed(UserMessage.of("The import was cancelled. Import the course again to add it."));
            }
            case FAILED -> {
                stopFollowing();
                failed(job.failure() != null ? job.failure() : UserMessage.SOMETHING_WENT_WRONG);
            }
        }
    }

    private void done(ImportResult result) {
        step = Step.DONE;
        busy = false;
        String courseId = result == null ? info.existingCourseId() : result.courseId();
        String title = result == null || result.courseTitle().isBlank() ? info.title() : result.courseTitle();
        boolean updated = result != null && result.outcome() == ImportOutcome.UPDATED;
        String heading = updated ? title + " is up to date" : title + " is in your library";
        sheet.setTitle(heading);
        sheet.subtitle(null);
        Label summary = new Label(summary(result));
        summary.getStyleClass().add("import-summary");
        summary.setWrapText(true);
        CourseCover cover = new CourseCover(info.coverHue(), info.packageId(), CourseCover.Size.MEDIUM);
        StackPane badge = new StackPane(Icons.of(Feather.CHECK, 14));
        badge.getStyleClass().add("import-done-badge");
        StackPane coverWithBadge = new StackPane(cover, badge);
        StackPane.setAlignment(badge, Pos.BOTTOM_RIGHT);
        coverWithBadge.setMaxSize(72, 72);
        HBox row = new HBox(20, coverWithBadge, summary);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(summary, Priority.ALWAYS);
        sheet.body().getChildren().setAll(row);
        Button close = new Button("Done");
        close.setOnAction(event -> sheet.close());
        Button open = new Button("Open course");
        open.getStyleClass().add("primary");
        open.setOnAction(event -> open(courseId));
        open.setDisable(courseId == null);
        primary = open;
        sheet.setButtons(close, open);
        sheet.setDefaultButton(open);
        sheet.setInitialFocus(open);
        sheet.focusFirst();
        if (!sheet.isShowing() && anchor != null && anchor.getScene() != null) {
            Toasts.show(anchor, Toasts.Kind.SUCCESS, heading, courseId == null ? null : "Open",
                    courseId == null ? null : () -> openCourse.accept(courseId));
        }
    }

    private void failed(UserMessage message) {
        busy = false;
        boolean wasImporting = step == Step.IMPORTING;
        step = Step.FAILED;
        sheet.setTitle("The course wasn't imported");
        sheet.subtitle(null);
        sheet.body().getChildren().setAll(notice(Tone.DANGER, Feather.ALERT_CIRCLE, message.text()));
        Button close = new Button("Close");
        close.setOnAction(event -> sheet.close());
        Button again = new Button("Try again");
        again.getStyleClass().add("primary");
        again.setOnAction(event -> {
            reading();
            disposer.add(UiTasks.run(() -> workspace.sharing().inspect(file), this::preview, this::unreadable));
        });
        primary = again;
        sheet.setButtons(close, again);
        sheet.setDefaultButton(again);
        sheet.setInitialFocus(again);
        sheet.focusFirst();
        if (wasImporting && !sheet.isShowing() && anchor != null && anchor.getScene() != null) {
            Toasts.error(anchor, message);
        }
    }

    // ---- pieces ------------------------------------------------------------------------------

    private void open(String courseId) {
        sheet.close();
        if (courseId != null) {
            openCourse.accept(courseId);
        }
    }

    private void stopFollowing() {
        if (jobEvents != null) {
            jobEvents.close();
            jobEvents = null;
        }
    }

    private void showCodeError(UserMessage message) {
        codeError.setText(message == null ? "" : message.text());
        codeError.setVisible(message != null);
        codeError.setManaged(message != null);
        code.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("invalid"), message != null);
    }

    /** The cover, author, title, subtitle and what the package holds. */
    static Node packageHeader(PackageInfo info) {
        StackPane cover = new StackPane(new CourseCover(info.coverHue(), info.packageId(), CourseCover.Size.CARD));
        cover.getStyleClass().add("import-cover");
        cover.setMinWidth(200);
        cover.setPrefWidth(200);
        cover.setMaxWidth(200);
        Label author = new Label(info.authorName().isBlank() ? "Shared course" : info.authorName());
        author.getStyleClass().addAll("text-caption", "import-author");
        Label title = new Label(info.title());
        title.getStyleClass().addAll("text-title", "import-title");
        title.setWrapText(true);
        VBox text = new VBox(4, author, title);
        if (!info.subtitle().isBlank()) {
            Label subtitle = new Label(info.subtitle());
            subtitle.getStyleClass().add("import-subtitle");
            subtitle.setWrapText(true);
            text.getChildren().add(subtitle);
        }
        Label facts = new Label(facts(info));
        facts.getStyleClass().addAll("text-caption", "import-facts");
        facts.setWrapText(true);
        text.getChildren().add(facts);
        VBox.setMargin(facts, new javafx.geometry.Insets(8, 0, 0, 0));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(24, cover, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("import-header");
        return row;
    }

    /** "Version 3 · 4 modules · 27 questions · 4 PDFs · 12.4 MB". */
    static String facts(PackageInfo info) {
        List<String> parts = new ArrayList<>();
        parts.add("Version " + info.versionNumber());
        parts.add(Formats.count(info.moduleCount(), "module", "modules"));
        parts.add(Formats.count(info.itemCount(), "question", "questions"));
        if (info.documentCount() > 0) {
            parts.add(Formats.count(info.documentCount(), "PDF", "PDFs"));
        }
        if (info.byteSize() > 0) {
            parts.add(fileSize(info.byteSize()));
        }
        return String.join(" · ", parts);
    }

    /** What an import did, in one or two sentences. */
    static String summary(ImportResult result) {
        if (result == null) {
            return "The course is ready to study.";
        }
        return switch (result.outcome()) {
            case CREATED -> Formats.count(result.added(), "question is", "questions are") + " ready to study. "
                    + "It's on Home and in Study next.";
            case UPDATED -> {
                List<String> parts = new ArrayList<>();
                if (result.added() > 0) {
                    parts.add(Formats.count(result.added(), "new question", "new questions"));
                }
                if (result.updated() > 0) {
                    parts.add(Formats.count(result.updated(), "question changed", "questions changed"));
                }
                if (result.retired() > 0) {
                    parts.add(Formats.count(result.retired(), "question taken out", "questions taken out"));
                }
                String changes = parts.isEmpty() ? "The questions are the same as before" : joinSentence(parts);
                yield "Version " + result.versionNumber() + ": " + changes + ". Your progress is kept.";
            }
            case ALREADY_CURRENT -> "Your library already had this version, so nothing changed.";
            case FEEDBACK_ADDED -> "The course is ready to study.";
        };
    }

    private static String joinSentence(List<String> parts) {
        if (parts.size() == 1) {
            return parts.getFirst();
        }
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.getLast();
    }

    /** A file size in plain units, e.g. {@code "12.4 MB"}. */
    static String fileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " bytes";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return Math.round(kb) + " KB";
        }
        double mb = kb / 1024.0;
        if (mb < 100) {
            return String.format(Locale.ENGLISH, "%.1f MB", mb);
        }
        return Math.round(mb) + " MB";
    }

    /** A tinted line with an icon: what will happen, or what went wrong. */
    static Node notice(Tone tone, Ikon icon, String text) {
        Label label = new Label(text);
        label.getStyleClass().add("sheet-notice-text");
        label.setWrapText(true);
        HBox.setHgrow(label, Priority.ALWAYS);
        var glyph = Icons.of(icon, 16);
        glyph.getStyleClass().add("sheet-notice-icon");
        HBox box = new HBox(10, glyph, label);
        box.setAlignment(Pos.TOP_LEFT);
        box.getStyleClass().add("sheet-notice");
        Tone.apply(box, tone);
        return box;
    }
}
