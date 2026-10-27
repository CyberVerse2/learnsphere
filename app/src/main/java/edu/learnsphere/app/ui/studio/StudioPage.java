package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.CourseCard;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.ResponsiveGrid;
import edu.learnsphere.app.ui.components.SegmentedControl;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.StatusPill;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.CourseDetail;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.ItemCounts;
import edu.learnsphere.core.service.ItemDetail;
import edu.learnsphere.core.service.ModuleSummary;
import edu.learnsphere.core.service.RenderedPage;
import java.io.ByteArrayInputStream;
import java.util.List;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The Studio, where creators work. Without a course it lists the courses created here; with one it is a
 * working surface in tabs: details and cover, modules, PDFs with live status, the review queue (each AI
 * draft beside the passage it came from, with Approve, Edit, Regenerate and Reject), scanned pages to
 * check, and publishing.
 */
public final class StudioPage extends PageView {

    /** Drafts shown in the review queue at once. */
    static final int QUEUE_PAGE = 6;
    /** Starting width of each review card column; both grow to share the card. */
    private static final double REVIEW_COLUMN = 420;

    private final Destination.Studio destination;
    private final VBox body = new VBox(24);

    private record Workbench(CourseDetail detail, List<DocumentSummary> documents, ItemCounts counts,
                             List<ItemDetail> queue, List<CourseVersion> versions, ScannedPage scanned) {
    }

    private record ScannedPage(DocumentSummary document, DocumentPage page, RenderedPage image) {
    }

    public StudioPage(AppContext context, Navigator navigator, Destination.Studio destination) {
        super(context, navigator);
        this.destination = destination;
        show(Skeleton.block(260, 40), Skeleton.block(-1, 44), Skeleton.block(-1, 320));
    }

    @Override
    public void onShown() {
        if (destination.courseId() == null) {
            load(() -> workspace().courses().list(CourseFilter.creating()), this::renderCourses);
            on(CourseChanged.class, event -> load(() -> workspace().courses().list(CourseFilter.creating()),
                    this::renderCourses));
            return;
        }
        refresh();
        on(DocumentChanged.class, event -> refresh());
        on(ItemsChanged.class, event -> refresh());
    }

    private void refresh() {
        String id = destination.courseId();
        load(() -> {
            var ws = workspace();
            CourseDetail detail = ws.courses().get(id);
            List<DocumentSummary> documents = ws.documents().list(id);
            ScannedPage scanned = null;
            if (destination.tab() == StudioTab.PAGES) {
                for (DocumentSummary document : documents) {
                    List<DocumentPage> pages = ws.documents().pagesNeedingReview(document.id());
                    if (!pages.isEmpty()) {
                        DocumentPage page = pages.getFirst();
                        scanned = new ScannedPage(document, page, ws.documents().renderPage(document.id(), page.pageNumber(), 1.5));
                        break;
                    }
                }
            }
            return new Workbench(detail, documents, ws.items().counts(id),
                    destination.tab() == StudioTab.REVIEW ? ws.items().reviewQueue(id, null, QUEUE_PAGE, 0) : List.of(),
                    ws.courses().versions(id), scanned);
        }, this::renderWorkbench);
    }

    // -------------------------------------------------------------------------------------------------
    // Courses created here
    // -------------------------------------------------------------------------------------------------

    private void renderCourses(List<CourseSummary> courses) {
        Button create = new Button("New course", Icons.of(Feather.PLUS));
        create.getStyleClass().add("primary");
        create.setOnAction(event -> Toasts.info(page(), "Name your course to start adding PDFs."));
        PageHeader header = new PageHeader("Studio")
                .subtitle("Turn your PDFs into a course: check what the study assistant drafts, then publish and share it.")
                .actions(create);
        if (courses.isEmpty()) {
            show(header, new EmptyState(EmptyIllustration.Kind.DOCUMENTS, "Make your first course",
                    "Add the PDFs you teach from. The study assistant drafts questions and flashcards for you to check.")
                    .primary("New course", () -> Toasts.info(page(), "Name your course to start adding PDFs.")));
            return;
        }
        ResponsiveGrid grid = ResponsiveGrid.forCards();
        for (CourseSummary course : courses) {
            CourseCard card = new CourseCard(course, CourseCard.Mode.CREATING);
            card.setOnOpen(() -> navigator.navigate(new Destination.Studio(course.id(), StudioTab.REVIEW)));
            grid.getChildren().add(card);
        }
        show(header, grid);
    }

    // -------------------------------------------------------------------------------------------------
    // One course
    // -------------------------------------------------------------------------------------------------

    private void renderWorkbench(Workbench w) {
        CourseSummary course = w.detail().summary();
        CourseCover cover = new CourseCover(course.coverHue(), course.id(), CourseCover.Size.SMALL);
        Button preview = new Button("Study it yourself", Icons.of(Feather.PLAY));
        preview.setOnAction(event -> navigator.navigate(new Destination.Course(course.id())));
        Button publish = new Button("Publish", Icons.of(Feather.UPLOAD_CLOUD));
        publish.getStyleClass().add("primary");
        publish.setOnAction(event -> navigator.navigate(new Destination.Studio(course.id(), StudioTab.PUBLISH)));
        PageHeader header = new PageHeader(course.title())
                .overline(CourseCard.metaLine(course, CourseCard.Mode.CREATING))
                .actions(preview, publish);
        HBox top = new HBox(20, cover, header);
        top.setAlignment(Pos.BOTTOM_LEFT);
        HBox.setHgrow(header, Priority.ALWAYS);
        top.getStyleClass().add("studio-top");

        Node content = switch (destination.tab()) {
            case DETAILS -> details(w.detail());
            case MODULES -> modules(w.detail().modules());
            case DOCUMENTS -> documents(w.documents());
            case REVIEW -> review(w.queue(), w.counts());
            case PAGES -> pages(w.scanned());
            case PUBLISH -> publishing(w.detail(), w.counts(), w.versions());
        };
        body.getChildren().setAll(content);
        show(top, tabs(w), body);
    }

    private Node tabs(Workbench w) {
        HBox strip = new HBox(4);
        strip.getStyleClass().add("tab-strip");
        ToggleGroup group = new ToggleGroup();
        int scannedPages = w.documents().stream().mapToInt(DocumentSummary::pagesNeedingReview).sum();
        for (StudioTab tab : StudioTab.values()) {
            ToggleButton button = new ToggleButton(tab.title());
            button.getStyleClass().setAll("tab-button");
            button.setToggleGroup(group);
            button.setSelected(tab == destination.tab());
            int count = switch (tab) {
                case REVIEW -> w.counts().draft();
                case PAGES -> scannedPages;
                case DOCUMENTS -> w.documents().size();
                case MODULES -> w.detail().modules().size();
                default -> 0;
            };
            if (count > 0) {
                Label badge = new Label(Integer.toString(count));
                badge.getStyleClass().addAll("tab-count", tab == StudioTab.PAGES ? "warning" : "neutral");
                button.setGraphic(badge);
                button.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
            }
            button.setOnAction(event -> {
                button.setSelected(true);
                navigator.replace(new Destination.Studio(destination.courseId(), tab), true);
            });
            strip.getChildren().add(button);
        }
        return strip;
    }

    private Node details(CourseDetail detail) {
        var course = detail.course();
        TextField title = new TextField(course.title());
        TextField subtitle = new TextField(course.subtitle() == null ? "" : course.subtitle());
        TextArea description = new TextArea(course.description() == null ? "" : course.description());
        description.setWrapText(true);
        description.setPrefRowCount(4);
        TextField author = new TextField(course.authorName());
        CourseCover cover = new CourseCover(course.coverHue(), course.id(), CourseCover.Size.CARD);
        Slider hue = new Slider(0, 359, course.coverHue());
        hue.getStyleClass().add("hue-slider");
        hue.valueProperty().addListener((obs, old, value) -> cover.setHue(value.intValue()));
        hue.setAccessibleText("Cover colour");
        Button save = new Button("Save changes");
        save.getStyleClass().add("primary");
        save.setOnAction(event -> run(() -> {
            workspace().courses().update(course.id(), new edu.learnsphere.core.service.CourseUpdate(title.getText(),
                    subtitle.getText(), description.getText(), author.getText()));
            workspace().courses().setCoverHue(course.id(), (int) Math.round(hue.getValue()));
            return null;
        }, ignored -> Toasts.success(page(), "Changes saved"), message -> Toasts.error(page(), message)));
        VBox form = new VBox(18, field("Title", title), field("Subtitle", subtitle), field("Description", description),
                field("Author", author), save);
        HBox.setHgrow(form, Priority.ALWAYS);
        Label coverLabel = new Label("Cover");
        coverLabel.getStyleClass().add("field-label");
        Label coverHint = new Label("Every course gets its own cover. Slide to change its colour.");
        coverHint.getStyleClass().add("text-caption");
        coverHint.setWrapText(true);
        VBox coverBox = new VBox(10, coverLabel, cover, hue, coverHint);
        coverBox.setMinWidth(300);
        coverBox.setMaxWidth(300);
        HBox row = new HBox(40, form, coverBox);
        return panel(row);
    }

    private Node modules(List<ModuleSummary> modules) {
        VBox list = new VBox();
        list.getStyleClass().add("row-list");
        for (ModuleSummary m : modules) {
            Label handle = new Label(null, Icons.of(Feather.MENU, 16));
            handle.getStyleClass().add("drag-handle");
            Label position = new Label(Integer.toString(m.module().position() + 1));
            position.getStyleClass().add("module-number");
            Label title = new Label(m.module().title());
            title.getStyleClass().add("medium");
            Label summary = new Label(m.module().summary() == null ? "" : m.module().summary());
            summary.getStyleClass().add("text-caption");
            VBox text = new VBox(2, title, summary);
            HBox.setHgrow(text, Priority.ALWAYS);
            Label counts = new Label(Formats.count(m.documentCount(), "PDF", "PDFs") + " · "
                    + Formats.count(m.itemCount(), "question", "questions"));
            counts.getStyleClass().add("text-caption");
            HBox row = new HBox(14, handle, position, text, counts);
            if (m.draftItemCount() > 0) {
                row.getChildren().add(new Chip(Formats.count(m.draftItemCount(), "draft", "drafts"), Tone.WARNING));
            }
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("list-row");
            if (list.getChildren().isEmpty()) {
                row.getStyleClass().add("first");
            }
            list.getChildren().add(row);
        }
        Button add = new Button("Add module", Icons.of(Feather.PLUS));
        add.getStyleClass().add("small");
        add.setOnAction(event -> Toasts.info(page(), "Name the module to add it at the end."));
        return new VBox(16, panel(list), add);
    }

    private Node documents(List<DocumentSummary> documents) {
        if (documents.isEmpty()) {
            return new EmptyState(EmptyIllustration.Kind.DOCUMENTS, "Add the PDFs this course is built from",
                    "LearnSphere reads each one so learners can ask about it and you can make questions from it.")
                    .primary("Add PDF", () -> Toasts.info(page(), "Choose the PDFs to add."));
        }
        VBox list = new VBox();
        list.getStyleClass().add("row-list");
        for (DocumentSummary d : documents) {
            StackPane icon = new StackPane(Icons.of(Feather.FILE_TEXT, 18));
            icon.getStyleClass().add("icon-badge");
            Label title = new Label(d.title());
            title.getStyleClass().add("medium");
            String meta = Formats.count(d.pageCount(), "page", "pages")
                    + (d.moduleTitle() == null ? " · Whole course" : " · " + d.moduleTitle());
            Label detail = new Label(meta);
            detail.getStyleClass().add("text-caption");
            VBox text = new VBox(2, title, detail);
            HBox.setHgrow(text, Priority.ALWAYS);
            StatusPill pill = StatusPill.forDocument(d);
            VBox status = new VBox(6, pill);
            status.setAlignment(Pos.CENTER_RIGHT);
            status.setMinWidth(220);
            if (StatusPill.statusOf(d.status()) == StatusPill.Status.WORKING) {
                ProgressBarSlim bar = new ProgressBarSlim();
                bar.setProgress(d.progress());
                bar.setPrefWidth(200);
                bar.setMaxWidth(200);
                status.getChildren().add(bar);
            }
            HBox row = new HBox(14, icon, text, status);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("list-row");
            if (list.getChildren().isEmpty()) {
                row.getStyleClass().add("first");
            }
            if (d.status() == DocumentStatus.FAILED) {
                Button retry = new Button("Try again");
                retry.getStyleClass().add("small");
                retry.setOnAction(event -> run(() -> {
                    workspace().documents().retry(d.id());
                    return null;
                }, ignored -> { }, message -> Toasts.error(page(), message)));
                row.getChildren().add(retry);
            } else if (d.status() == DocumentStatus.NEEDS_REVIEW) {
                Button check = new Button("Check pages");
                check.getStyleClass().add("small");
                check.setOnAction(event -> navigator.replace(new Destination.Studio(d.courseId(), StudioTab.PAGES), true));
                row.getChildren().add(check);
            }
            list.getChildren().add(row);
        }
        Button add = new Button("Add PDF", Icons.of(Feather.PLUS));
        add.getStyleClass().add("small");
        add.setOnAction(event -> Toasts.info(page(), "Choose the PDFs to add."));
        return new VBox(16, panel(list), add);
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
        approve.getStyleClass().addAll("primary", "small");
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

    private Node pages(ScannedPage scanned) {
        if (scanned == null) {
            return new EmptyState(EmptyIllustration.Kind.CAUGHT_UP, "No pages to check",
                    "Pages read from scanned images appear here so you can check the text before learners rely on it.");
        }
        Label note = new Label("These pages were read from a scanned image. Check the text before learners rely on it.");
        note.getStyleClass().add("text-muted");
        note.setWrapText(true);
        Label where = new Label(scanned.document().title() + " · page " + scanned.page().pageNumber());
        where.getStyleClass().add("text-heading");
        ImageView image = new ImageView(new Image(new ByteArrayInputStream(scanned.image().png())));
        image.setPreserveRatio(true);
        image.setFitWidth(420);
        StackPane imageBox = new StackPane(image);
        imageBox.getStyleClass().add("scanned-page");
        Label readLabel = new Label("Read by AI from the scanned page");
        readLabel.getStyleClass().add("field-label");
        TextArea text = new TextArea(scanned.page().text());
        text.setWrapText(true);
        text.getStyleClass().add("page-text");
        VBox.setVgrow(text, Priority.ALWAYS);
        Button approve = new Button("Looks right", Icons.of(Feather.CHECK, 14));
        approve.getStyleClass().add("primary");
        String documentId = scanned.document().id();
        int number = scanned.page().pageNumber();
        String original = scanned.page().text();
        approve.setOnAction(event -> act(() -> {
            if (text.getText().equals(original)) {
                workspace().documents().approvePages(documentId, List.of(number));
            } else {
                workspace().documents().editPageText(documentId, number, text.getText());
            }
        }, "Page checked"));
        VBox editor = new VBox(10, readLabel, text, approve);
        HBox.setHgrow(editor, Priority.ALWAYS);
        HBox columns = new HBox(28, imageBox, editor);
        return new VBox(16, note, panel(new VBox(16, where, columns)));
    }

    private Node publishing(CourseDetail detail, ItemCounts counts, List<CourseVersion> versions) {
        var course = detail.course();
        int next = course.versionNumber() + 1;
        Label ready = new Label(counts.approved() == 0 ? "Approve at least one question to publish."
                : Formats.count(counts.approved(), "approved question", "approved questions") + " will be in version " + next + ".");
        ready.getStyleClass().add("text-muted");
        TextArea notes = new TextArea();
        notes.setPromptText("What's new in this version (optional)");
        notes.setPrefRowCount(2);
        notes.setWrapText(true);
        Button publish = new Button("Publish version " + next);
        publish.getStyleClass().add("primary");
        publish.setDisable(counts.approved() == 0);
        publish.setOnAction(event -> run(() -> workspace().courses().publish(course.id(), notes.getText()),
                version -> Toasts.success(page(), "Published version " + version.number()),
                message -> Toasts.error(page(), message)));
        VBox publishBox = new VBox(14, heading("Publish"), ready, notes, publish);

        SegmentedControl<Visibility> visibility = new SegmentedControl<>(List.of(Visibility.values()), v -> switch (v) {
            case PRIVATE -> "Only me";
            case SHAREABLE -> "Anyone with the file";
            case PROTECTED -> "With a code";
        });
        visibility.setValue(course.visibility());
        visibility.valueProperty().addListener((obs, old, value) -> run(() -> {
            workspace().courses().setVisibility(course.id(), value);
            return null;
        }, ignored -> { }, message -> Toasts.error(page(), message)));
        Button export = new Button("Export course", Icons.of(Feather.SHARE, 14));
        export.setDisable(course.versionNumber() == 0);
        export.setOnAction(event -> Toasts.info(page(), "Choose where to save the course file."));
        Label shareNote = new Label("Learners open the file in LearnSphere. Their progress stays on their computer.");
        shareNote.getStyleClass().add("text-caption");
        shareNote.setWrapText(true);
        VBox shareBox = new VBox(14, heading("Share"), visibility, shareNote, export);

        VBox history = new VBox(0, heading("Versions"));
        if (versions.isEmpty()) {
            Label none = new Label("Not published yet.");
            none.getStyleClass().add("text-muted");
            history.getChildren().add(none);
        }
        for (CourseVersion v : versions) {
            Label number = new Label("Version " + v.number());
            number.getStyleClass().add("medium");
            Label when = new Label(Formats.ago(v.publishedAt(), context.clock()));
            when.getStyleClass().add("text-caption");
            Label notesLabel = new Label(v.notes() == null || v.notes().isBlank() ? "No notes" : v.notes());
            notesLabel.getStyleClass().add("text-muted");
            notesLabel.setWrapText(true);
            VBox row = new VBox(2, new HBox(10, number, when), notesLabel);
            row.getStyleClass().add("version-row");
            history.getChildren().add(row);
        }
        GridPane grid = new GridPane();
        grid.setHgap(24);
        grid.setVgap(24);
        Node publishPanel = panel(publishBox);
        Node sharePanel = panel(shareBox);
        Node historyPanel = panel(history);
        grid.add(publishPanel, 0, 0);
        grid.add(sharePanel, 1, 0);
        grid.add(historyPanel, 0, 1, 2, 1);
        var half = new javafx.scene.layout.ColumnConstraints();
        half.setPercentWidth(50);
        grid.getColumnConstraints().addAll(half, half);
        return grid;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("text-heading");
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

    private static Node field(String label, Node control) {
        Label text = new Label(label);
        text.getStyleClass().add("field-label");
        return new VBox(6, text, control);
    }

    private static Card panel(Node content) {
        Card card = new Card(content);
        card.setInteractive(false);
        card.getStyleClass().add("panel");
        card.setMaxHeight(Double.MAX_VALUE);
        return card;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }
}
