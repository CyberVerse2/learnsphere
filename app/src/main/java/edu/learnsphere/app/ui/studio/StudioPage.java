package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.ConfirmDialog;
import edu.learnsphere.app.ui.components.CourseCard;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.ItemCounts;
import edu.learnsphere.core.service.ItemDetail;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The Studio, where creators work. Without a course it lists the courses this profile is writing, with New
 * course. With one it is a working surface: the course's cover and title, then tabs for its details and
 * cover, modules, PDFs with live status, the review queue (each AI draft beside the passage it came from),
 * scanned pages to check, and publishing and sharing.
 *
 * <p>Switching tabs happens in place: the tab's content is replaced and the destination is updated without a
 * new history entry, so back still leaves the Studio and the breadcrumbs name the tab. A tab with unsaved
 * edits asks before it is left. Each tab is a {@link StudioSection} that loads its own data and follows its
 * own events.
 */
public final class StudioPage extends PageView {

    /** Drafts shown in the review queue at once. */
    static final int QUEUE_PAGE = 6;
    /** Starting width of each review card column; both grow to share the card. */
    private static final double REVIEW_COLUMN = 420;

    private final Destination.Studio destination;
    private final StudioFiles files;
    private final VBox headerBox = new VBox();
    private final VBox body = new VBox(24);
    private final Map<StudioTab, ToggleButton> tabButtons = new EnumMap<>(StudioTab.class);
    private final Map<StudioTab, Label> tabCounts = new EnumMap<>(StudioTab.class);
    private final ToggleGroup tabGroup = new ToggleGroup();
    private Refresher<Header> header;
    private StudioTab tab;
    private StudioSection section;
    private boolean built;

    /** What the review queue shows: a page of drafts and the course's counts. */
    private record Workbench(List<ItemDetail> queue, ItemCounts counts) {
    }

    /** What the header and the tab counts show. */
    private record Header(CourseSummary course, int scannedPages) {
    }

    public StudioPage(AppContext context, Navigator navigator, Destination.Studio destination) {
        this(context, navigator, destination, StudioFiles.system());
    }

    /** A Studio page that opens files through {@code files} (tests and the screenshot tour pass their own). */
    public StudioPage(AppContext context, Navigator navigator, Destination.Studio destination, StudioFiles files) {
        super(context, navigator);
        this.destination = Objects.requireNonNull(destination, "destination");
        this.files = Objects.requireNonNull(files, "files");
        this.tab = destination.tab();
        StudioStyles.apply(page());
        FocusScroller.install(page());
        page().getStyleClass().add("studio-page");
        body.getStyleClass().add("studio-body");
        if (destination.courseId() != null) {
            show(loadingHeader(), Skeleton.block(-1, 44), Skeleton.block(-1, 320));
        }
    }

    @Override
    public void onShown() {
        if (destination.courseId() == null) {
            switchTo(null, null);
            return;
        }
        String id = destination.courseId();
        header = new Refresher<>(disposer, () -> {
            var ws = workspace();
            CourseSummary course = ws.courses().summary(id);
            int scanned = 0;
            if (course.documentsNeedingAttention() > 0) {
                scanned = ws.documents().list(id).stream().mapToInt(DocumentSummary::pagesNeedingReview).sum();
            }
            return new Header(course, scanned);
        }, this::renderHeader, this::headerFailed);
        header.request();
        on(CourseChanged.class, event -> {
            if (id.equals(event.courseId()) && event.type() != ChangeType.DELETED) {
                header.request();
            }
        });
        Map<String, DocumentStatus> statuses = new HashMap<>();
        on(DocumentChanged.class, event -> {
            if (!id.equals(event.courseId())) {
                return;
            }
            // Page-by-page progress while a PDF is read changes no count in the header; anything else may.
            DocumentStatus status = event.status();
            DocumentStatus before = statuses.put(event.documentId(), status);
            boolean progressOnly = event.type() == ChangeType.UPDATED && before == status && event.hasSteps()
                    && status != DocumentStatus.READY && status != DocumentStatus.FAILED
                    && status != DocumentStatus.NEEDS_REVIEW;
            if (!progressOnly) {
                header.request();
            }
        });
        on(ItemsChanged.class, event -> {
            if (id.equals(event.courseId())) {
                header.request();
            }
        });
    }

    /** The tab on screen; null on the list of courses. */
    StudioTab tab() {
        return destination.courseId() == null ? null : tab;
    }

    /** The part of the page on screen (tests). */
    StudioSection section() {
        return section;
    }

    /** Opens {@code next} in place; asks first when the tab on screen has unsaved edits. */
    void openTab(StudioTab next, String documentId) {
        Objects.requireNonNull(next, "next");
        if (next == tab && documentId == null && section != null) {
            selectButton(tab);
            return;
        }
        if (section != null && section.hasUnsavedChanges()) {
            ConfirmDialog.of("Leave without saving?", section.unsavedChangesText(), "Discard changes")
                    .cancelLabel("Keep editing")
                    .danger()
                    .show(page())
                    .thenAccept(leave -> {
                        if (leave) {
                            switchTo(next, documentId);
                        } else {
                            selectButton(tab);
                        }
                    });
            return;
        }
        switchTo(next, documentId);
    }

    private void switchTo(StudioTab next, String documentId) {
        if (section != null) {
            section.dispose();
        }
        StudioHost host = new StudioHost(context, navigator, destination.courseId(), files, this::openTab);
        if (next == null) {
            section = new CourseListSection(host);
            show(section.root());
        } else {
            boolean changed = next != tab;
            tab = next;
            section = sectionFor(next, host, documentId);
            body.getChildren().setAll(section.root());
            selectButton(next);
            if (changed) {
                navigator.replace(new Destination.Studio(destination.courseId(), next), false);
                page().setVvalue(0);
            }
        }
        section.onShown();
    }

    private StudioSection sectionFor(StudioTab next, StudioHost host, String documentId) {
        return switch (next) {
            case DETAILS -> new DetailsSection(host);
            case MODULES -> new ModulesSection(host);
            case DOCUMENTS -> new DocumentsSection(host);
            case REVIEW -> new ReviewTab(host);
            case PAGES -> new ScannedPagesSection(host, documentId);
            case PUBLISH -> new PublishSection(host);
        };
    }

    /**
     * A header that couldn't load: before the first one the page shows the problem; afterwards the last
     * header and the open tab (with any unsaved edits) stay, and the problem shows as a toast.
     */
    private void headerFailed(UserMessage message) {
        if (built) {
            Toasts.error(page(), message);
        } else {
            showError(message);
        }
    }

    @Override
    protected void showError(UserMessage message) {
        disposeSection();
        built = false;
        show(new EmptyState(EmptyIllustration.Kind.SEARCH, "This course couldn't open", message.text())
                .primary("Go to Studio", () -> navigator.navigate(Destination.Studio.courses())));
    }

    @Override
    public void dispose() {
        disposeSection();
        super.dispose();
    }

    private void disposeSection() {
        if (section != null) {
            section.dispose();
            section = null;
        }
    }

    // -------------------------------------------------------------------------------------------------
    // Header and tabs
    // -------------------------------------------------------------------------------------------------

    private void renderHeader(Header data) {
        CourseSummary course = data.course();
        if (!course.createdHere()) {
            disposeSection();
            show(new EmptyState(EmptyIllustration.Kind.LIBRARY, "This course was shared with you",
                    "You can study it and ask about it. Only its author can change it.")
                    .primary("Open course", () -> navigator.navigate(new Destination.Course(course.id())))
                    .secondary("Go to Studio", () -> navigator.navigate(Destination.Studio.courses())));
            built = false;
            return;
        }
        headerBox.getChildren().setAll(header(course));
        if (!built) {
            built = true;
            show(headerBox, tabs(), body);
            switchTo(tab, null);
        }
        updateCounts(data);
    }

    private Node header(CourseSummary course) {
        CourseCover cover = new CourseCover(course.coverHue(), course.id(), CourseCover.Size.MEDIUM);
        Label overline = new Label(metaLine(course).toUpperCase(java.util.Locale.ROOT));
        overline.getStyleClass().addAll("text-overline", "studio-header-meta");
        Label title = new Label(course.title());
        title.getStyleClass().addAll("text-display", "studio-header-title");
        title.setWrapText(true);
        title.setMnemonicParsing(false);
        VBox text = new VBox(4, overline, title);
        text.setAlignment(Pos.CENTER_LEFT);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        Button study = new Button("Study it yourself", Icons.of(Feather.PLAY, 14));
        study.setOnAction(event -> navigator.navigate(new Destination.Course(course.id())));
        study.setMinWidth(Region.USE_PREF_SIZE);
        HBox row = new HBox(20, cover, text, study);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("studio-header");
        return row;
    }

    /** "Draft · not published · Only me", "Version 3 · Anyone with the file", "Archived". */
    static String metaLine(CourseSummary course) {
        String state = CourseCard.metaLine(course, CourseCard.Mode.CREATING);
        if (course.isArchived()) {
            return state;
        }
        return state + " · " + StudioFormats.visibilityLabel(course.visibility());
    }

    private Node tabs() {
        HBox strip = new HBox();
        strip.getStyleClass().addAll("tab-strip", "studio-tabs");
        strip.setAccessibleRole(AccessibleRole.TAB_PANE);
        for (StudioTab each : StudioTab.values()) {
            ToggleButton button = new ToggleButton(each.title());
            button.getStyleClass().setAll("tab-button");
            button.setToggleGroup(tabGroup);
            button.setUserData(each);
            button.setAccessibleRole(AccessibleRole.TAB_ITEM);
            button.setMnemonicParsing(false);
            Label count = new Label();
            count.getStyleClass().add("tab-count");
            count.setVisible(false);
            count.setManaged(false);
            button.setGraphic(count);
            button.setContentDisplay(ContentDisplay.RIGHT);
            button.setOnAction(event -> openTab(each, null));
            tabButtons.put(each, button);
            tabCounts.put(each, count);
            strip.getChildren().add(button);
        }
        // A tab stays selected: clicking the selected tab keeps it.
        tabGroup.selectedToggleProperty().addListener((obs, old, selected) -> {
            if (selected == null && old != null) {
                old.setSelected(true);
            }
        });
        strip.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            int step = event.getCode() == KeyCode.RIGHT ? 1 : event.getCode() == KeyCode.LEFT ? -1 : 0;
            if (step == 0 || event.isShortcutDown() || event.isAltDown()) {
                return;
            }
            event.consume();
            StudioTab[] all = StudioTab.values();
            StudioTab next = all[Math.floorMod(tab.ordinal() + step, all.length)];
            tabButtons.get(next).requestFocus();
            openTab(next, null);
        });
        selectButton(tab);
        return strip;
    }

    private void selectButton(StudioTab selected) {
        ToggleButton button = tabButtons.get(selected);
        if (button != null && !button.isSelected()) {
            button.setSelected(true);
        }
    }

    private void updateCounts(Header data) {
        CourseSummary course = data.course();
        setCount(StudioTab.MODULES, course.moduleCount(), null, "module", "modules");
        setCount(StudioTab.DOCUMENTS, course.documentCount(),
                course.documentsNeedingAttention() > 0 ? "warning" : null, "PDF", "PDFs");
        setCount(StudioTab.REVIEW, course.draftItemCount(), "warning", "draft to review", "drafts to review");
        setCount(StudioTab.PAGES, data.scannedPages(), "warning", "page to check", "pages to check");
        setCount(StudioTab.PUBLISH, course.openFlagCount(), "danger", "open flag", "open flags");
    }

    private void setCount(StudioTab which, int count, String tone, String singular, String plural) {
        Label label = tabCounts.get(which);
        ToggleButton button = tabButtons.get(which);
        if (label == null) {
            return;
        }
        label.setText(Formats.number(count));
        label.setVisible(count > 0);
        label.setManaged(count > 0);
        label.getStyleClass().removeAll("warning", "danger");
        if (tone != null && count > 0) {
            label.getStyleClass().add(tone);
        }
        button.setAccessibleText(count > 0
                ? which.title() + ", " + Formats.count(count, singular, plural)
                : which.title());
    }

    /** The number on a tab, or 0 when it shows none (tests). */
    int tabCount(StudioTab which) {
        Label label = tabCounts.get(which);
        return label == null || !label.isVisible() ? 0 : Integer.parseInt(label.getText().replace(",", ""));
    }

    private static Node loadingHeader() {
        HBox row = new HBox(20, Skeleton.block(64, 64), new VBox(10, Skeleton.line(160), Skeleton.block(360, 32)));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    // -------------------------------------------------------------------------------------------------
    // Review queue
    // -------------------------------------------------------------------------------------------------

    /** The review queue tab: the page draws it from a page of drafts and the course's counts. */
    private final class ReviewTab extends StudioSection {

        private final VBox root = new VBox(16, Skeleton.block(-1, 20), Skeleton.block(-1, 220), Skeleton.block(-1, 220));

        ReviewTab(StudioHost host) {
            super(host);
        }

        @Override
        Region root() {
            return root;
        }

        @Override
        void onShown() {
            String id = courseId();
            Refresher<Workbench> loads = new Refresher<>(disposer,
                    () -> new Workbench(workspace().items().reviewQueue(id, null, QUEUE_PAGE, 0),
                            workspace().items().counts(id)),
                    w -> root.getChildren().setAll(content(StudioTab.REVIEW, w)),
                    message -> root.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.SEARCH,
                            "The review queue couldn't load", message.text())));
            loads.request();
            on(ItemsChanged.class, event -> {
                if (id.equals(event.courseId())) {
                    loads.request();
                }
            });
        }
    }

    /** The content of a tab the page draws itself. */
    private Node content(StudioTab drawn, Workbench w) {
        return switch (drawn) {
            case REVIEW -> review(w.queue(), w.counts());
            default -> throw new IllegalArgumentException("Drawn by its own section: " + drawn);
        };
    }

    private Node review(List<ItemDetail> queue, ItemCounts counts) {
        if (queue.isEmpty()) {
            return new EmptyState(EmptyIllustration.Kind.CAUGHT_UP, "Nothing waiting for review",
                    "Generate questions from a module and the drafts appear here, each beside the passage it came from.");
        }
        Label summary = new Label(Formats.count(counts.draft(), "draft", "drafts") + " to review · "
                + Formats.count(counts.approved(), "question", "questions") + " approved"
                + (counts.unsupported() > 0 ? " · " + counts.unsupported() + " not backed by the material" : ""));
        summary.getStyleClass().add("text-muted");
        VBox list = new VBox(16, summary);
        for (ItemDetail detail : queue) {
            list.getChildren().add(reviewCard(detail));
        }
        return list;
    }

    private Node reviewCard(ItemDetail detail) {
        LearningItem item = detail.item();
        HBox tags = new HBox(8, new Chip(typeLabel(item.type()), Tone.NEUTRAL));
        if (item.origin() == ItemOrigin.AI) {
            tags.getChildren().add(new Chip("AI draft", Tone.ACCENT, Feather.FEATHER));
        }
        if (item.support() == SupportStatus.UNSUPPORTED) {
            tags.getChildren().add(new Chip("Not backed by the passage", Tone.WARNING, Feather.ALERT_TRIANGLE));
        }
        for (var concept : detail.concepts()) {
            tags.getChildren().add(new Chip(concept.name()));
        }
        Label prompt = new Label(item.prompt());
        prompt.getStyleClass().add("review-prompt");
        prompt.setWrapText(true);
        VBox draft = new VBox(12, tags, prompt);
        if (item.type() == ItemType.MCQ) {
            VBox options = new VBox(6);
            options.getChildren().add(option(item.answer(), true));
            item.distractors().forEach(d -> options.getChildren().add(option(d, false)));
            draft.getChildren().add(options);
        } else {
            Label answer = new Label((item.type() == ItemType.FLASHCARD ? "Back: " : "Answer: ") + item.answer());
            answer.getStyleClass().add("review-answer");
            answer.setWrapText(true);
            draft.getChildren().add(answer);
        }
        HBox.setHgrow(draft, Priority.ALWAYS);
        draft.setPrefWidth(REVIEW_COLUMN);

        VBox source = new VBox(8);
        source.getStyleClass().add("review-source");
        source.setPrefWidth(REVIEW_COLUMN);
        source.setMaxHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(source, Priority.ALWAYS);
        if (detail.source() != null) {
            Label where = new Label(detail.source().ref().documentTitle() + " · "
                    + Formats.pages(detail.source().ref().pageStart(), detail.source().ref().pageEnd()));
            where.getStyleClass().add("text-caption");
            Label passage = new Label(detail.source().text());
            passage.getStyleClass().addAll("text-reading", "review-passage");
            passage.setWrapText(true);
            source.getChildren().addAll(where, passage);
        } else {
            Label none = new Label("Written by hand, without a source passage.");
            none.getStyleClass().add("text-caption");
            source.getChildren().add(none);
        }

        Button approve = new Button("Approve", Icons.of(Feather.CHECK, 14));
        approve.getStyleClass().addAll("positive", "small");
        approve.setOnAction(event -> act(() -> workspace().items().approve(item.id()), "Approved"));
        Button edit = new Button("Edit");
        edit.getStyleClass().add("small");
        edit.setOnAction(event -> Toasts.info(page(), "Change the wording, then approve it."));
        Button regenerate = new Button("Regenerate", Icons.of(Feather.REFRESH_CW, 14));
        regenerate.getStyleClass().add("small");
        regenerate.setOnAction(event -> act(() -> workspace().generation().regenerate(item.id()), "Writing a new draft"));
        Button reject = new Button("Reject");
        reject.getStyleClass().addAll("quiet", "small", "danger-text");
        reject.setOnAction(event -> act(() -> workspace().items().reject(item.id()), "Rejected"));
        HBox actions = new HBox(8, approve, edit, regenerate, spacer(), reject);
        actions.setAlignment(Pos.CENTER_LEFT);

        HBox columns = new HBox(28, draft, source);
        Card card = new Card(columns, actions);
        card.setInteractive(false);
        card.getStyleClass().add("review-card");
        card.setSpacing(20);
        return card;
    }

    private void act(Runnable work, String done) {
        run(() -> {
            work.run();
            return null;
        }, ignored -> Toasts.success(page(), done), message -> Toasts.error(page(), message));
    }

    private static Node option(String text, boolean correct) {
        Label label = new Label(text, Icons.of(correct ? Feather.CHECK_CIRCLE : Feather.CIRCLE, 14));
        label.getStyleClass().addAll("review-option", correct ? "correct" : "distractor");
        label.setWrapText(true);
        return label;
    }

    static String typeLabel(ItemType type) {
        return switch (type) {
            case MCQ -> "Multiple choice";
            case TRUE_FALSE -> "True or false";
            case SHORT_ANSWER -> "Short answer";
            case FLASHCARD -> "Flashcard";
            case CLOZE -> "Fill in the blank";
        };
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
