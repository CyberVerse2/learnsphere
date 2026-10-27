package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.components.ConfirmDialog;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.StatusPill;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.service.DocumentSummary;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.input.DragEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The PDFs tab: the PDFs the course is built from, each with a live status (reading, page by page; needs
 * review; ready; couldn't import with the reason and Try again or Remove), its pages, size and module. PDFs
 * are added with Add PDFs (several at once, Command/Ctrl+O) or by dropping them anywhere on the tab, into the
 * module chosen in "Add to". Each PDF can be previewed page by page, renamed, moved to another module and
 * removed.
 */
final class DocumentsSection extends StudioSection {

    /** Errors that need a changed file, not another try. */
    private static final Set<UserMessage> FIX_THE_FILE = Set.of(DocumentError.ENCRYPTED.userMessage(),
            DocumentError.TOO_LARGE.userMessage(), DocumentError.TOO_MANY_PAGES.userMessage(),
            DocumentError.DAMAGED.userMessage());

    private final VBox root = new VBox(16);
    private final ComboBox<ModuleChoice> target = new ComboBox<>();
    private final Button add = new Button("Add PDFs", Icons.of(Feather.PLUS, 14));
    private final VBox problems = new VBox(8);
    private final Label dropHint = new Label();
    private final Map<String, Row> rows = new HashMap<>();
    private final Refresher<Snapshot> refresher;
    private Snapshot shown;
    private Node dropZone;
    private boolean importing;

    /** A module PDFs can be added to; a null id is the whole course. */
    record ModuleChoice(String id, String title) {
        @Override
        public String toString() {
            return title;
        }
    }

    /** One file that couldn't be added, with why. */
    record Problem(String fileName, UserMessage message) {
    }

    private record Snapshot(List<DocumentSummary> documents, List<CourseModule> modules) {
    }

    /** The live parts of a PDF's row. */
    private record Row(DocumentSummary document, StatusPill pill, ProgressBarSlim bar) {
    }

    DocumentsSection(StudioHost host) {
        super(host);
        root.getStyleClass().add("studio-documents");
        target.getStyleClass().add("document-target");
        target.setAccessibleText("Add PDFs to");
        target.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(ModuleChoice item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.title());
            }
        });
        target.valueProperty().addListener((obs, old, value) -> updateDropHint());
        add.getStyleClass().addAll("primary", "add-pdfs");
        add.setMinWidth(Region.USE_PREF_SIZE);
        add.setOnAction(event -> choose());
        problems.getStyleClass().add("import-problems");
        root.getChildren().setAll(loading());
        refresher = new Refresher<>(disposer, () -> new Snapshot(workspace().documents().list(courseId()),
                workspace().courses().modules(courseId())), this::render,
                message -> root.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.SEARCH,
                        "The PDFs couldn't load", message.text()).primary("Try again", this::reload)));
        acceptDrops(root);
    }

    @Override
    Region root() {
        return root;
    }

    @Override
    void onShown() {
        reload();
        on(DocumentChanged.class, this::onDocumentChanged);
        on(CourseChanged.class, event -> {
            if (courseId().equals(event.courseId())) {
                reload();
            }
        });
        Shortcuts.install(root, new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN), this::choose, disposer);
    }

    private void reload() {
        refresher.request();
    }

    private void onDocumentChanged(DocumentChanged event) {
        if (!courseId().equals(event.courseId())) {
            return;
        }
        Row row = rows.get(event.documentId());
        boolean working = StatusPill.statusOf(event.status()) == StatusPill.Status.WORKING;
        if (event.type() == ChangeType.UPDATED && row != null && working && row.bar() != null) {
            // Page-by-page progress: update the row in place.
            row.pill().show(event);
            row.bar().setProgress(event.progress());
            return;
        }
        reload();
    }

    // -------------------------------------------------------------------------------------------------
    // Rendering
    // -------------------------------------------------------------------------------------------------

    private void render(Snapshot snapshot) {
        shown = snapshot;
        ModuleChoice chosen = target.getValue();
        List<ModuleChoice> choices = new ArrayList<>();
        choices.add(new ModuleChoice(null, "Whole course"));
        snapshot.modules().forEach(m -> choices.add(new ModuleChoice(m.id(), m.title())));
        target.getItems().setAll(choices);
        target.setValue(choices.stream().filter(c -> chosen != null && Objects.equals(c.id(), chosen.id()))
                .findFirst().orElse(choices.getFirst()));

        rows.clear();
        if (snapshot.documents().isEmpty()) {
            dropZone = emptyDropZone();
            root.getChildren().setAll(dropZone);
            if (!problems.getChildren().isEmpty()) {
                root.getChildren().addFirst(problems);
            }
            return;
        }
        VBox list = new VBox();
        list.getStyleClass().add("document-list");
        for (DocumentSummary document : snapshot.documents()) {
            Node row = row(document);
            if (list.getChildren().isEmpty()) {
                row.getStyleClass().add("first");
            }
            list.getChildren().add(row);
        }
        VBox panel = StudioUi.panel(null, null, list);
        panel.getStyleClass().add("tight");
        dropZone = slimDropZone();
        root.getChildren().setAll(toolbar(snapshot.documents()), problems, panel, dropZone);
        updateDropHint();
    }

    private Node toolbar(List<DocumentSummary> documents) {
        long pages = documents.stream().mapToLong(DocumentSummary::pageCount).sum();
        long working = documents.stream().filter(DocumentSummary::isProcessing).count();
        List<String> parts = new ArrayList<>();
        parts.add(Formats.count(documents.size(), "PDF", "PDFs"));
        parts.add(Formats.count(pages, "page", "pages"));
        if (working > 0) {
            parts.add(working + " importing");
        }
        Label summary = new Label(String.join(" · ", parts));
        summary.getStyleClass().add("text-muted");
        summary.setMinWidth(0);
        Label to = new Label("Add to");
        to.getStyleClass().add("field-label");
        to.setLabelFor(target);
        to.setMinWidth(Region.USE_PREF_SIZE);
        HBox bar = new HBox(12, summary, StudioUi.grow(), to, target, add);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("documents-toolbar");
        return bar;
    }

    private HBox row(DocumentSummary document) {
        StatusPill pill = StatusPill.forDocument(document);
        pill.getStyleClass().add("document-pill");
        boolean failed = document.status() == DocumentStatus.FAILED;
        boolean attention = document.status() == DocumentStatus.NEEDS_REVIEW;

        StackPane icon = new StackPane(Icons.of(failed ? Feather.ALERT_TRIANGLE : attention ? Feather.EYE : Feather.FILE_TEXT, 18));
        icon.getStyleClass().add("document-icon");

        Label title = new Label(document.title());
        title.getStyleClass().addAll("document-title", "medium");
        title.setMnemonicParsing(false);
        title.setWrapText(true);
        Label meta = new Label(StudioFormats.documentMeta(document));
        meta.getStyleClass().add("document-meta");
        meta.setMnemonicParsing(false);
        VBox text = new VBox(3, title, meta);
        if (failed && document.error() != null) {
            Label reason = new Label(document.error().text());
            reason.getStyleClass().add("document-reason");
            reason.setWrapText(true);
            text.getChildren().add(reason);
        } else if (attention) {
            Label reason = new Label(Formats.count(document.pagesNeedingReview(), "scanned page needs", "scanned pages need")
                    + " checking before questions can be made from this PDF.");
            reason.getStyleClass().add("document-progress");
            reason.setWrapText(true);
            text.getChildren().add(reason);
        }
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);

        VBox status = new VBox(8, pill);
        status.setAlignment(Pos.CENTER_RIGHT);
        status.setMinWidth(Region.USE_PREF_SIZE);
        ProgressBarSlim bar = null;
        if (StatusPill.statusOf(document.status()) == StatusPill.Status.WORKING) {
            bar = new ProgressBarSlim();
            bar.getStyleClass().add("document-bar");
            bar.setProgressNow(document.progress());
            bar.setPrefWidth(200);
            bar.setMaxWidth(200);
            status.getChildren().add(bar);
            status.setMinWidth(200);
        }

        HBox actions = new HBox(8);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setMinWidth(Region.USE_PREF_SIZE);
        if (failed) {
            if (!FIX_THE_FILE.contains(document.error())) {
                Button retry = new Button("Try again", Icons.of(Feather.REFRESH_CW, 14));
                retry.getStyleClass().addAll("small", "document-retry");
                retry.setOnAction(event -> {
                    retry.setDisable(true);
                    act(() -> {
                        workspace().documents().retry(document.id());
                        return null;
                    }, null);
                });
                actions.getChildren().add(retry);
            }
            Button remove = new Button("Remove");
            remove.getStyleClass().addAll("small", "quiet", "danger-text", "document-remove");
            remove.setOnAction(event -> remove(document));
            actions.getChildren().add(remove);
        } else if (attention) {
            Button check = new Button("Check pages");
            check.getStyleClass().addAll("small", "document-check");
            check.setOnAction(event -> host.tabs().open(StudioTab.PAGES, document.id()));
            actions.getChildren().add(check);
        } else if (document.pageCount() > 0) {
            Button preview = new Button("Preview");
            preview.getStyleClass().addAll("small", "document-preview");
            preview.setOnAction(event -> PagePreviewDialog.show(root, workspace(), document));
            actions.getChildren().add(preview);
        }
        IconButton more = new IconButton(Feather.MORE_HORIZONTAL, "More for " + document.title()).small();
        more.getStyleClass().add("document-more");
        more.setOnAction(event -> menu(document).show(more, Side.BOTTOM, 0, 4));
        actions.getChildren().add(more);

        HBox row = new HBox(14, icon, text, status, actions);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("document-row");
        if (failed) {
            row.getStyleClass().add("failed");
        } else if (attention) {
            row.getStyleClass().add("attention");
        }
        row.setUserData(document.id());
        rows.put(document.id(), new Row(document, pill, bar));
        return row;
    }

    private ContextMenu menu(DocumentSummary document) {
        MenuItem rename = new MenuItem("Rename…");
        rename.setOnAction(event -> rename(document));
        Menu move = new Menu("Move to");
        List<ModuleChoice> choices = new ArrayList<>(target.getItems());
        for (ModuleChoice choice : choices) {
            MenuItem item = new MenuItem(choice.title());
            item.setMnemonicParsing(false);
            item.setDisable(Objects.equals(choice.id(), document.moduleId()));
            item.setOnAction(event -> act(() -> {
                workspace().documents().moveToModule(document.id(), choice.id());
                return null;
            }, "Moved to " + choice.title()));
            move.getItems().add(item);
        }
        MenuItem preview = new MenuItem("Preview");
        preview.setDisable(document.pageCount() == 0 || document.status() == DocumentStatus.FAILED);
        preview.setOnAction(event -> PagePreviewDialog.show(root, workspace(), document));
        MenuItem remove = new MenuItem("Remove…");
        remove.setOnAction(event -> remove(document));
        return new ContextMenu(preview, rename, move, new SeparatorMenuItem(), remove);
    }

    // -------------------------------------------------------------------------------------------------
    // Adding PDFs
    // -------------------------------------------------------------------------------------------------

    private void choose() {
        if (importing || shown == null) {
            return;
        }
        List<Path> chosen = host.files().choosePdfs(window());
        if (!chosen.isEmpty()) {
            importPdfs(chosen);
        }
    }

    /** Adds {@code files} to the module chosen in "Add to"; files that can't be added are listed with why. */
    void importPdfs(List<Path> files) {
        if (files.isEmpty() || importing) {
            return;
        }
        ModuleChoice choice = target.getValue();
        String moduleId = choice == null ? null : choice.id();
        String where = choice == null || choice.id() == null ? "the course" : choice.title();
        importing = true;
        add.setDisable(true);
        add.setText("Adding…");
        run(() -> {
            List<Problem> failed = new ArrayList<>();
            int added = 0;
            for (Path file : files) {
                try {
                    workspace().documents().importPdf(courseId(), moduleId, file);
                    added++;
                } catch (RuntimeException e) {
                    failed.add(new Problem(file.getFileName().toString(), LearnSphereException.userMessageOf(e)));
                }
            }
            return new ImportReport(added, failed);
        }, report -> {
            finishImporting();
            showProblems(report.problems());
            if (report.added() > 0) {
                success("Added " + Formats.count(report.added(), "PDF", "PDFs") + " to " + where);
            }
        }, message -> {
            finishImporting();
            error(message);
        });
    }

    private record ImportReport(int added, List<Problem> problems) {
    }

    private void finishImporting() {
        importing = false;
        add.setDisable(false);
        add.setText("Add PDFs");
    }

    private void showProblems(List<Problem> list) {
        problems.getChildren().clear();
        if (list.isEmpty()) {
            return;
        }
        VBox lines = new VBox(4);
        Label heading = new Label(list.size() == 1 ? "This PDF wasn't added" : list.size() + " PDFs weren't added");
        heading.getStyleClass().add("semibold");
        lines.getChildren().add(heading);
        for (Problem problem : list) {
            Label line = new Label(problem.fileName() + ": " + problem.message().text());
            line.setWrapText(true);
            line.getStyleClass().add("import-problem");
            lines.getChildren().add(line);
        }
        HBox.setHgrow(lines, Priority.ALWAYS);
        IconButton dismiss = new IconButton(Feather.X, "Dismiss").small();
        dismiss.setOnAction(event -> problems.getChildren().clear());
        HBox note = new HBox(12, Icons.of(Feather.ALERT_CIRCLE, 16), lines, dismiss);
        note.getStyleClass().addAll("studio-note", "danger");
        note.setAlignment(Pos.TOP_LEFT);
        problems.getChildren().setAll(note);
        if (!root.getChildren().contains(problems)) {
            root.getChildren().addFirst(problems);
        }
    }

    /** The lines of the "weren't added" note (tests). */
    List<String> problemLines() {
        return problems.lookupAll(".import-problem").stream().map(n -> ((Label) n).getText()).toList();
    }

    private void acceptDrops(Region area) {
        area.addEventHandler(DragEvent.DRAG_OVER, event -> {
            if (!importing && shown != null && !pdfs(event).isEmpty()) {
                event.acceptTransferModes(TransferMode.COPY);
                highlight(true);
                event.consume();
            }
        });
        area.addEventHandler(DragEvent.DRAG_EXITED, event -> highlight(false));
        area.addEventHandler(DragEvent.DRAG_DROPPED, event -> {
            List<Path> files = pdfs(event);
            highlight(false);
            if (!files.isEmpty()) {
                event.setDropCompleted(true);
                event.consume();
                importPdfs(files);
            }
        });
    }

    private static List<Path> pdfs(DragEvent event) {
        if (!event.getDragboard().hasFiles()) {
            return List.of();
        }
        return event.getDragboard().getFiles().stream().filter(File::isFile)
                .filter(f -> f.getName().toLowerCase(Locale.ROOT).endsWith(".pdf")).map(File::toPath).toList();
    }

    private void highlight(boolean on) {
        if (dropZone == null) {
            return;
        }
        if (on && !dropZone.getStyleClass().contains("drag-over")) {
            dropZone.getStyleClass().add("drag-over");
        } else if (!on) {
            dropZone.getStyleClass().remove("drag-over");
        }
    }

    private Node emptyDropZone() {
        EmptyState empty = new EmptyState(EmptyIllustration.Kind.DOCUMENTS, "Add the PDFs this course is built from",
                "Drop PDFs here or choose them. LearnSphere reads each one so learners can ask about it and you can make questions from it.")
                .primary("Add PDFs", this::choose);
        empty.getStyleClass().add("documents-empty");
        Label to = new Label("Add to");
        to.getStyleClass().add("field-label");
        to.setLabelFor(target);
        HBox chooser = new HBox(12, to, target);
        chooser.setAlignment(Pos.CENTER);
        boolean modules = target.getItems().size() > 1;
        VBox zone = new VBox(16, empty);
        if (modules) {
            zone.getChildren().add(chooser);
        }
        zone.setAlignment(Pos.CENTER);
        zone.getStyleClass().addAll("drop-zone", "large");
        return zone;
    }

    private Node slimDropZone() {
        var icon = Icons.of(Feather.UPLOAD, 18);
        icon.getStyleClass().add("drop-zone-icon");
        Label title = new Label("Drop PDFs here");
        title.getStyleClass().addAll("drop-zone-title", "medium");
        dropHint.getStyleClass().add("field-hint");
        dropHint.setWrapText(true);
        VBox text = new VBox(2, title, dropHint);
        HBox.setHgrow(text, Priority.ALWAYS);
        HBox zone = new HBox(16, icon, text);
        zone.setAlignment(Pos.CENTER_LEFT);
        zone.getStyleClass().add("drop-zone");
        return zone;
    }

    private void updateDropHint() {
        ModuleChoice choice = target.getValue();
        String where = choice == null || choice.id() == null ? "the whole course" : choice.title();
        dropHint.setText("They're added to " + where + ". Long PDFs take a few minutes to read.");
    }

    // -------------------------------------------------------------------------------------------------
    // Renaming and removing
    // -------------------------------------------------------------------------------------------------

    private void rename(DocumentSummary document) {
        TextField name = new TextField(document.title());
        name.getStyleClass().add("document-rename-field");
        StudioUi.limit(name, 200);
        VBox content = StudioUi.field("Name", name, "Learners see this name when an answer cites the PDF.");
        StudioDialog.of("Rename PDF")
                .content(content)
                .confirm("Rename")
                .focus(name)
                .onConfirm(dialog -> {
                    String text = StudioUi.text(name);
                    if (text.isEmpty()) {
                        dialog.showError("Enter a name for this PDF.");
                        return;
                    }
                    dialog.setBusy(true);
                    run(() -> {
                        workspace().documents().rename(document.id(), text);
                        return null;
                    }, ignored -> dialog.close(true), dialog::showError);
                })
                .show(root);
        name.selectAll();
    }

    private void remove(DocumentSummary document) {
        if (document.status() == DocumentStatus.FAILED) {
            removeNow(document);
            return;
        }
        ConfirmDialog.of("Remove “" + document.title() + "”?",
                        "Questions made from it stay in the course, without the passage they came from.", "Remove PDF")
                .danger()
                .show(root)
                .thenAccept(confirmed -> {
                    if (confirmed) {
                        removeNow(document);
                    }
                });
    }

    private void removeNow(DocumentSummary document) {
        act(() -> {
            workspace().documents().remove(document.id());
            return null;
        }, "Removed “" + document.title() + "”");
    }

    private static Node loading() {
        VBox list = new VBox(16);
        list.getStyleClass().add("studio-panel");
        for (int i = 0; i < 3; i++) {
            HBox row = new HBox(14, Skeleton.block(40, 48), new VBox(8, Skeleton.line(220), Skeleton.line(160)));
            row.setAlignment(Pos.CENTER_LEFT);
            list.getChildren().add(row);
        }
        return new VBox(16, Skeleton.block(-1, 40), list);
    }
}
