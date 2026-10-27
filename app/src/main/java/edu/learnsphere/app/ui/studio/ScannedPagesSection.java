package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.ConfirmDialog;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.StatusPill;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.RenderedPage;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The Scanned pages tab: pages the study assistant read from scanned images, one at a time, the page image
 * beside the text read from it. The creator corrects the text and approves the page (Command/Ctrl+Enter), or
 * approves every page of the PDF at once. Once a PDF's last page is approved, its import continues; the tab
 * shows its live status and offers the next PDF with pages to check.
 */
final class ScannedPagesSection extends StudioSection {

    /** The note above every scanned page. */
    static final String NOTE = "These pages were read from a scanned image. Check the text before learners rely on it.";

    private static final double IMAGE_WIDTH = 440;
    private static final double SCALE = 2;
    private static final int MAX_TEXT = 100_000;

    private final String requestedDocumentId;
    private final VBox root = new VBox(20);
    private final VBox body = new VBox(20);
    private final ComboBox<DocumentSummary> documents = new ComboBox<>();
    private final FlowPane ribbon = new FlowPane();
    private final ToggleGroup ribbonGroup = new ToggleGroup();
    private final ImageView image = new ImageView();
    private final StackPane imageFrame = new StackPane();
    private final Label pageTitle = new Label();
    private final TextArea text = new TextArea();
    private final Button approve = new Button("Approve page", Icons.of(Feather.CHECK, 14));
    private final Button approveAll = new Button();
    private final IconButton previous = new IconButton(Feather.CHEVRON_LEFT, "Previous page");
    private final IconButton next = new IconButton(Feather.CHEVRON_RIGHT, "Next page");
    private final Set<Integer> approved = new LinkedHashSet<>();
    private List<DocumentSummary> waiting = List.of();
    private DocumentSummary document;
    private List<Integer> pages = List.of();
    private int current;
    private String loadedText = "";
    private boolean busy;
    private StatusPill finishedPill;
    private String finishedDocumentId;

    ScannedPagesSection(StudioHost host, String documentId) {
        super(host);
        this.requestedDocumentId = documentId;
        root.getStyleClass().add("studio-pages");
        documents.getStyleClass().add("pages-document");
        documents.setAccessibleText("PDF with pages to check");
        documents.setCellFactory(list -> documentCell());
        documents.setButtonCell(documentCell());
        documents.setOnAction(event -> {
            DocumentSummary chosen = documents.getValue();
            if (chosen != null && (document == null || !chosen.id().equals(document.id()))) {
                openDocument(chosen);
            }
        });
        ribbon.getStyleClass().add("page-ribbon");
        ribbon.setHgap(6);
        ribbon.setVgap(6);
        image.setPreserveRatio(true);
        image.setFitWidth(IMAGE_WIDTH);
        image.setSmooth(true);
        imageFrame.getStyleClass().add("scanned-image");
        imageFrame.setMinWidth(IMAGE_WIDTH + 40);
        imageFrame.setMaxWidth(IMAGE_WIDTH + 40);
        imageFrame.setAlignment(Pos.TOP_CENTER);
        pageTitle.getStyleClass().addAll("text-title", "scanned-page-title");
        text.getStyleClass().addAll("page-text", "scanned-text");
        text.setWrapText(true);
        text.setPrefRowCount(18);
        text.setAccessibleText("Text read from the page");
        StudioUi.limit(text, MAX_TEXT);
        text.textProperty().addListener((obs, old, value) -> updateApproveLabel());
        approve.getStyleClass().addAll("primary", "approve-page");
        approve.setOnAction(event -> approveCurrent());
        approveAll.getStyleClass().add("approve-all");
        approveAll.setOnAction(event -> confirmApproveAll());
        previous.small();
        next.small();
        previous.setOnAction(event -> show(current - 1));
        next.setOnAction(event -> show(current + 1));
        root.getChildren().setAll(loading());
    }

    @Override
    Region root() {
        return root;
    }

    @Override
    void onShown() {
        loadWaiting(requestedDocumentId);
        on(DocumentChanged.class, this::onDocumentChanged);
        Shortcuts.install(root, new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN), () -> {
            if (document != null && !busy && !pages.isEmpty()) {
                approveCurrent();
            }
        }, disposer);
    }

    private void onDocumentChanged(DocumentChanged event) {
        if (!courseId().equals(event.courseId())) {
            return;
        }
        if (finishedPill != null && event.documentId().equals(finishedDocumentId)) {
            finishedPill.show(event);
        }
        if (document == null && finishedPill == null) {
            // Waiting with nothing to check: a PDF may have just reached its scanned pages.
            loadWaiting(null);
        }
    }

    @Override
    boolean hasUnsavedChanges() {
        return document != null && !text.isDisabled() && !Objects.equals(text.getText(), loadedText);
    }

    @Override
    String unsavedChangesText() {
        return "Your changes to this page's text aren't saved.";
    }

    // -------------------------------------------------------------------------------------------------
    // Loading
    // -------------------------------------------------------------------------------------------------

    private void loadWaiting(String preferredId) {
        run(() -> workspace().documents().list(courseId()).stream().filter(d -> d.pagesNeedingReview() > 0).toList(),
                list -> {
                    waiting = list;
                    if (list.isEmpty()) {
                        document = null;
                        if (finishedPill == null) {
                            showNothingToCheck();
                        }
                        return;
                    }
                    DocumentSummary chosen = list.stream().filter(d -> d.id().equals(preferredId)).findFirst()
                            .orElse(list.getFirst());
                    finishedPill = null;
                    finishedDocumentId = null;
                    buildWorkspace();
                    documents.getItems().setAll(list);
                    documents.setValue(chosen);
                    openDocument(chosen);
                }, message -> root.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.SEARCH,
                        "The scanned pages couldn't load", message.text()).primary("Try again", () -> loadWaiting(preferredId))));
    }

    private void openDocument(DocumentSummary chosen) {
        document = chosen;
        approved.clear();
        pages = List.of();
        body.setDisable(true);
        run(() -> workspace().documents().pagesNeedingReview(chosen.id()).stream().map(DocumentPage::pageNumber).toList(),
                numbers -> {
                    body.setDisable(false);
                    if (!Objects.equals(document, chosen)) {
                        return;
                    }
                    if (numbers.isEmpty()) {
                        loadWaiting(null);
                        return;
                    }
                    pages = numbers;
                    buildRibbon();
                    show(0);
                }, message -> {
                    body.setDisable(false);
                    error(message);
                });
    }

    private void show(int index) {
        if (index < 0 || index >= pages.size() || document == null) {
            return;
        }
        current = index;
        int number = pages.get(index);
        pageTitle.setText("Page " + number);
        previous.setDisable(index == 0);
        next.setDisable(index == pages.size() - 1);
        selectChip(number);
        text.setDisable(true);
        ActivityIndicator spinner = new ActivityIndicator(24);
        imageFrame.getChildren().setAll(image, spinner);
        image.setOpacity(0.35);
        String documentId = document.id();
        run(() -> new Loaded(workspace().documents().page(documentId, number),
                        workspace().documents().renderPage(documentId, number, SCALE)),
                loaded -> {
                    if (document == null || !documentId.equals(document.id()) || current != index) {
                        return;
                    }
                    loadedText = loaded.page().text();
                    text.setText(loadedText);
                    text.setDisable(false);
                    text.positionCaret(0);
                    image.setImage(new Image(new ByteArrayInputStream(loaded.image().png())));
                    image.setOpacity(1);
                    image.setAccessibleText("Scan of page " + number);
                    imageFrame.getChildren().setAll(image);
                    updateApproveLabel();
                }, message -> {
                    Label failed = new Label(message.text());
                    failed.getStyleClass().add("text-muted");
                    failed.setWrapText(true);
                    imageFrame.getChildren().setAll(failed);
                    text.setDisable(false);
                });
    }

    private record Loaded(DocumentPage page, RenderedPage image) {
    }

    // -------------------------------------------------------------------------------------------------
    // Layout
    // -------------------------------------------------------------------------------------------------

    private void buildWorkspace() {
        HBox note = StudioUi.note(Feather.EYE, NOTE, "warning");
        note.getStyleClass().add("scanned-note");

        Label from = new Label("PDF");
        from.getStyleClass().add("field-label");
        from.setLabelFor(documents);
        from.setMinWidth(Region.USE_PREF_SIZE);
        HBox picker = new HBox(12, from, documents, StudioUi.grow(), approveAll);
        picker.setAlignment(Pos.CENTER_LEFT);
        picker.getStyleClass().add("pages-picker");

        Chip ai = new Chip("Read by AI from a scanned page", Tone.ACCENT, Feather.FEATHER);
        ai.getStyleClass().add("ai-read-chip");
        HBox titleRow = new HBox(12, pageTitle, ai, StudioUi.grow(), previous, next);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        Label hint = new Label("Fix anything that was read wrongly. Lines that start with # are headings.");
        hint.getStyleClass().add("field-hint");
        hint.setWrapText(true);
        VBox.setVgrow(text, Priority.ALWAYS);
        HBox actions = new HBox(12, hint, StudioUi.grow(), approve);
        actions.setAlignment(Pos.CENTER_LEFT);
        approve.setMinWidth(Region.USE_PREF_SIZE);
        VBox editor = new VBox(12, titleRow, text, actions);
        editor.getStyleClass().add("scanned-editor");
        editor.setMinWidth(0);
        HBox.setHgrow(editor, Priority.ALWAYS);

        HBox layout = new HBox(28, imageFrame, editor);
        layout.getStyleClass().add("scanned-layout");
        body.getChildren().setAll(picker, ribbon, layout);
        root.getChildren().setAll(note, body);
    }

    private void buildRibbon() {
        ribbon.getChildren().clear();
        ribbonGroup.getToggles().clear();
        for (int i = 0; i < pages.size(); i++) {
            int index = i;
            int number = pages.get(i);
            ToggleButton chip = new ToggleButton("p. " + number);
            chip.getStyleClass().setAll("page-chip");
            chip.setUserData(number);
            chip.setToggleGroup(ribbonGroup);
            chip.setAccessibleText("Page " + number);
            chip.setOnAction(event -> {
                chip.setSelected(true);
                show(index);
            });
            ribbon.getChildren().add(chip);
        }
        updateApproveAll();
    }

    private void selectChip(int number) {
        for (var toggle : ribbonGroup.getToggles()) {
            if (Objects.equals(toggle.getUserData(), number)) {
                toggle.setSelected(true);
            }
        }
    }

    private void markDone(int number) {
        approved.add(number);
        for (Node node : ribbon.getChildren()) {
            if (node instanceof ToggleButton chip && Objects.equals(chip.getUserData(), number)) {
                if (!chip.getStyleClass().contains("done")) {
                    chip.getStyleClass().add("done");
                }
                chip.setGraphic(Icons.of(Feather.CHECK, 12));
                chip.setAccessibleText("Page " + number + ", approved");
            }
        }
        updateApproveAll();
    }

    private void updateApproveLabel() {
        boolean edited = !Objects.equals(text.getText(), loadedText);
        boolean done = !pages.isEmpty() && approved.contains(pages.get(current));
        approve.setText(edited ? "Save and approve" : done ? "Approved" : "Approve page");
        approve.setDisable(busy || (done && !edited));
    }

    private void updateApproveAll() {
        int left = (int) pages.stream().filter(n -> !approved.contains(n)).count();
        approveAll.setText(left <= 1 ? "Approve all" : "Approve all " + left + " pages");
        approveAll.setDisable(busy || left == 0);
    }

    private void showNothingToCheck() {
        root.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.CAUGHT_UP, "No pages to check",
                "When a PDF has scanned pages, the text read from them appears here so you can check it before learners rely on it.")
                .secondary("Go to PDFs", () -> host.tabs().open(StudioTab.DOCUMENTS, null)));
    }

    private void showFinished(DocumentSummary finished) {
        document = null;
        finishedDocumentId = finished.id();
        finishedPill = new StatusPill(StatusPill.Status.WORKING, "Preparing for questions…");
        finishedPill.getStyleClass().add("finished-pill");
        HBox status = new HBox(12, finishedPill);
        status.setAlignment(Pos.CENTER);
        EmptyState done = new EmptyState(EmptyIllustration.Kind.CAUGHT_UP, "Every page is checked",
                "“" + finished.title() + "” continues importing with the text you approved.");
        List<DocumentSummary> others = waiting.stream().filter(d -> !d.id().equals(finished.id())).toList();
        if (!others.isEmpty()) {
            DocumentSummary nextDocument = others.getFirst();
            done.primary("Check “" + Formats.shorten(nextDocument.title(), 40) + "”", () -> loadWaiting(nextDocument.id()));
        } else {
            done.secondary("Go to PDFs", () -> host.tabs().open(StudioTab.DOCUMENTS, null));
        }
        done.getChildren().add(1, status);
        root.getChildren().setAll(done);
        run(() -> workspace().documents().get(finished.id()), latest -> {
            if (finishedPill != null && finished.id().equals(finishedDocumentId)) {
                finishedPill.show(latest);
            }
        }, ignored -> { });
    }

    // -------------------------------------------------------------------------------------------------
    // Approving
    // -------------------------------------------------------------------------------------------------

    /** Approves the page on screen, saving its text first when it was changed. */
    void approveCurrent() {
        if (document == null || pages.isEmpty() || busy) {
            return;
        }
        int number = pages.get(current);
        String edited = text.getText() == null ? "" : text.getText();
        boolean changed = !edited.equals(loadedText);
        if (approved.contains(number) && !changed) {
            return;
        }
        DocumentSummary doc = document;
        setBusy(true);
        run(() -> {
            if (changed) {
                workspace().documents().editPageText(doc.id(), number, edited);
            } else {
                workspace().documents().approvePages(doc.id(), List.of(number));
            }
            return workspace().documents().pagesNeedingReview(doc.id()).size();
        }, left -> {
            setBusy(false);
            loadedText = edited;
            markDone(number);
            if (left == 0) {
                showFinished(doc);
                return;
            }
            int nextIndex = nextUnapproved();
            if (nextIndex >= 0) {
                show(nextIndex);
            } else {
                updateApproveLabel();
            }
        }, message -> {
            setBusy(false);
            error(message);
        });
    }

    private int nextUnapproved() {
        for (int step = 1; step <= pages.size(); step++) {
            int index = (current + step) % pages.size();
            if (!approved.contains(pages.get(index))) {
                return index;
            }
        }
        return -1;
    }

    private void confirmApproveAll() {
        if (document == null || busy) {
            return;
        }
        List<Integer> left = pages.stream().filter(n -> !approved.contains(n)).toList();
        if (left.isEmpty()) {
            return;
        }
        ConfirmDialog.of("Approve " + (left.size() == 1 ? "the last page" : "all " + left.size() + " pages") + "?",
                        "Pages you haven't opened keep the text as it was read. Learners' answers can quote it.",
                        "Approve all")
                .show(root)
                .thenAccept(confirmed -> {
                    if (confirmed) {
                        approveAll(left);
                    }
                });
    }

    /** Approves {@code numbers} of the PDF on screen, saving the open page's changed text first. */
    void approveAll(List<Integer> numbers) {
        DocumentSummary doc = document;
        if (doc == null || busy) {
            return;
        }
        int open = pages.isEmpty() ? -1 : pages.get(current);
        String edited = text.getText() == null ? "" : text.getText();
        boolean changed = open > 0 && !edited.equals(loadedText);
        List<Integer> rest = new ArrayList<>(numbers);
        setBusy(true);
        run(() -> {
            if (changed) {
                workspace().documents().editPageText(doc.id(), open, edited);
                rest.remove(Integer.valueOf(open));
            }
            if (!rest.isEmpty()) {
                workspace().documents().approvePages(doc.id(), rest);
            }
            return null;
        }, ignored -> {
            setBusy(false);
            numbers.forEach(this::markDone);
            showFinished(doc);
        }, message -> {
            setBusy(false);
            error(message);
        });
    }

    private void setBusy(boolean value) {
        busy = value;
        text.setEditable(!value);
        updateApproveLabel();
        updateApproveAll();
    }

    private ListCell<DocumentSummary> documentCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(DocumentSummary item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                        : item.title() + " · " + Formats.count(item.pagesNeedingReview(), "page", "pages") + " to check");
            }
        };
    }

    private static Node loading() {
        HBox layout = new HBox(28, Skeleton.block(IMAGE_WIDTH + 40, 560),
                new VBox(12, Skeleton.block(200, 30), Skeleton.block(-1, 420), Skeleton.block(140, 40)));
        HBox.setHgrow(layout.getChildren().get(1), Priority.ALWAYS);
        return new VBox(20, Skeleton.block(-1, 48), layout);
    }

    /** The page on screen, or 0 (tests). */
    int pageOnScreen() {
        return pages.isEmpty() || document == null ? 0 : pages.get(current);
    }

    /** The text area with the page's text (tests). */
    TextArea textArea() {
        return text;
    }
}
