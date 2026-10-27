package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.ClassInsightSummary;
import edu.learnsphere.core.service.CourseDetail;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.ExportOptions;
import edu.learnsphere.core.service.FlagSummary;
import edu.learnsphere.core.service.ImportResult;
import edu.learnsphere.core.service.ItemCounts;
import edu.learnsphere.core.service.ItemInsight;
import edu.learnsphere.core.service.LearnerInsight;
import edu.learnsphere.core.service.PackageInfo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The Publish and share tab. On the left, publishing: how many questions are approved, waiting or flagged,
 * what learners get in the next version, a release note and Publish version N, then every version published
 * with its note. On the right, sharing: who can open the course, Export course (with a code for a protected
 * course, and the saved file shown in the file manager afterwards), learners' progress files for class
 * insights, and learners' feedback files with the flags they raise, each linking to its question.
 */
final class PublishSection extends StudioSection {

    static final String NOTHING_APPROVED = "Approve at least one question before publishing.";
    static final int MIN_CODE = 4;

    private static final double SHARE_WIDTH = 380;
    private static final int MAX_NOTES = 2_000;

    private final VBox root = new VBox(24);
    private final VBox publishPanel = new VBox();
    private final VBox versionsPanel = new VBox();
    private final VBox sharePanel = new VBox();
    private final VBox insightsPanel = new VBox();
    private final VBox feedbackPanel = new VBox();
    private final VBox exportStatus = new VBox(12);
    private final TextArea notes = new TextArea();
    private final Button publish = new Button("Publish");
    private final Button export = new Button("Export course…", Icons.of(Feather.DOWNLOAD, 14));
    private final Refresher<Data> refresher;
    private Data data;
    private boolean built;
    private boolean publishing;
    private boolean exporting;
    private PackageInfo lastExport;

    /** Everything the tab shows. */
    private record Data(CourseDetail detail, ItemCounts counts, List<CourseVersion> versions,
                        ClassInsightSummary insights, List<FlagSummary> flags) {
        CourseSummary course() {
            return detail.summary();
        }
    }

    PublishSection(StudioHost host) {
        super(host);
        root.getStyleClass().add("studio-publish");
        notes.getStyleClass().add("release-note");
        notes.setPromptText("For example: Added a module on reaction mechanisms and fixed two answers.");
        notes.setWrapText(true);
        notes.setPrefRowCount(3);
        notes.setAccessibleText("Release note");
        StudioUi.limit(notes, MAX_NOTES);
        publish.getStyleClass().addAll("primary", "publish-version");
        publish.setOnAction(event -> publishVersion());
        export.getStyleClass().add("export-course");
        export.setOnAction(event -> exportCourse());
        root.getChildren().setAll(loading());
        refresher = new Refresher<>(disposer, () -> {
            var ws = workspace();
            String id = courseId();
            return new Data(ws.courses().get(id), ws.items().counts(id), ws.courses().versions(id),
                    ws.sharing().classInsights(id), ws.items().flags(id, false));
        }, this::render, message -> root.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.SEARCH,
                "Publishing couldn't load", message.text()).primary("Try again", this::reload)));
    }

    @Override
    Region root() {
        return root;
    }

    @Override
    void onShown() {
        reload();
        on(CourseChanged.class, event -> {
            if (courseId().equals(event.courseId())) {
                reload();
            }
        });
        on(ItemsChanged.class, event -> {
            if (courseId().equals(event.courseId())) {
                reload();
            }
        });
        on(DocumentChanged.class, event -> {
            if (courseId().equals(event.courseId()) && !event.hasSteps()) {
                reload();
            }
        });
    }

    private void reload() {
        refresher.request();
    }

    // -------------------------------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------------------------------

    private void render(Data latest) {
        data = latest;
        if (!built) {
            built = true;
            VBox left = new VBox(24, publishPanel, versionsPanel);
            left.setMinWidth(0);
            HBox.setHgrow(left, Priority.ALWAYS);
            VBox right = new VBox(24, sharePanel, feedbackPanel, insightsPanel);
            right.setMinWidth(SHARE_WIDTH);
            right.setPrefWidth(SHARE_WIDTH);
            right.setMaxWidth(SHARE_WIDTH);
            root.getChildren().setAll(new HBox(28, left, right));
        }
        renderPublish();
        renderVersions();
        renderShare();
        renderFeedback();
        renderInsights();
    }

    private void renderPublish() {
        ItemCounts counts = data.counts();
        CourseSummary course = data.course();
        int next = data.versions().stream().mapToInt(CourseVersion::number).max().orElse(0) + 1;

        GridPane ledger = new GridPane();
        ledger.getStyleClass().add("ledger");
        ledger.add(ledgerCell(counts.approved(), "approved", null, false), 0, 0);
        ledger.add(ledgerCell(counts.draft(), "drafts to review", counts.draft() > 0 ? "waiting" : null, true), 1, 0);
        ledger.add(ledgerCell(course.openFlagCount(), "flagged", course.openFlagCount() > 0 ? "flagged" : null, true), 2, 0);
        ledger.add(ledgerCell(counts.rejected() + counts.retired(), "rejected or retired", null, true), 3, 0);

        VBox gets = new VBox(8);
        gets.getStyleClass().add("learners-get");
        Label getsTitle = new Label("Learners get");
        getsTitle.getStyleClass().addAll("text-overline");
        gets.getChildren().add(getsTitle);
        if (counts.approved() > 0) {
            String where = course.moduleCount() == 0 ? ""
                    : " in " + Formats.count(course.moduleCount(), "module", "modules");
            gets.getChildren().add(gets(Feather.CHECK,
                    Formats.count(counts.approved(), "approved question", "approved questions") + where, false));
        }
        if (course.documentCount() > 0) {
            gets.getChildren().add(gets(Feather.CHECK, "Your " + Formats.count(course.documentCount(), "PDF", "PDFs")
                    + ", so they can ask about them and see where each answer comes from", false));
        }
        int leftOut = counts.draft() + counts.rejected();
        if (leftOut > 0) {
            gets.getChildren().add(gets(Feather.MINUS, Formats.count(leftOut, "draft or rejected question stays",
                    "drafts and rejected questions stay") + " here with you", true));
        }

        Label noteLabel = new Label("Release note");
        noteLabel.getStyleClass().add("field-label");
        noteLabel.setLabelFor(notes);
        Label noteHint = new Label("Shown with the version, so learners know what changed.");
        noteHint.getStyleClass().add("field-hint");
        VBox noteField = new VBox(6, noteLabel, notes, noteHint);

        publish.setText("Publish version " + next);
        publish.setDisable(publishing || counts.approved() == 0);
        HBox actions = new HBox(12);
        actions.setAlignment(Pos.CENTER_LEFT);
        if (counts.approved() == 0) {
            Label why = new Label(NOTHING_APPROVED);
            why.getStyleClass().addAll("field-hint", "publish-blocked");
            why.setWrapText(true);
            why.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(why, Priority.ALWAYS);
            Button review = new Button("Open review queue");
            review.getStyleClass().addAll("quiet", "small");
            review.setOnAction(event -> host.tabs().open(StudioTab.REVIEW, null));
            actions.getChildren().addAll(why, review, publish);
        } else if (counts.draft() > 0) {
            Label why = new Label(Formats.count(counts.draft(), "draft isn't", "drafts aren't")
                    + " included until you approve " + (counts.draft() == 1 ? "it." : "them."));
            why.getStyleClass().add("field-hint");
            why.setWrapText(true);
            why.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(why, Priority.ALWAYS);
            actions.getChildren().addAll(why, publish);
        } else {
            actions.getChildren().addAll(StudioUi.grow(), publish);
        }
        publish.setMinWidth(Region.USE_PREF_SIZE);

        String subtitle = course.versionNumber() > 0
                ? "Learners have version " + course.versionNumber() + ". A new version adds what you've approved since."
                : "Publishing makes a version learners can open. Only approved questions are included.";
        publishPanel.getChildren().setAll(StudioUi.panel("Publish a version", subtitle, ledger, gets, noteField, actions));
    }

    private static VBox ledgerCell(int count, String label, String tone, boolean divided) {
        Label number = new Label(Formats.number(count));
        number.getStyleClass().addAll("text-numeral", "ledger-number");
        Label name = new Label(label);
        name.getStyleClass().add("ledger-label");
        VBox cell = new VBox(2, number, name);
        cell.getStyleClass().add("ledger-cell");
        if (divided) {
            cell.getStyleClass().add("divided");
        }
        if (tone != null) {
            cell.getStyleClass().add(tone);
        }
        cell.setAccessibleRole(AccessibleRole.TEXT);
        cell.setAccessibleText(count + " " + label);
        return cell;
    }

    private static Label gets(Feather icon, String text, boolean leftOut) {
        Label line = new Label(text, Icons.of(icon, 14));
        line.setWrapText(true);
        line.setMnemonicParsing(false);
        if (leftOut) {
            line.getStyleClass().add("left-out");
        }
        return line;
    }

    private void renderVersions() {
        List<CourseVersion> versions = data.versions().stream()
                .sorted(Comparator.comparingInt(CourseVersion::number).reversed()).toList();
        if (versions.isEmpty()) {
            Label none = new Label("Nothing published yet. Each version you publish is listed here with its note.");
            none.getStyleClass().add("field-hint");
            none.setWrapText(true);
            versionsPanel.getChildren().setAll(StudioUi.panel("Versions", null, none));
            return;
        }
        VBox list = new VBox();
        for (CourseVersion version : versions) {
            Label badge = new Label("v" + version.number());
            badge.getStyleClass().addAll("version-badge", "semibold");
            badge.setMinWidth(Region.USE_PREF_SIZE);
            Label when = new Label("Version " + version.number() + " · published "
                    + Formats.ago(version.publishedAt(), host.context().clock()));
            when.getStyleClass().add("text-caption");
            Label note = new Label(version.notes().isBlank() ? "No release note" : version.notes());
            note.getStyleClass().add("version-notes");
            if (version.notes().isBlank()) {
                note.getStyleClass().add("empty");
            }
            note.setWrapText(true);
            note.setMnemonicParsing(false);
            VBox text = new VBox(2, when, note);
            text.setMinWidth(0);
            HBox.setHgrow(text, Priority.ALWAYS);
            HBox row = new HBox(16, badge, text);
            row.setAlignment(Pos.TOP_LEFT);
            row.getStyleClass().add("version-entry");
            if (list.getChildren().isEmpty()) {
                row.getStyleClass().addAll("first", "latest");
            }
            list.getChildren().add(row);
        }
        versionsPanel.getChildren().setAll(StudioUi.panel("Versions",
                Formats.count(versions.size(), "version", "versions") + " published", list));
    }

    // -------------------------------------------------------------------------------------------------
    // Sharing
    // -------------------------------------------------------------------------------------------------

    private void renderShare() {
        CourseSummary course = data.course();
        Visibility visibility = course.visibility();
        StackPane icon = new StackPane(Icons.of(switch (visibility) {
            case PRIVATE -> Feather.LOCK;
            case SHAREABLE -> Feather.SHARE_2;
            case PROTECTED -> Feather.KEY;
        }, 18));
        icon.getStyleClass().add("share-state-icon");
        Label who = new Label(StudioFormats.visibilityLabel(visibility));
        who.getStyleClass().addAll("medium", "share-visibility");
        Label detail = new Label(StudioFormats.visibilityDetail(visibility));
        detail.getStyleClass().add("field-hint");
        detail.setWrapText(true);
        VBox text = new VBox(2, who, detail);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button change = new Button("Change");
        change.getStyleClass().addAll("quiet", "small", "share-change");
        change.setAccessibleText("Change who can open it");
        change.setOnAction(event -> host.tabs().open(StudioTab.DETAILS, null));
        change.setMinWidth(Region.USE_PREF_SIZE);
        HBox state = new HBox(14, icon, text, change);
        state.setAlignment(Pos.TOP_LEFT);
        state.getStyleClass().add("share-state");

        List<Node> content = new ArrayList<>();
        content.add(state);
        export.setDisable(exporting);
        if (course.versionNumber() == 0) {
            content.add(StudioUi.note(Feather.INFO, "Publish a version before sharing it.", null));
        } else if (visibility == Visibility.PRIVATE) {
            content.add(StudioUi.note(Feather.INFO,
                    "Only you can open this course. Choose who can open it to share it.", null));
        } else {
            Label how = new Label(visibility == Visibility.PROTECTED
                    ? "Export version " + course.versionNumber() + " as a course file and send it with its code. "
                    + "Learners add it with Import course in their Library."
                    : "Export version " + course.versionNumber() + " as a course file and send it to learners. "
                    + "They add it with Import course in their Library.");
            how.getStyleClass().add("field-hint");
            how.setWrapText(true);
            content.add(how);
            content.add(export);
        }
        content.add(exportStatus);
        sharePanel.getChildren().setAll(StudioUi.panel("Share", null, content.toArray(Node[]::new)));
    }

    /** Asks where to save the course file (and its code when protected), then writes it. */
    void exportCourse() {
        if (data == null || exporting) {
            return;
        }
        CourseSummary course = data.course();
        Path target = host.files().chooseCourseFile(window(), StudioFormats.courseFileName(course.title()));
        if (target == null) {
            return;
        }
        if (course.visibility() == Visibility.PROTECTED) {
            askForCode(code -> write(target, ExportOptions.protectedWith(code)));
        } else {
            write(target, ExportOptions.standard());
        }
    }

    private void askForCode(java.util.function.Consumer<char[]> then) {
        TextField code = new TextField();
        code.getStyleClass().add("protection-code");
        code.setPromptText("At least " + MIN_CODE + " characters");
        VBox content = StudioUi.field("Code", code,
                "Learners type this code to open the course. Send it separately from the file.");
        StudioDialog.of("Choose a code")
                .message("Anyone who opens this file needs the code.")
                .content(content)
                .confirm("Export")
                .focus(code)
                .onConfirm(dialog -> {
                    String text = StudioUi.text(code);
                    if (text.length() < MIN_CODE) {
                        dialog.showError("Choose a code of at least " + MIN_CODE + " characters to protect this course.");
                        code.requestFocus();
                        return;
                    }
                    dialog.close(true);
                    then.accept(text.toCharArray());
                })
                .show(root);
    }

    private void write(Path target, ExportOptions options) {
        exporting = true;
        export.setDisable(true);
        ActivityIndicator spinner = new ActivityIndicator(16);
        Label saving = new Label("Saving “" + target.getFileName() + "”…");
        saving.getStyleClass().add("field-hint");
        HBox progress = new HBox(10, spinner, saving);
        progress.setAlignment(Pos.CENTER_LEFT);
        progress.getStyleClass().add("export-progress");
        exportStatus.getChildren().setAll(progress);
        run(() -> {
            try {
                return workspace().sharing().exportCourse(courseId(), target, options);
            } finally {
                if (options.protectionCode() != null) {
                    Arrays.fill(options.protectionCode(), '\0');
                }
            }
        }, info -> {
            exporting = false;
            export.setDisable(false);
            lastExport = info;
            showExported(target, info);
            success("Course exported");
        }, message -> {
            exporting = false;
            export.setDisable(false);
            exportStatus.getChildren().setAll(StudioUi.note(Feather.ALERT_CIRCLE, message.text(), "danger"));
        });
    }

    private void showExported(Path file, PackageInfo info) {
        Label title = new Label("Saved “" + file.getFileName() + "”");
        title.getStyleClass().addAll("export-done-title", "medium");
        title.setWrapText(true);
        title.setMnemonicParsing(false);
        List<String> parts = new ArrayList<>();
        parts.add("Version " + info.versionNumber());
        parts.add(Formats.count(info.itemCount(), "question", "questions"));
        if (info.documentCount() > 0) {
            parts.add(Formats.count(info.documentCount(), "PDF", "PDFs"));
        }
        parts.add(StudioFormats.size(info.byteSize()));
        if (info.protectedByCode()) {
            parts.add("needs its code");
        }
        Label meta = new Label(String.join(" · ", parts));
        meta.getStyleClass().add("export-done-path");
        meta.setWrapText(true);
        Button reveal = new Button(StudioFormats.revealLabel());
        reveal.getStyleClass().addAll("small", "export-reveal");
        reveal.setOnAction(event -> run(() -> {
            host.files().reveal(file);
            return null;
        }, ignored -> { }, this::error));
        VBox text = new VBox(2, title, meta, reveal);
        VBox.setMargin(reveal, new javafx.geometry.Insets(8, 0, 0, 0));
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox done = new HBox(12, Icons.of(Feather.CHECK_CIRCLE, 18), text);
        done.setAlignment(Pos.TOP_LEFT);
        done.getStyleClass().add("export-done");
        exportStatus.getChildren().setAll(done);
    }

    /** What the last export wrote, or null (tests). */
    PackageInfo lastExport() {
        return lastExport;
    }

    // -------------------------------------------------------------------------------------------------
    // Publishing
    // -------------------------------------------------------------------------------------------------

    /** Publishes the approved questions as the next version, with the release note. */
    void publishVersion() {
        if (data == null || publishing || data.counts().approved() == 0) {
            return;
        }
        String note = StudioUi.text(notes);
        publishing = true;
        publish.setDisable(true);
        run(() -> workspace().courses().publish(courseId(), note), version -> {
            publishing = false;
            notes.clear();
            success("Published version " + version.number());
            reload();
        }, message -> {
            publishing = false;
            publish.setDisable(false);
            error(message);
        });
    }

    /** The release note field (tests). */
    TextArea notesField() {
        return notes;
    }

    // -------------------------------------------------------------------------------------------------
    // Feedback and class insights
    // -------------------------------------------------------------------------------------------------

    private void renderFeedback() {
        Button add = new Button("Add feedback file…", Icons.of(Feather.INBOX, 14));
        add.getStyleClass().addAll("small", "add-feedback");
        add.setOnAction(event -> importFeedback());
        List<Node> content = new ArrayList<>();
        List<FlagSummary> flags = data.flags();
        if (flags.isEmpty()) {
            Label none = new Label("No open flags. When learners flag a question and send you their feedback file, "
                    + "the flags appear here.");
            none.getStyleClass().add("field-hint");
            none.setWrapText(true);
            content.add(none);
        } else {
            VBox list = new VBox();
            for (FlagSummary flag : flags) {
                Node row = flagRow(flag);
                if (list.getChildren().isEmpty()) {
                    row.getStyleClass().add("first");
                }
                list.getChildren().add(row);
            }
            content.add(list);
        }
        content.add(add);
        String subtitle = flags.isEmpty() ? null : Formats.count(flags.size(), "open flag", "open flags");
        feedbackPanel.getChildren().setAll(StudioUi.panel("Learner feedback", subtitle, content.toArray(Node[]::new)));
    }

    private Node flagRow(FlagSummary summary) {
        Chip reason = new Chip(StudioFormats.reasonLabel(summary.flag().reason()), Tone.DANGER, Feather.FLAG);
        Label prompt = new Label(Formats.shorten(summary.itemPrompt(), 140));
        prompt.getStyleClass().addAll("flag-prompt");
        prompt.setWrapText(true);
        prompt.setMnemonicParsing(false);
        VBox text = new VBox(4, prompt);
        if (!summary.flag().note().isBlank()) {
            Label note = new Label("“" + summary.flag().note() + "”");
            note.getStyleClass().add("flag-note");
            note.setWrapText(true);
            note.setMnemonicParsing(false);
            text.getChildren().add(note);
        }
        Label where = new Label((summary.moduleTitle() == null ? "Whole course" : summary.moduleTitle()) + " · "
                + Formats.ago(summary.flag().createdAt(), host.context().clock()));
        where.getStyleClass().add("text-caption");
        text.getChildren().add(where);
        text.setMinWidth(0);
        text.getStyleClass().add("flag-link");
        text.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                openItem(summary);
            }
        });
        IconButton open = new IconButton(Feather.EYE, "Show the question").small();
        open.getStyleClass().add("flag-open");
        open.setOnAction(event -> openItem(summary));
        Button resolve = new Button("Resolve");
        resolve.getStyleClass().addAll("small", "flag-resolve");
        resolve.setMinWidth(Region.USE_PREF_SIZE);
        resolve.setOnAction(event -> {
            resolve.setDisable(true);
            act(() -> {
                workspace().items().resolveFlag(summary.flag().id());
                return null;
            }, "Flag resolved");
        });
        HBox top = new HBox(8, reason, StudioUi.grow(), open, resolve);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox row = new VBox(8, top, text);
        row.getStyleClass().add("flag-row");
        return row;
    }

    private void openItem(FlagSummary summary) {
        ItemPreviewDialog.show(root, workspace(), host.context().clock(), summary.itemId());
    }

    private void importFeedback() {
        Path file = host.files().chooseFeedbackFile(window());
        if (file == null) {
            return;
        }
        run(() -> workspace().sharing().importFeedback(file), this::feedbackImported, this::error);
    }

    private void feedbackImported(ImportResult result) {
        if (result.added() == 0) {
            success("This file has no new flags for this course");
        } else {
            success("Added " + Formats.count(result.added(), "flag", "flags") + " from learners");
        }
        reload();
    }

    private void renderInsights() {
        ClassInsightSummary insights = data.insights();
        Button add = new Button("Add progress files…", Icons.of(Feather.USERS, 14));
        add.getStyleClass().addAll("small", "add-progress");
        add.setOnAction(event -> importProgress());
        List<Node> content = new ArrayList<>();
        if (insights.isEmpty()) {
            Label none = new Label("Learners can send you their progress from the course page. "
                    + "Add their files to see how the class is doing.");
            none.getStyleClass().add("field-hint");
            none.setWrapText(true);
            content.add(none);
        } else {
            VBox learners = new VBox();
            for (LearnerInsight learner : insights.learners()) {
                Node row = learnerRow(learner);
                if (learners.getChildren().isEmpty()) {
                    row.getStyleClass().add("first");
                }
                learners.getChildren().add(row);
            }
            content.add(learners);
            List<ItemInsight> missed = insights.items().stream().filter(i -> i.accuracy() != null && i.attempts() > 0)
                    .sorted(Comparator.comparingDouble(ItemInsight::accuracy)).limit(3).toList();
            if (!missed.isEmpty()) {
                Label heading = new Label("Most missed");
                heading.getStyleClass().add("text-overline");
                VBox list = new VBox(8, heading);
                for (ItemInsight item : missed) {
                    Label prompt = new Label(Formats.shorten(item.prompt(), 90));
                    prompt.getStyleClass().add("flag-prompt");
                    prompt.setWrapText(true);
                    prompt.setMnemonicParsing(false);
                    HBox.setHgrow(prompt, Priority.ALWAYS);
                    Label accuracy = new Label(Formats.percent(item.accuracy()));
                    accuracy.getStyleClass().addAll("insight-accuracy", "semibold");
                    accuracy.setMinWidth(Region.USE_PREF_SIZE);
                    HBox row = new HBox(12, prompt, accuracy);
                    row.setAlignment(Pos.TOP_LEFT);
                    list.getChildren().add(row);
                }
                content.add(list);
            }
        }
        content.add(add);
        String subtitle = insights.isEmpty() ? null : classLine(insights);
        insightsPanel.getChildren().setAll(StudioUi.panel("Class insights", subtitle, content.toArray(Node[]::new)));
    }

    private static String classLine(ClassInsightSummary insights) {
        double total = 0;
        int counted = 0;
        for (LearnerInsight learner : insights.learners()) {
            if (learner.accuracy() != null) {
                total += learner.accuracy();
                counted++;
            }
        }
        String learners = Formats.count(insights.learnerCount(), "learner", "learners");
        return counted == 0 ? learners : learners + " · " + Formats.percent(total / counted) + " correct on average";
    }

    private Node learnerRow(LearnerInsight learner) {
        Label name = new Label(learner.label());
        name.getStyleClass().addAll("insight-name", "medium");
        name.setMnemonicParsing(false);
        Label meta = new Label(Formats.count(learner.attempts(), "answer", "answers") + " · added "
                + Formats.ago(learner.importedAt(), host.context().clock()));
        meta.getStyleClass().add("text-caption");
        VBox text = new VBox(2, name, meta);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label accuracy = new Label(learner.accuracy() == null ? "–" : Formats.percent(learner.accuracy()));
        accuracy.getStyleClass().addAll("insight-accuracy", "semibold");
        accuracy.setMinWidth(Region.USE_PREF_SIZE);
        IconButton remove = new IconButton(Feather.X, "Remove " + learner.label() + "'s progress").small();
        remove.setOnAction(event -> act(() -> {
            workspace().sharing().removeClassInsight(learner.insightId());
            return null;
        }, "Removed " + learner.label() + "'s progress"));
        remove.getStyleClass().add("insight-remove");
        HBox row = new HBox(12, text, accuracy, remove);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("insight-row");
        return row;
    }

    private void importProgress() {
        List<Path> files = host.files().chooseProgressFiles(window());
        if (files.isEmpty()) {
            return;
        }
        run(() -> workspace().sharing().importClassProgress(courseId(), files), summary -> {
            success("Added progress from " + Formats.count(files.size(), "file", "files"));
            reload();
        }, this::error);
    }

    @Override
    protected void act(java.util.concurrent.Callable<?> work, String done) {
        run(work, ignored -> {
            success(done);
            reload();
        }, (UserMessage message) -> {
            error(message);
            reload();
        });
    }

    private static Node loading() {
        VBox left = new VBox(24, Skeleton.block(-1, 320), Skeleton.block(-1, 180));
        HBox.setHgrow(left, Priority.ALWAYS);
        VBox right = new VBox(24, Skeleton.block(SHARE_WIDTH, 200), Skeleton.block(SHARE_WIDTH, 160));
        return new HBox(28, left, right);
    }
}
