package edu.learnsphere.app.ui.studio.review;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Overlays;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.SegmentedControl;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.ToggleChip;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.events.JobProgress;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.GenerationRequest;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.ItemDetail;
import edu.learnsphere.core.service.ItemFilter;
import edu.learnsphere.core.service.ItemSummary;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.SourcePassage;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.util.Cancellable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBoxBase;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

/**
 * The Studio's review queue for one course: the course's key concepts, generating questions with the study
 * assistant, and every question to review, approve, edit, regenerate or reject, each beside the passage it
 * was drafted from.
 *
 * <p>Filters narrow the queue by module, concept, type, who wrote it and open flags; the status tabs count
 * what matches. Keys work on the outlined card: A approves, E edits, R rejects, J and K move, X selects.
 * Several drafts can be approved at once, with an undo. The study assistant's work (finding concepts,
 * drafting, rewriting a question) runs in the background and reports progress in place, so the queue never
 * waits on it.
 */
public final class ReviewQueueTab implements View {

    /** Questions shown before "Show more". */
    static final int PAGE_SIZE = 20;
    /** The review queue's stylesheet (also added to its dialogs, which sit above the page). */
    static final String STYLESHEET = Objects.requireNonNull(ReviewQueueTab.class.getResource("review.css"),
            "review.css").toExternalForm();
    private static final String KEY = ReviewQueueTab.class.getName();
    private static final List<ItemStatus> STATUSES =
            List.of(ItemStatus.DRAFT, ItemStatus.APPROVED, ItemStatus.REJECTED, ItemStatus.RETIRED);

    /** What the queue shows. */
    record Query(ItemStatus status, String moduleId, String conceptId, ItemType type, ItemOrigin origin,
                 boolean flagged, int limit) {

        ItemFilter filter(String courseId) {
            ItemFilter filter = ItemFilter.forCourse(courseId).withModule(moduleId).withConcept(conceptId)
                    .withOrigin(origin).withFlaggedOnly(flagged);
            return type == null ? filter : filter.withTypes(type);
        }

        boolean filtered() {
            return moduleId != null || conceptId != null || type != null || origin != null || flagged;
        }
    }

    /** Everything one load brings back. */
    record Snapshot(Query query, List<CourseModule> modules, Set<String> readyModuleIds, Set<String> pdfModuleIds,
                    boolean courseReady,
                    boolean anyPdf, List<Concept> concepts, Set<String> extractedConceptIds,
                    Map<ItemStatus, Integer> counts, int courseTotal, List<ItemDetail> items, List<JobUpdate> activeJobs) {

        int matching() {
            return counts.getOrDefault(query.status(), 0);
        }

        String moduleTitle(String moduleId) {
            for (CourseModule module : modules) {
                if (module.id().equals(moduleId)) {
                    return module.title();
                }
            }
            return null;
        }
    }

    /** An entry of a filter list; a null value means "any". */
    record Choice(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final AppContext context;
    private final Navigator navigator;
    private final String courseId;
    private final Disposer disposer = new Disposer();
    private final VBox root = new VBox(20);

    private final ConceptsPanel concepts;
    private final SegmentedControl<ItemStatus> statusControl =
            new SegmentedControl<>(STATUSES, ReviewLabels::status);
    private final Button newQuestion = new Button("New question", Icons.of(Feather.EDIT_3, 16));
    private final Button generate = new Button("Generate questions", Icons.of(Feather.FEATHER, 16));
    private final ChoiceBox<Choice> moduleFilter = new ChoiceBox<>();
    private final ChoiceBox<Choice> conceptFilter = new ChoiceBox<>();
    private final ChoiceBox<Choice> typeFilter = new ChoiceBox<>();
    private final ChoiceBox<Choice> originFilter = new ChoiceBox<>();
    private final ToggleChip flaggedFilter = new ToggleChip("Flagged", Icons.of(Feather.FLAG, 14));
    private final Button clearFilters = new Button("Clear filters");
    private final HBox banner = new HBox(12);
    private final HBox selectionBar = new HBox(12);
    private final CheckBox selectAll = new CheckBox("Select all");
    private final Label selectedCount = new Label();
    private final Button approveSelected = new Button("Approve selected", Icons.of(Feather.CHECK_SQUARE, 14));
    private final Label keyHint = new Label();
    private final VBox list = new VBox(16);
    private final VBox content = new VBox(16);
    private final HBox footer = new HBox(12);
    private final Label showing = new Label();
    private final Button showMore = new Button("Show more");

    private Snapshot data;
    private boolean everLoaded;
    private Map<String, ReviewCard> cards = new LinkedHashMap<>();
    private String currentId;
    private int currentIndex;
    private final Set<String> selected = new LinkedHashSet<>();
    private final Set<String> fresh = new HashSet<>();
    private final Map<String, JobUpdate> regenerating = new HashMap<>();
    private final Map<String, String> cardErrors = new HashMap<>();
    private final Map<String, String> conceptJobs = new HashMap<>();
    private JobUpdate generation;
    private String generationResult;
    private GenerationRequest lastRequest;
    private int limit = PAGE_SIZE;
    private int loadNumber;
    private Cancellable pendingLoad;
    private final PauseTransition reloadDelay = new PauseTransition(Duration.millis(150));
    private boolean quiet;
    private boolean shown;
    private boolean disposed;
    private Node lastFocus;
    private Scene keyScene;
    private final EventHandler<KeyEvent> keys = this::onKey;
    private final ChangeListener<Node> focusWatcher = (obs, old, owner) -> {
        if (owner != null && isInside(owner, root)) {
            lastFocus = owner;
        }
    };

    public ReviewQueueTab(AppContext context, Navigator navigator, String courseId) {
        this.context = Objects.requireNonNull(context, "context");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.courseId = Objects.requireNonNull(courseId, "courseId");
        root.getStyleClass().add("review-queue");
        root.getStylesheets().add(STYLESHEET);
        root.getProperties().put(KEY, this);
        concepts = new ConceptsPanel(this, courseId);

        statusControl.setValue(ItemStatus.DRAFT);
        statusControl.setAccessibleText("Question status");
        statusControl.valueProperty().addListener((obs, old, value) -> {
            if (!quiet && value != null) {
                selected.clear();
                limit = PAGE_SIZE;
                reload();
            }
        });
        newQuestion.setOnAction(event -> openNewQuestion());
        generate.getStyleClass().add("primary");
        generate.setOnAction(event -> openGenerateDialog());
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox toolbar = new HBox(12, statusControl, gap, newQuestion, generate);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("review-toolbar");

        filter(moduleFilter, "Module");
        filter(conceptFilter, "Concept");
        filter(typeFilter, "Question type");
        filter(originFilter, "Written by");
        typeFilter.getItems().add(new Choice(null, "All types"));
        for (ItemType type : ItemType.values()) {
            typeFilter.getItems().add(new Choice(type.name(), ReviewLabels.typePlural(type)));
        }
        typeFilter.setValue(typeFilter.getItems().getFirst());
        originFilter.getItems().setAll(new Choice(null, "AI and by hand"), new Choice(ItemOrigin.AI.name(), "AI drafts"),
                new Choice(ItemOrigin.MANUAL.name(), "Written by hand"));
        originFilter.setValue(originFilter.getItems().getFirst());
        moduleFilter.getItems().setAll(new Choice(null, "All modules"));
        moduleFilter.setValue(moduleFilter.getItems().getFirst());
        conceptFilter.getItems().setAll(new Choice(null, "All concepts"));
        conceptFilter.setValue(conceptFilter.getItems().getFirst());
        flaggedFilter.setAccessibleText("Only flagged questions");
        flaggedFilter.selectedProperty().addListener((obs, old, on) -> filtersChanged());
        clearFilters.getStyleClass().addAll("quiet", "small");
        clearFilters.setOnAction(event -> clearFilters());
        Label filterLabel = new Label("Show");
        filterLabel.getStyleClass().add("text-caption");
        FlowPane filters = new FlowPane(8, 8, filterLabel, moduleFilter, conceptFilter, typeFilter, originFilter,
                flaggedFilter, clearFilters);
        filters.setAlignment(Pos.CENTER_LEFT);
        filters.getStyleClass().add("review-filters");

        banner.getStyleClass().add("generation-banner");
        banner.setAlignment(Pos.CENTER_LEFT);
        show(banner, false);

        selectAll.setAllowIndeterminate(false);
        selectAll.setOnAction(event -> selectAll(selectAll.isSelected()));
        selectedCount.getStyleClass().add("text-muted");
        approveSelected.getStyleClass().addAll("positive", "small");
        approveSelected.setOnAction(event -> approveSelected());
        keyHint.getStyleClass().addAll("text-caption", "key-hint");
        keyHint.setMinWidth(0);
        Region barGap = new Region();
        HBox.setHgrow(barGap, Priority.ALWAYS);
        selectionBar.getChildren().addAll(selectAll, selectedCount, approveSelected, barGap, keyHint);
        selectionBar.setAlignment(Pos.CENTER_LEFT);
        selectionBar.getStyleClass().add("selection-bar");

        list.getStyleClass().add("review-list");
        showing.getStyleClass().add("text-caption");
        showMore.getStyleClass().add("small");
        showMore.setOnAction(event -> {
            limit += PAGE_SIZE;
            reload();
        });
        footer.getChildren().addAll(showing, showMore);
        footer.setAlignment(Pos.CENTER);
        footer.getStyleClass().add("review-footer");

        content.getChildren().setAll(loadingCards());
        root.getChildren().addAll(concepts, toolbar, filters, banner, selectionBar, content);
        show(selectionBar, false);
        reloadDelay.setOnFinished(event -> reload());

        root.sceneProperty().addListener((obs, old, scene) -> attach(scene));
    }

    /**
     * The review queue of {@code courseId} inside {@code host}: built and started on the first call, then the
     * same one on later calls (so a host that redraws itself keeps the queue's state), and disposed with
     * {@code disposer}.
     */
    public static Node hostIn(Node host, Disposer disposer, AppContext context, Navigator navigator, String courseId) {
        String key = KEY + ":" + courseId;
        if (host.getProperties().get(key) instanceof ReviewQueueTab tab && !tab.disposed) {
            return tab.root();
        }
        ReviewQueueTab tab = new ReviewQueueTab(context, navigator, courseId);
        host.getProperties().put(key, tab);
        disposer.onDispose(() -> {
            host.getProperties().remove(key);
            tab.dispose();
        });
        tab.onShown();
        return tab.root();
    }

    /** The review queue shown at or under {@code node}, or null. */
    public static ReviewQueueTab find(Node node) {
        if (node == null) {
            return null;
        }
        if (node.getProperties().get(KEY) instanceof ReviewQueueTab tab) {
            return tab;
        }
        if (node instanceof Parent parent) {
            parent.applyCss();
        }
        Node found = node.lookup(".review-queue");
        return found != null && found.getProperties().get(KEY) instanceof ReviewQueueTab tab ? tab : null;
    }

    @Override
    public Region root() {
        return root;
    }

    @Override
    public void onShown() {
        if (shown || disposed) {
            return;
        }
        shown = true;
        Workspace workspace = context.workspace();
        if (workspace == null) {
            showProblem(UserMessage.PROFILE_NOT_OPENED);
            return;
        }
        disposer.add(Fx.subscribe(workspace.events(), ItemsChanged.class, event -> {
            if (courseId.equals(event.courseId())) {
                reloadSoon();
            }
        }));
        disposer.add(Fx.subscribe(workspace.events(), CourseChanged.class, event -> {
            if (courseId.equals(event.courseId())) {
                reloadSoon();
            }
        }));
        disposer.add(Fx.subscribe(workspace.events(), DocumentChanged.class, event -> {
            if (courseId.equals(event.courseId()) && (event.type() != ChangeType.UPDATED
                    || event.status() == DocumentStatus.READY || event.status() == DocumentStatus.FAILED)) {
                reloadSoon();
            }
        }));
        disposer.add(Fx.subscribe(workspace.events(), JobProgress.class, event -> onJob(event.job())));
        reload();
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        reloadDelay.stop();
        attach(null);
        disposer.dispose();
    }

    // ---- public hooks (screenshots, tests) ------------------------------------------------------------

    /** Opens "Generate questions" for the module the queue is filtered to (or the whole course). */
    public void openGenerateDialog() {
        if (data == null) {
            return;
        }
        new GenerateDialog(this, courseId, data.modules(), data.readyModuleIds(), data.courseReady(), data.concepts(),
                selectedValue(moduleFilter)).open(root);
    }

    /** Opens the editor for a new question written by hand. */
    public void openNewQuestion() {
        if (data == null) {
            return;
        }
        new ItemEditorDialog(this, null, data.modules(), data.concepts(), selectedValue(moduleFilter)).open(root);
    }

    /** Opens the editor for a question on screen; false when it isn't shown. */
    public boolean openEditor(String itemId) {
        ReviewCard card = cards.get(itemId);
        if (card == null || data == null) {
            return false;
        }
        edit(card);
        return true;
    }

    /** Opens the PDF page of a question's source; false when it has none on screen. */
    public boolean openSourcePage(String itemId) {
        ReviewCard card = cards.get(itemId);
        if (card == null || card.detail().source() == null) {
            return false;
        }
        String marked = card.sourceView().highlightedText();
        openPage(card.detail().source(), marked != null ? marked : card.detail().source().text());
        return true;
    }

    /** Shows the questions with this status. */
    public void showStatus(ItemStatus status) {
        statusControl.setValue(status);
    }

    /** Shows the questions of one module, or of every module for null. */
    public void filterToModule(String moduleId) {
        moduleFilter.getItems().stream().filter(c -> Objects.equals(c.value(), moduleId)).findFirst()
                .ifPresent(moduleFilter::setValue);
    }

    /** Shows only flagged questions, or all. */
    public void showFlaggedOnly(boolean value) {
        flaggedFilter.setSelected(value);
    }

    /** Ids of the questions on screen, in order. */
    public List<String> shownItemIds() {
        return List.copyOf(cards.keySet());
    }

    /** The question the keyboard acts on, or null. */
    public String currentItemId() {
        return currentId;
    }

    /** Makes a question on screen the one the keyboard acts on and scrolls to it; false when it isn't shown. */
    public boolean showItem(String itemId) {
        ReviewCard card = cards.get(itemId);
        if (card == null) {
            return false;
        }
        setCurrent(card, true);
        return true;
    }

    /** Ticks or unticks a draft on screen for "Approve selected"; false when it can't be selected. */
    public boolean selectItem(String itemId, boolean on) {
        ReviewCard card = cards.get(itemId);
        if (card == null || !card.selectBox().isVisible()) {
            return false;
        }
        card.setSelected(on);
        return true;
    }

    /** Opens or folds the key concepts. */
    public void showConcepts(boolean open) {
        concepts.setExpanded(open);
    }

    /** The cards on screen, in order. */
    List<ReviewCard> cards() {
        return List.copyOf(cards.values());
    }

    ReviewCard card(String itemId) {
        return cards.get(itemId);
    }

    ConceptsPanel conceptsPanel() {
        return concepts;
    }

    SegmentedControl<ItemStatus> statusControl() {
        return statusControl;
    }

    ChoiceBox<Choice> moduleFilter() {
        return moduleFilter;
    }

    ChoiceBox<Choice> conceptFilter() {
        return conceptFilter;
    }

    ChoiceBox<Choice> typeFilter() {
        return typeFilter;
    }

    ChoiceBox<Choice> originFilter() {
        return originFilter;
    }

    Label bannerText() {
        return banner.getChildren().stream().filter(n -> n instanceof VBox).map(n -> (VBox) n)
                .flatMap(v -> v.getChildren().stream()).filter(n -> n instanceof Label).map(n -> (Label) n)
                .findFirst().orElse(null);
    }

    boolean isBannerShown() {
        return banner.isVisible();
    }

    Snapshot snapshot() {
        return data;
    }

    // ---- loading -------------------------------------------------------------------------------------

    Workspace workspace() {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            throw new IllegalStateException("No profile is open");
        }
        return workspace;
    }

    /** Runs {@code work} in the background; the outcome arrives on the FX thread unless the queue is gone. */
    <T> void call(Callable<? extends T> work, Consumer<? super T> onSuccess, Consumer<? super UserMessage> onError) {
        if (disposed) {
            return;
        }
        disposer.add(UiTasks.run(work, onSuccess, onError));
    }

    /** Reloads after a short pause, so a burst of changes reloads once. */
    void reloadSoon() {
        if (!disposed) {
            reloadDelay.playFromStart();
        }
    }

    private Query query() {
        return new Query(statusControl.getValue(), selectedValue(moduleFilter), selectedValue(conceptFilter),
                enumValue(typeFilter, ItemType.class), enumValue(originFilter, ItemOrigin.class), flaggedFilter.isSelected(),
                limit);
    }

    private void reload() {
        if (disposed || !shown) {
            return;
        }
        reloadDelay.stop();
        if (pendingLoad != null) {
            pendingLoad.cancel();
        }
        Query query = query();
        int number = ++loadNumber;
        if (everLoaded) {
            list.setOpacity(0.7);
        }
        pendingLoad = UiTasks.run(() -> fetch(query), snapshot -> {
            if (number == loadNumber) {
                list.setOpacity(1);
                apply(snapshot);
            }
        }, message -> {
            if (number == loadNumber) {
                list.setOpacity(1);
                if (everLoaded) {
                    toastError(message);
                } else {
                    showProblem(message);
                }
            }
        });
        disposer.add(pendingLoad);
    }

    private Snapshot fetch(Query query) {
        Workspace ws = workspace();
        List<CourseModule> modules = ws.courses().modules(courseId);
        Set<String> ready = new HashSet<>();
        Set<String> withPdf = new HashSet<>();
        boolean courseReady = false;
        List<DocumentSummary> documents = ws.documents().list(courseId);
        for (DocumentSummary document : documents) {
            if (document.moduleId() != null) {
                withPdf.add(document.moduleId());
            }
            if (document.isReady()) {
                courseReady = true;
                if (document.moduleId() != null) {
                    ready.add(document.moduleId());
                }
            }
        }
        List<Concept> conceptList = ws.generation().concepts(courseId, null);
        Set<String> extracted = ws.generation().extractedConceptIds(courseId);
        ItemFilter base = query.filter(courseId);
        Map<ItemStatus, Integer> counts = new EnumMap<>(ItemStatus.class);
        for (ItemSummary summary : ws.items().list(base)) {
            counts.merge(summary.item().status(), 1, Integer::sum);
        }
        int total = ws.items().counts(courseId).total();
        List<ItemDetail> items = ws.items().listDetails(base.withStatuses(query.status()).withPage(query.limit(), 0));
        List<JobUpdate> jobs = ws.jobs().active();
        return new Snapshot(query, modules, Set.copyOf(ready), Set.copyOf(withPdf), courseReady, !documents.isEmpty(), conceptList,
                Set.copyOf(extracted), Map.copyOf(counts), total, items, jobs);
    }

    private void apply(Snapshot snapshot) {
        boolean first = !everLoaded;
        everLoaded = true;
        data = snapshot;
        quiet = true;
        try {
            for (ItemStatus status : STATUSES) {
                int n = snapshot.counts().getOrDefault(status, 0);
                statusControl.button(status).setText(ReviewLabels.status(status, n));
                statusControl.button(status).setAccessibleText(ReviewLabels.status(status) + ", " + n);
            }
            refreshFilterChoices(snapshot);
        } finally {
            quiet = false;
        }
        int drafts = snapshot.counts().getOrDefault(ItemStatus.DRAFT, 0);
        concepts.update(new ConceptsPanel.Data(snapshot.modules(), snapshot.readyModuleIds(), snapshot.pdfModuleIds(),
                snapshot.concepts(),
                snapshot.extractedConceptIds()), drafts > 0 || snapshot.courseTotal() > 0);
        if (first) {
            for (JobUpdate job : snapshot.activeJobs()) {
                onJob(job);
            }
        }
        reconcile(snapshot);
        showContent(snapshot);
    }

    private void reconcile(Snapshot snapshot) {
        boolean focusWasInList = root.getScene() != null && root.getScene().getFocusOwner() != null
                && isInside(root.getScene().getFocusOwner(), list);
        Map<String, ReviewCard> next = new LinkedHashMap<>();
        List<Node> nodes = new ArrayList<>();
        for (ItemDetail detail : snapshot.items()) {
            String id = detail.item().id();
            ReviewCard old = cards.get(id);
            boolean isFresh = fresh.contains(id);
            ReviewCard card;
            if (old != null && old.detail().equals(detail) && old.isFresh() == isFresh) {
                card = old;
            } else {
                card = new ReviewCard(detail, snapshot.moduleTitle(detail.item().moduleId()), isFresh, cardHost);
                card.setSelected(selected.contains(id));
                JobUpdate job = regenerating.get(id);
                if (job != null) {
                    card.setBusy(progressLine(job, "Writing a new version…"));
                }
                card.setError(cardErrors.get(id));
            }
            next.put(id, card);
            nodes.add(card);
        }
        cards = next;
        selected.retainAll(next.keySet());
        if (!list.getChildren().equals(nodes)) {
            list.getChildren().setAll(nodes);
        }
        ReviewCard current = currentId == null ? null : cards.get(currentId);
        if (current == null && !nodes.isEmpty()) {
            current = (ReviewCard) nodes.get(Math.min(currentIndex, nodes.size() - 1));
        }
        setCurrent(current, false);
        if (focusWasInList && current != null && root.getScene() != null
                && (root.getScene().getFocusOwner() == null || !isInside(root.getScene().getFocusOwner(), root))) {
            current.requestFocus();
        }
        updateSelectionBar();
    }

    private void showContent(Snapshot snapshot) {
        Query query = snapshot.query();
        boolean draftsView = query.status() == ItemStatus.DRAFT;
        if (!snapshot.items().isEmpty()) {
            int matching = snapshot.matching();
            showing.setText(matching > snapshot.items().size()
                    ? "Showing " + snapshot.items().size() + " of " + matching
                    : ReviewLabels.questions(matching));
            show(showMore, matching > snapshot.items().size());
            List<Node> children = List.of(list, footer);
            if (!content.getChildren().equals(children)) {
                content.getChildren().setAll(children);
            }
            show(selectionBar, true);
            keyHint.setText(draftsView ? "A approve · E edit · R reject · J K move · X select" : "E edit · J K move");
            for (Node node : List.of(selectAll, selectedCount, approveSelected)) {
                show(node, draftsView);
            }
            return;
        }
        show(selectionBar, false);
        content.getChildren().setAll(emptyState(snapshot));
    }

    private Node emptyState(Snapshot snapshot) {
        Query query = snapshot.query();
        if (snapshot.courseTotal() == 0 && !snapshot.courseReady()) {
            String title = snapshot.anyPdf() ? "Your PDFs are getting ready" : "Add a PDF to draft questions from";
            String message = snapshot.anyPdf()
                    ? "Questions can be drafted once a PDF is ready. Its progress shows on the PDFs tab."
                    : "The study assistant drafts questions from the PDFs in this course, each with the passage it came from.";
            return new EmptyState(EmptyIllustration.Kind.DOCUMENTS, title, message)
                    .secondary("Go to PDFs", () -> navigator.replace(new Destination.Studio(courseId, StudioTab.DOCUMENTS), true))
                    .secondary("Write a question", this::openNewQuestion);
        }
        if (snapshot.courseTotal() == 0) {
            return new EmptyState(EmptyIllustration.Kind.QUESTIONS, "Draft your first questions",
                    "Choose Generate questions and the study assistant drafts them from your PDFs. You check each one here before learners see it.")
                    .secondary("Write a question", this::openNewQuestion);
        }
        if (query.filtered()) {
            return new EmptyState(EmptyIllustration.Kind.SEARCH, "No questions match these filters",
                    "Try another module, concept or type, or clear the filters to see everything.")
                    .secondary("Clear filters", this::clearFilters);
        }
        return switch (query.status()) {
            case DRAFT -> {
                EmptyState state = new EmptyState(EmptyIllustration.Kind.CAUGHT_UP, "Nothing waiting for review",
                        "You've checked every draft. Generate more questions when you're ready.");
                if (snapshot.counts().getOrDefault(ItemStatus.APPROVED, 0) > 0) {
                    state.secondary("See approved questions", () -> showStatus(ItemStatus.APPROVED));
                }
                yield state;
            }
            case APPROVED -> new EmptyState(EmptyIllustration.Kind.QUESTIONS, "No approved questions",
                    "Approve drafts and they appear here, ready to publish.");
            case REJECTED -> new EmptyState(EmptyIllustration.Kind.CAUGHT_UP, "No rejected questions",
                    "Drafts you reject wait here in case you change your mind.");
            case RETIRED -> new EmptyState(EmptyIllustration.Kind.CAUGHT_UP, "No retired questions",
                    "Retire an approved question to take it out of study. What learners have done on it is kept.");
        };
    }

    private void showProblem(UserMessage message) {
        show(selectionBar, false);
        content.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.SEARCH, "The review queue couldn't open",
                message.text()).secondary("Try again", () -> {
                    content.getChildren().setAll(loadingCards());
                    reload();
                }));
    }

    private static Node loadingCards() {
        VBox box = new VBox(16);
        for (int i = 0; i < 2; i++) {
            HBox columns = new HBox(28, Skeleton.text(5), Skeleton.block(-1, 150));
            HBox.setHgrow(columns.getChildren().getFirst(), Priority.ALWAYS);
            HBox.setHgrow(columns.getChildren().getLast(), Priority.ALWAYS);
            VBox card = new VBox(18, Skeleton.line(260), columns, Skeleton.line(320));
            card.getStyleClass().addAll("card", "review-card", "review-skeleton");
            box.getChildren().add(card);
        }
        box.setAccessibleText("Loading questions");
        return box;
    }

    // ---- filters ---------------------------------------------------------------------------------------

    private void filter(ChoiceBox<Choice> box, String name) {
        box.setAccessibleText(name);
        box.getStyleClass().add("review-filter");
        box.valueProperty().addListener((obs, old, value) -> {
            if (box == moduleFilter && !quiet && data != null) {
                quiet = true;
                try {
                    refreshConceptChoices(data);
                } finally {
                    quiet = false;
                }
            }
            filtersChanged();
        });
    }

    private void filtersChanged() {
        boolean any = selectedValue(moduleFilter) != null || selectedValue(conceptFilter) != null
                || selectedValue(typeFilter) != null || selectedValue(originFilter) != null || flaggedFilter.isSelected();
        clearFilters.setVisible(any);
        if (quiet) {
            return;
        }
        limit = PAGE_SIZE;
        selected.clear();
        reload();
    }

    private void clearFilters() {
        quiet = true;
        try {
            moduleFilter.setValue(moduleFilter.getItems().getFirst());
            if (data != null) {
                refreshConceptChoices(data);
            }
            conceptFilter.setValue(conceptFilter.getItems().getFirst());
            typeFilter.setValue(typeFilter.getItems().getFirst());
            originFilter.setValue(originFilter.getItems().getFirst());
            flaggedFilter.setSelected(false);
        } finally {
            quiet = false;
        }
        filtersChanged();
    }

    private void refreshFilterChoices(Snapshot snapshot) {
        String module = selectedValue(moduleFilter);
        List<Choice> modules = new ArrayList<>();
        modules.add(new Choice(null, "All modules"));
        for (CourseModule m : snapshot.modules()) {
            modules.add(new Choice(m.id(), m.title()));
        }
        setChoices(moduleFilter, modules, module);
        refreshConceptChoices(snapshot);
        clearFilters.setVisible(snapshot.query().filtered());
    }

    private void refreshConceptChoices(Snapshot snapshot) {
        String module = selectedValue(moduleFilter);
        String concept = selectedValue(conceptFilter);
        Map<String, Integer> names = new HashMap<>();
        snapshot.concepts().forEach(c -> names.merge(c.name(), 1, Integer::sum));
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(null, "All concepts"));
        for (Concept c : snapshot.concepts()) {
            if (module == null || module.equals(c.moduleId()) || c.moduleId() == null) {
                String label = c.name();
                if (names.get(c.name()) > 1 && c.moduleId() != null) {
                    label += " · " + snapshot.moduleTitle(c.moduleId());
                }
                choices.add(new Choice(c.id(), label));
            }
        }
        setChoices(conceptFilter, choices, concept);
    }

    private static void setChoices(ChoiceBox<Choice> box, List<Choice> choices, String keep) {
        if (!box.getItems().equals(choices)) {
            box.getItems().setAll(choices);
        }
        Choice chosen = choices.stream().filter(c -> Objects.equals(c.value(), keep)).findFirst().orElse(choices.getFirst());
        if (!Objects.equals(box.getValue(), chosen)) {
            box.setValue(chosen);
        }
    }

    private static String selectedValue(ChoiceBox<Choice> box) {
        return box.getValue() == null ? null : box.getValue().value();
    }

    private static <E extends Enum<E>> E enumValue(ChoiceBox<Choice> box, Class<E> type) {
        String value = selectedValue(box);
        return value == null ? null : Enum.valueOf(type, value);
    }

    // ---- current card, keyboard and selection ------------------------------------------------------------

    private void setCurrent(ReviewCard card, boolean focus) {
        for (ReviewCard other : cards.values()) {
            if (other != card && other.isCurrent()) {
                other.setCurrent(false);
            }
        }
        if (card == null) {
            currentId = null;
            return;
        }
        card.setCurrent(true);
        currentId = card.itemId();
        currentIndex = new ArrayList<>(cards.keySet()).indexOf(currentId);
        if (focus) {
            card.requestFocus();
            scrollIntoView(card);
        }
    }

    /** Moves the outline to the next ({@code +1}) or previous ({@code -1}) card. */
    void move(int delta) {
        if (cards.isEmpty()) {
            return;
        }
        List<ReviewCard> ordered = new ArrayList<>(cards.values());
        int at = currentId == null ? -1 : ordered.indexOf(cards.get(currentId));
        int next = Math.clamp(at + delta, 0, ordered.size() - 1);
        setCurrent(ordered.get(next), true);
    }

    /** Handles the review keys: A approve, E edit, R reject, J/K next/previous, X select. */
    void handleKey(KeyEvent event) {
        onKey(event);
    }

    private void onKey(KeyEvent event) {
        if (event.isConsumed() || event.isShortcutDown() || event.isAltDown() || event.isControlDown()
                || event.isMetaDown() || disposed || data == null) {
            return;
        }
        Scene scene = root.getScene();
        if (scene == null || !root.isVisible()) {
            return;
        }
        Node focus = scene.getFocusOwner();
        if (focus instanceof TextInputControl || focus instanceof ComboBoxBase<?>
                || focus instanceof ChoiceBox<?> box && box.isShowing()) {
            return;
        }
        try {
            if (Overlays.find(root).hasModal()) {
                return;
            }
        } catch (IllegalStateException noWindow) {
            return;
        }
        ReviewCard current = currentId == null ? null : cards.get(currentId);
        switch (event.getCode()) {
            case J -> move(1);
            case K -> move(-1);
            case A -> {
                if (current != null && current.item().status() == ItemStatus.DRAFT && !current.isBusy()) {
                    approve(current);
                }
            }
            case E -> {
                if (current != null && !current.isBusy()) {
                    edit(current);
                }
            }
            case R -> {
                if (current != null && current.item().status() == ItemStatus.DRAFT && !current.isBusy()) {
                    reject(current);
                }
            }
            case X -> {
                if (current != null && current.item().status() == ItemStatus.DRAFT) {
                    current.setSelected(!current.isSelected());
                }
            }
            default -> {
                return;
            }
        }
        event.consume();
    }

    private void attach(Scene scene) {
        if (keyScene != null) {
            keyScene.removeEventHandler(KeyEvent.KEY_PRESSED, keys);
            keyScene.focusOwnerProperty().removeListener(focusWatcher);
            keyScene = null;
        }
        if (scene == null || disposed) {
            return;
        }
        keyScene = scene;
        scene.addEventHandler(KeyEvent.KEY_PRESSED, keys);
        scene.focusOwnerProperty().addListener(focusWatcher);
        // A host that redraws itself takes the queue off screen for a moment; keep the keyboard where it was.
        Node remembered = lastFocus;
        if (remembered != null) {
            Platform.runLater(() -> {
                Node owner = scene.getFocusOwner();
                if (remembered.getScene() == scene && (owner == null || !isInside(owner, root))
                        && !Overlays.find(root).hasModal()) {
                    remembered.requestFocus();
                }
            });
        }
    }

    private void scrollIntoView(Node node) {
        ScrollPane pane = null;
        for (Node n = root.getParent(); n != null; n = n.getParent()) {
            if (n instanceof ScrollPane scroll) {
                pane = scroll;
                break;
            }
        }
        if (pane == null || pane.getContent() == null) {
            return;
        }
        Node inner = pane.getContent();
        Bounds bounds = inner.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        double contentHeight = inner.getBoundsInLocal().getHeight();
        double viewHeight = pane.getViewportBounds().getHeight();
        if (bounds == null || contentHeight <= viewHeight) {
            return;
        }
        double range = contentHeight - viewHeight;
        double top = pane.getVvalue() * range;
        double margin = 24;
        double target = top;
        if (bounds.getMinY() - margin < top) {
            target = bounds.getMinY() - margin;
        } else if (bounds.getMaxY() + margin > top + viewHeight) {
            target = Math.min(bounds.getMinY() - margin, bounds.getMaxY() + margin - viewHeight);
        }
        pane.setVvalue(Math.clamp(target / range, 0, 1));
    }

    private void selectAll(boolean on) {
        for (ReviewCard card : cards.values()) {
            if (card.item().status() == ItemStatus.DRAFT) {
                card.setSelected(on);
            }
        }
        updateSelectionBar();
    }

    private void updateSelectionBar() {
        long drafts = cards.values().stream().filter(c -> c.item().status() == ItemStatus.DRAFT).count();
        int n = selected.size();
        selectedCount.setText(n == 0 ? "" : n + " selected");
        approveSelected.setDisable(n == 0);
        approveSelected.setText(n > 1 ? "Approve " + n + " questions" : n == 1 ? "Approve 1 question" : "Approve selected");
        selectAll.setSelected(drafts > 0 && n == drafts);
    }

    // ---- actions ---------------------------------------------------------------------------------------

    private final ReviewCard.Host cardHost = new ReviewCard.Host() {
        @Override
        public void approve(ReviewCard card) {
            ReviewQueueTab.this.approve(card);
        }

        @Override
        public void edit(ReviewCard card) {
            ReviewQueueTab.this.edit(card);
        }

        @Override
        public void regenerate(ReviewCard card) {
            ReviewQueueTab.this.regenerate(card);
        }

        @Override
        public void stopRegenerating(ReviewCard card) {
            JobUpdate job = regenerating.get(card.itemId());
            if (job != null) {
                card.setBusy("Stopping…");
                call(() -> {
                    workspace().jobs().cancel(job.jobId());
                    return null;
                }, ignored -> { }, ReviewQueueTab.this::toastError);
            }
        }

        @Override
        public void reject(ReviewCard card) {
            ReviewQueueTab.this.reject(card);
        }

        @Override
        public void retire(ReviewCard card) {
            String id = card.itemId();
            act(card, () -> workspace().items().retire(id), ItemStatus.APPROVED, "Retired", () -> {
                workspace().items().returnToDraft(id);
                workspace().items().approve(id);
            });
        }

        @Override
        public void backToReview(ReviewCard card) {
            String id = card.itemId();
            ItemStatus was = card.item().status();
            act(card, () -> workspace().items().returnToDraft(id), was, "Back in review", null);
        }

        @Override
        public void resolveFlag(ReviewCard card, Flag flag) {
            call(() -> {
                workspace().items().resolveFlag(flag.id());
                return null;
            }, ignored -> {
                toast(Toasts.Kind.SUCCESS, "Flag resolved", null, null);
                reloadSoon();
            }, message -> card.setError(message.text()));
        }

        @Override
        public void openPage(SourcePassage source, String textToFind) {
            ReviewQueueTab.this.openPage(source, textToFind);
        }

        @Override
        public void selectionChanged(ReviewCard card, boolean on) {
            if (on) {
                selected.add(card.itemId());
            } else {
                selected.remove(card.itemId());
            }
            updateSelectionBar();
        }

        @Override
        public void makeCurrent(ReviewCard card) {
            if (!card.isCurrent()) {
                setCurrent(card, false);
            }
        }
    };

    void approve(ReviewCard card) {
        String id = card.itemId();
        act(card, () -> workspace().items().approve(id), ItemStatus.DRAFT, "Approved",
                () -> workspace().items().returnToDraft(id));
    }

    void reject(ReviewCard card) {
        String id = card.itemId();
        act(card, () -> workspace().items().reject(id), ItemStatus.DRAFT, "Rejected",
                () -> workspace().items().returnToDraft(id));
    }

    void edit(ReviewCard card) {
        if (data == null) {
            return;
        }
        new ItemEditorDialog(this, card.detail(), data.modules(), data.concepts(), null).open(root);
    }

    void regenerate(ReviewCard card) {
        String id = card.itemId();
        cardErrors.remove(id);
        card.setBusy("Writing a new version…");
        call(() -> workspace().generation().regenerate(id), job -> {
            regenerating.put(id, job);
            fresh.remove(id);
            onJob(job);
        }, message -> {
            card.setBusy(null);
            cardErrors.put(id, message.text());
            card.setError(message.text());
        });
    }

    /**
     * Runs a status change on a card's question. When it takes the question out of the list on screen, the
     * card goes at once and the outline moves to the next one; the toast offers {@code undo} when not null.
     */
    private void act(ReviewCard card, Runnable work, ItemStatus from, String done, Runnable undo) {
        String id = card.itemId();
        cardErrors.remove(id);
        card.setError(null);
        call(() -> {
            work.run();
            return null;
        }, ignored -> {
            removeCard(id);
            toast(Toasts.Kind.SUCCESS, done, undo == null ? null : "Undo", undo == null ? null : () -> call(() -> {
                undo.run();
                return null;
            }, again -> reloadSoon(), this::toastError));
            reloadSoon();
        }, message -> {
            cardErrors.put(id, message.text());
            card.setError(message.text());
        });
    }

    /** Takes a card off screen (its question no longer matches) and moves the outline to its neighbour. */
    private void removeCard(String id) {
        ReviewCard card = cards.get(id);
        if (card == null || data == null) {
            return;
        }
        List<String> ids = new ArrayList<>(cards.keySet());
        int at = ids.indexOf(id);
        boolean wasCurrent = id.equals(currentId);
        boolean hadFocus = root.getScene() != null && root.getScene().getFocusOwner() != null
                && isInside(root.getScene().getFocusOwner(), card);
        Map<String, ReviewCard> next = new LinkedHashMap<>(cards);
        next.remove(id);
        cards = next;
        selected.remove(id);
        list.getChildren().remove(card);
        if (wasCurrent && !cards.isEmpty()) {
            ReviewCard neighbour = new ArrayList<>(cards.values()).get(Math.min(at, cards.size() - 1));
            setCurrent(neighbour, hadFocus);
            if (!hadFocus) {
                scrollIntoView(neighbour);
            }
        } else if (cards.isEmpty()) {
            currentId = null;
        }
        updateSelectionBar();
    }

    private void approveSelected() {
        List<String> ids = cards.keySet().stream().filter(selected::contains).toList();
        if (ids.isEmpty()) {
            return;
        }
        approveSelected.setDisable(true);
        call(() -> {
            workspace().items().approveAll(ids);
            List<String> approved = new ArrayList<>();
            for (String id : ids) {
                if (workspace().items().get(id).item().status() == ItemStatus.APPROVED) {
                    approved.add(id);
                }
            }
            return approved;
        }, approved -> {
            for (String id : approved) {
                removeCard(id);
            }
            selected.removeAll(approved);
            int left = ids.size() - approved.size();
            for (String id : ids) {
                ReviewCard card = cards.get(id);
                if (card != null && !approved.contains(id)) {
                    cardErrors.put(id, "Fix this question before approving it.");
                    card.setError("Fix this question before approving it.");
                }
            }
            String message = "Approved " + ReviewLabels.questions(approved.size())
                    + (left > 0 ? ". " + ReviewLabels.questions(left) + (left == 1 ? " needs" : " need") + " fixing first." : "");
            toast(approved.isEmpty() ? Toasts.Kind.INFO : Toasts.Kind.SUCCESS, message,
                    approved.isEmpty() ? null : "Undo", approved.isEmpty() ? null : () -> call(() -> {
                        for (String id : approved) {
                            workspace().items().returnToDraft(id);
                        }
                        return null;
                    }, again -> reloadSoon(), this::toastError));
            updateSelectionBar();
            reloadSoon();
        }, message -> {
            updateSelectionBar();
            toastError(message);
        });
    }

    /** Saves the editor's content: edits {@code itemId}, or creates a question when it is null. */
    void saveItem(String itemId, String moduleId, ItemContent content, boolean approve, Runnable onDone,
                  Consumer<String> onError) {
        call(() -> {
            String id;
            if (itemId == null) {
                id = workspace().items().create(courseId, moduleId, content).id();
            } else {
                workspace().items().edit(itemId, content);
                id = itemId;
            }
            if (approve) {
                workspace().items().approve(id);
            }
            return id;
        }, id -> {
            cardErrors.remove(id);
            fresh.remove(id);
            onDone.run();
            String message = itemId == null
                    ? (approve ? "Question added and approved" : "Question added to the drafts")
                    : (approve ? "Saved and approved" : "Changes saved");
            if (approve && itemId != null && statusControl.getValue() == ItemStatus.DRAFT) {
                removeCard(id);
            }
            toast(Toasts.Kind.SUCCESS, message, null, null);
            if (itemId == null && statusControl.getValue() == (approve ? ItemStatus.APPROVED : ItemStatus.DRAFT)) {
                currentId = id;
            }
            reloadSoon();
        }, message -> onError.accept(message.text()));
    }

    void openPage(SourcePassage source, String textToFind) {
        new SourcePageDialog(this, source, textToFind).open(root);
    }

    // ---- concepts ----------------------------------------------------------------------------------------

    void findConcepts(String moduleId) {
        concepts.setStatus(moduleId, new ConceptsPanel.Status(ConceptsPanel.Status.Kind.WORKING, "Finding key concepts…"));
        call(() -> workspace().generation().extractConcepts(moduleId), job -> {
            conceptJobs.put(job.jobId(), moduleId);
            onJob(job);
        }, message -> concepts.setStatus(moduleId, new ConceptsPanel.Status(ConceptsPanel.Status.Kind.PROBLEM, message.text())));
    }

    void addConcept(String moduleId, String name, Runnable onDone) {
        call(() -> workspace().generation().addConcept(courseId, moduleId, name, ""), concept -> {
            onDone.run();
            reloadSoon();
        }, message -> {
            onDone.run();
            toastError(message);
        });
    }

    void renameConcept(Concept concept, String name, Runnable onDone) {
        call(() -> workspace().generation().updateConcept(concept.id(), name, concept.description()), updated -> {
            onDone.run();
            reloadSoon();
        }, message -> {
            onDone.run();
            toastError(message);
        });
    }

    void deleteConcept(Concept concept) {
        call(() -> {
            workspace().generation().deleteConcept(concept.id());
            return null;
        }, ignored -> {
            toast(Toasts.Kind.SUCCESS, "Removed “" + Formats.shorten(concept.name(), 40) + "”", null, null);
            reloadSoon();
        }, this::toastError);
    }

    // ---- generating --------------------------------------------------------------------------------------

    void startGeneration(GenerationRequest request, Runnable onQueued, Consumer<String> onError) {
        call(() -> workspace().generation().generate(request), job -> {
            lastRequest = request;
            generation = job;
            generationResult = null;
            onQueued.run();
            onJob(job);
        }, message -> onError.accept(message.text()));
    }

    private void onJob(JobUpdate job) {
        if (disposed) {
            return;
        }
        switch (job.kind()) {
            case GENERATE_ITEMS -> {
                boolean ours = generation != null ? generation.jobId().equals(job.jobId())
                        : job.isActive() && belongsHere(job.subjectId());
                if (ours) {
                    generation = job;
                    if (job.status() == JobStatus.DONE) {
                        String jobId = job.jobId();
                        call(() -> JobOutcomes.drafted(workspace().jobs(), jobId).orElse(null), outcome -> {
                            if (generation != null && generation.jobId().equals(jobId)) {
                                generationResult = JobOutcomes.draftedSentence(outcome);
                                renderBanner();
                            }
                        }, message -> {
                            generationResult = JobOutcomes.draftedSentence(null);
                            renderBanner();
                        });
                        reloadSoon();
                    }
                    renderBanner();
                }
            }
            case EXTRACT_CONCEPTS -> {
                String moduleId = conceptJobs.containsKey(job.jobId()) ? conceptJobs.get(job.jobId())
                        : job.isActive() && data != null && data.moduleTitle(job.subjectId()) != null ? job.subjectId() : null;
                if (moduleId == null) {
                    return;
                }
                conceptJobs.put(job.jobId(), moduleId);
                switch (job.status()) {
                    case QUEUED, RUNNING -> concepts.setStatus(moduleId, new ConceptsPanel.Status(
                            ConceptsPanel.Status.Kind.WORKING, progressLine(job, "Finding key concepts…")));
                    case DONE -> {
                        conceptJobs.remove(job.jobId());
                        call(() -> JobOutcomes.found(workspace().jobs(), job.jobId()).orElse(null),
                                outcome -> concepts.setStatus(moduleId, new ConceptsPanel.Status(ConceptsPanel.Status.Kind.DONE,
                                        JobOutcomes.foundSentence(outcome))),
                                message -> concepts.setStatus(moduleId, new ConceptsPanel.Status(ConceptsPanel.Status.Kind.DONE,
                                        JobOutcomes.foundSentence(null))));
                        reloadSoon();
                    }
                    case FAILED -> {
                        conceptJobs.remove(job.jobId());
                        UserMessage failure = job.failure();
                        concepts.setStatus(moduleId, new ConceptsPanel.Status(ConceptsPanel.Status.Kind.PROBLEM,
                                failure == null ? UserMessage.SOMETHING_WENT_WRONG.text() : failure.text()));
                    }
                    case CANCELLED -> {
                        conceptJobs.remove(job.jobId());
                        concepts.setStatus(moduleId, null);
                    }
                }
            }
            case REGENERATE_ITEM -> {
                String id = job.subjectId();
                JobUpdate known = regenerating.get(id);
                if (known == null && !(job.isActive() && cards.containsKey(id))) {
                    return;
                }
                if (known != null && !known.jobId().equals(job.jobId())) {
                    return;
                }
                ReviewCard card = cards.get(id);
                switch (job.status()) {
                    case QUEUED, RUNNING -> {
                        regenerating.put(id, job);
                        if (card != null) {
                            card.setBusy(progressLine(job, "Writing a new version…"));
                        }
                    }
                    case DONE -> {
                        regenerating.remove(id);
                        fresh.add(id);
                        if (card != null) {
                            card.setBusy(null);
                        }
                        reloadSoon();
                    }
                    case FAILED -> {
                        regenerating.remove(id);
                        UserMessage failure = job.failure();
                        String text = failure == null ? UserMessage.SOMETHING_WENT_WRONG.text() : failure.text();
                        cardErrors.put(id, text);
                        if (card != null) {
                            card.setBusy(null);
                            card.setError(text);
                        }
                    }
                    case CANCELLED -> {
                        regenerating.remove(id);
                        if (card != null) {
                            card.setBusy(null);
                        }
                    }
                }
            }
            default -> {
                // Other work doesn't show here.
            }
        }
    }

    private boolean belongsHere(String subjectId) {
        return courseId.equals(subjectId) || data != null && data.moduleTitle(subjectId) != null;
    }

    private static String progressLine(JobUpdate job, String fallback) {
        return job.isActive() && job.message() != null && !job.message().isBlank() ? job.message() : fallback;
    }

    private void renderBanner() {
        if (generation == null) {
            show(banner, false);
            return;
        }
        List<Node> children = new ArrayList<>();
        VBox text = new VBox(6);
        HBox.setHgrow(text, Priority.ALWAYS);
        Label line = new Label();
        line.setWrapText(true);
        text.getChildren().add(line);
        banner.getStyleClass().removeAll("working", "done", "problem");
        switch (generation.status()) {
            case QUEUED, RUNNING -> {
                banner.getStyleClass().add("working");
                children.add(new ActivityIndicator(18));
                line.setText(progressLine(generation, "Getting ready to write questions…"));
                line.getStyleClass().add("medium");
                ProgressBarSlim bar = new ProgressBarSlim();
                bar.setProgress(generation.progress());
                bar.setMaxWidth(Double.MAX_VALUE);
                text.getChildren().add(bar);
                children.add(text);
                Button stop = new Button("Stop");
                stop.getStyleClass().addAll("quiet", "small", "neutral");
                stop.setAccessibleText("Stop writing questions");
                String jobId = generation.jobId();
                stop.setOnAction(event -> call(() -> {
                    workspace().jobs().cancel(jobId);
                    return null;
                }, ignored -> { }, this::toastError));
                children.add(stop);
            }
            case DONE -> {
                banner.getStyleClass().add("done");
                children.add(Icons.of(Feather.CHECK_CIRCLE, 18));
                line.setText(generationResult == null ? "Questions drafted." : generationResult);
                line.getStyleClass().add("medium");
                children.add(text);
                if (statusControl.getValue() != ItemStatus.DRAFT) {
                    Button see = new Button("See drafts");
                    see.getStyleClass().addAll("quiet", "small");
                    see.setOnAction(event -> showStatus(ItemStatus.DRAFT));
                    children.add(see);
                }
                children.add(dismiss());
            }
            case FAILED -> {
                banner.getStyleClass().add("problem");
                children.add(Icons.of(Feather.ALERT_CIRCLE, 18));
                UserMessage failure = generation.failure();
                line.setText(failure == null ? UserMessage.SOMETHING_WENT_WRONG.text() : failure.text());
                children.add(text);
                if (lastRequest != null) {
                    Button again = new Button("Try again");
                    again.getStyleClass().add("small");
                    GenerationRequest request = lastRequest;
                    again.setOnAction(event -> startGeneration(request, () -> { },
                            problem -> toast(Toasts.Kind.ERROR, problem, null, null)));
                    children.add(again);
                }
                children.add(dismiss());
            }
            case CANCELLED -> {
                children.add(Icons.of(Feather.SLASH, 18));
                line.setText("Stopped writing questions.");
                children.add(text);
                children.add(dismiss());
            }
        }
        banner.getChildren().setAll(children);
        show(banner, true);
    }

    private Node dismiss() {
        IconButton close = new IconButton(Feather.X, "Dismiss").small();
        close.setOnAction(event -> {
            generation = null;
            generationResult = null;
            renderBanner();
        });
        return close;
    }

    // ---- small helpers ---------------------------------------------------------------------------------

    private void toast(Toasts.Kind kind, String message, String action, Runnable onAction) {
        if (root.getScene() == null) {
            return;
        }
        try {
            Toasts.show(root, kind, message, action, onAction);
        } catch (IllegalStateException noOverlays) {
            // Not in a window with overlays (a bare test scene): nothing to show the toast in.
        }
    }

    private void toastError(UserMessage message) {
        toast(Toasts.Kind.ERROR, message.text(), null, null);
    }

    private static boolean isInside(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    private static void show(Node node, boolean visible) {
        node.setVisible(visible);
        node.setManaged(visible);
    }
}
