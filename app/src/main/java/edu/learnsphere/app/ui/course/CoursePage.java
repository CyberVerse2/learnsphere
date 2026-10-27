package edu.learnsphere.app.ui.course;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.ProgressRing;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.app.ui.library.CourseActions;
import edu.learnsphere.app.ui.library.LiveLoad;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.DocumentChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.events.SettingChanged;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.CourseDetail;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.ItemFilter;
import edu.learnsphere.core.service.ModuleSummary;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.StudyNext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;

/**
 * One course, laid out like the title page of a book: the cover beside the title, author and version, with
 * Study and Ask as the two main actions. Below it the modules form a path that fills as they are studied, the
 * course's PDFs open in Ask's reader, and a side column shows today's work, ways to practise and the concepts
 * from weakest to strongest. A course updated by a newer import says so; a course made here links to the
 * Studio; a course the learner left can be studied again.
 */
public final class CoursePage extends PageView {

    /** How long the note about an imported update stays on the page. */
    public static final Duration UPDATE_NOTE_DAYS = Duration.ofDays(30);
    /** Description characters shown before Show more. */
    static final int DESCRIPTION_PREVIEW = 260;
    /** Concept chips shown before the link to Progress. */
    static final int CONCEPT_LIMIT = 10;

    /** One load of the page; {@code hasFlashcards} is true when the course has approved flashcards. */
    private record Data(CourseDetail detail, StudyNext next, List<DocumentSummary> documents,
                        List<CourseVersion> versions, boolean hasFlashcards) {
    }

    private final Destination.Course destination;
    private final CourseActions actions;
    private final LiveLoad<Data> load;
    private final Map<String, DocumentStatus> documentStatus = new HashMap<>();
    private boolean opened;
    private boolean listening;
    private Data data;
    private ModulePath path;
    private Button primary;
    private Button ask;
    private ActionRow flashcardsRow;

    public CoursePage(AppContext context, Navigator navigator, Destination.Course destination) {
        super(context, navigator);
        this.destination = Objects.requireNonNull(destination, "destination");
        page().getStylesheets().add(Objects.requireNonNull(CoursePage.class.getResource("course.css"), "course.css")
                .toExternalForm());
        page().getStyleClass().add("course-page");
        actions = new CourseActions(context, navigator, this::page, disposer);
        String id = destination.courseId();
        load = new LiveLoad<>(() -> {
            if (!opened) {
                workspace().courses().markOpened(id);
            }
            CourseDetail detail = workspace().courses().get(id);
            boolean flashcards = detail.summary().itemCount() > 0 && !workspace().items().list(ItemFilter.forCourse(id)
                    .withStatuses(ItemStatus.APPROVED).withTypes(ItemType.FLASHCARD).withPage(1, 0)).isEmpty();
            return new Data(detail, workspace().practice().studyNext(id), workspace().documents().list(id),
                    workspace().courses().versions(id), flashcards);
        }, this::render, this::failed, disposer);

        Region cover = Skeleton.block(300, 200);
        VBox lines = new VBox(12, Skeleton.line(160), Skeleton.block(420, 40), Skeleton.text(2));
        HBox head = new HBox(40, cover, lines);
        HBox body = new HBox(32, new VBox(16, Skeleton.line(120), Skeleton.text(4)), Skeleton.block(340, 220));
        HBox.setHgrow(body.getChildren().getFirst(), Priority.ALWAYS);
        show(head, body);
    }

    @Override
    public void onShown() {
        // Listen first, so a change published while the first load runs still reloads the page.
        listen();
        load.request();
    }

    // ---- what tests reach ---------------------------------------------------------------------

    /** The module path, once loaded. */
    public ModulePath modulePath() {
        return path;
    }

    /** The primary action: Start studying, Practise weak concepts when caught up, or Ask. */
    public Button primaryButton() {
        return primary;
    }

    public Button askButton() {
        return ask;
    }

    /** The Practise card's Flashcards row, or null while the course has no questions. */
    public ActionRow flashcardsRow() {
        return flashcardsRow;
    }

    /** The loaded course, or null. */
    public CourseDetail detail() {
        return data == null ? null : data.detail();
    }

    /** Finished loads (tests wait for a reload). */
    public int loads() {
        return load.completed();
    }

    // ---- loading ------------------------------------------------------------------------------

    private void listen() {
        if (listening) {
            return;
        }
        listening = true;
        String id = destination.courseId();
        on(CourseChanged.class, event -> {
            if (id.equals(event.courseId())) {
                load.request();
            }
        });
        on(ProgressChanged.class, event -> {
            if (event.courseId() == null || id.equals(event.courseId())) {
                load.request();
            }
        });
        on(ItemsChanged.class, event -> {
            if (id.equals(event.courseId())) {
                load.request();
            }
        });
        on(SettingChanged.class, event -> load.request());
        on(DocumentChanged.class, event -> {
            if (id.equals(event.courseId())
                    && documentStatus.put(event.documentId(), event.status()) != event.status()) {
                load.request();
            }
        });
    }

    private void failed(UserMessage message) {
        if (UserMessage.COURSE_NOT_FOUND.equals(message)) {
            show(new EmptyState(EmptyIllustration.Kind.SEARCH, "This course isn't in your library",
                    "Your courses are all in the Library.")
                    .primary("Go to Library", () -> navigator.navigate(Destination.LIBRARY)));
        } else {
            showError(message);
        }
    }

    private void render(Data loaded) {
        opened = true;
        data = loaded;
        flashcardsRow = null;
        loaded.documents().forEach(d -> documentStatus.put(d.id(), d.status()));
        CourseDetail detail = loaded.detail();
        CourseSummary course = detail.summary();

        List<Node> sections = new ArrayList<>();
        sections.add(hero(loaded));
        Node notice = notice(loaded);
        if (notice != null) {
            sections.add(notice);
        }
        VBox main = new VBox(40, modulesSection(detail));
        Node material = materialSection(course, loaded.documents());
        if (material != null) {
            main.getChildren().add(material);
        }
        HBox.setHgrow(main, Priority.ALWAYS);
        main.setMinWidth(0);
        VBox side = new VBox(20, todayCard(loaded));
        if (course.itemCount() > 0) {
            side.getChildren().add(practiseCard(detail, loaded.hasFlashcards()));
        }
        if (!detail.concepts().isEmpty()) {
            side.getChildren().add(conceptsCard(detail));
        }
        side.getStyleClass().add("course-side");
        side.setMinWidth(300);
        side.setPrefWidth(340);
        side.setMaxWidth(340);
        HBox columns = new HBox(40, main, side);
        columns.getStyleClass().add("course-columns");
        sections.add(columns);
        show(sections.toArray(Node[]::new));
    }

    // ---- header -------------------------------------------------------------------------------

    private Node hero(Data loaded) {
        CourseDetail detail = loaded.detail();
        CourseSummary course = detail.summary();
        CourseCover cover = new CourseCover(course.coverHue(), course.id(), CourseCover.Size.BANNER);
        StackPane coverBox = new StackPane(cover);
        coverBox.getStyleClass().add("course-hero-cover");
        coverBox.setMinWidth(300);
        coverBox.setPrefWidth(300);
        coverBox.setMaxWidth(300);
        coverBox.setMaxHeight(Region.USE_PREF_SIZE);

        Label meta = new Label(metaLine(course).toUpperCase(java.util.Locale.ROOT));
        meta.getStyleClass().addAll("text-overline", "course-hero-meta");
        Label title = new Label(course.title());
        title.getStyleClass().addAll("text-display", "course-hero-title");
        title.setWrapText(true);
        VBox text = new VBox(8, meta, title);
        if (!course.subtitle().isBlank()) {
            Label subtitle = new Label(course.subtitle());
            subtitle.getStyleClass().addAll("serif", "course-hero-subtitle");
            subtitle.setWrapText(true);
            text.getChildren().add(subtitle);
        }
        String description = detail.course().description().strip();
        if (!description.isEmpty()) {
            text.getChildren().add(description(description));
        }

        HBox buttons = actionsRow(loaded);
        VBox info = new VBox(20, text, buttons);
        info.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(info, Priority.ALWAYS);
        info.setMinWidth(0);
        HBox hero = new HBox(40, coverBox, info);
        hero.setAlignment(Pos.CENTER_LEFT);
        hero.getStyleClass().add("course-hero");
        return hero;
    }

    private Node description(String description) {
        Label label = new Label();
        label.getStyleClass().add("course-hero-description");
        label.setWrapText(true);
        label.setMaxWidth(640);
        if (description.length() <= DESCRIPTION_PREVIEW) {
            label.setText(description);
            return label;
        }
        label.setText(Formats.shorten(description, DESCRIPTION_PREVIEW));
        Button more = new Button("Show more");
        more.getStyleClass().addAll("quiet", "small", "course-hero-more");
        more.setOnAction(event -> {
            boolean expand = more.getText().equals("Show more");
            label.setText(expand ? description : Formats.shorten(description, DESCRIPTION_PREVIEW));
            more.setText(expand ? "Show less" : "Show more");
        });
        VBox box = new VBox(2, label, more);
        box.setAlignment(Pos.TOP_LEFT);
        return box;
    }

    /** "Ada Lovelace · Version 3", "Created by you · Draft", … */
    static String metaLine(CourseSummary course) {
        String who = course.createdHere() ? "Created by you"
                : course.authorName().isBlank() ? "Shared course" : course.authorName();
        String version = course.versionNumber() > 0 ? "Version " + course.versionNumber()
                : course.createdHere() ? "Draft" : null;
        return version == null ? who : who + " · " + version;
    }

    private HBox actionsRow(Data loaded) {
        CourseSummary course = loaded.detail().summary();
        StudyNext next = loaded.next();
        boolean archived = course.isArchived();
        ask = new Button("Ask a question", Icons.of(Feather.MESSAGE_CIRCLE, 16));
        ask.getStyleClass().add("large");
        ask.setOnAction(event -> actions.ask(course));
        Button studio = new Button("Edit in Studio", Icons.of(Feather.EDIT_2, 16));
        studio.getStyleClass().add("large");
        studio.setOnAction(event -> actions.openStudio(course));

        List<Node> row = new ArrayList<>();
        if (course.createdHere() && course.itemCount() == 0) {
            primary = studio;
            row.add(studio);
            row.add(ask);
        } else if (!course.enrolled() && !archived) {
            if (course.itemCount() > 0 && !next.caughtUp()) {
                // Starts studying the course and opens the session straight away.
                primary = new Button("Start studying", Icons.of(Feather.PLAY, 16));
                primary.setOnAction(event -> actions.enrolAndStudy(course));
            } else {
                // Nothing to study today: the course joins Home and Study next, with the Library menu's words.
                primary = new Button(CourseActions.enrolLabel(course), Icons.of(Feather.PLUS, 16));
                primary.setOnAction(event -> actions.enrol(course));
            }
            row.add(primary);
            row.add(ask);
        } else if (course.enrolled() && !next.caughtUp()) {
            primary = new Button("Start studying", Icons.of(Feather.PLAY, 16));
            primary.setOnAction(event -> navigator.navigate(new Destination.Study(SessionRequest.studyNext(course.id()))));
            row.add(primary);
            row.add(ask);
        } else if (course.enrolled() && loaded.detail().concepts().stream().anyMatch(ConceptMastery::isWeak)) {
            primary = new Button("Practise weak concepts", Icons.of(Feather.TARGET, 16));
            primary.setOnAction(event -> navigator.navigate(
                    new Destination.Study(SessionRequest.weakConcepts(course.id()))));
            row.add(primary);
            row.add(ask);
        } else {
            primary = ask;
            row.add(ask);
        }
        primary.getStyleClass().addAll("primary", "large");
        if (course.createdHere() && primary != studio) {
            row.add(studio);
        }
        IconButton more = new IconButton(Feather.MORE_HORIZONTAL, "More for this course");
        more.getStyleClass().add("course-hero-more-button");
        more.setOnAction(event -> {
            ContextMenu menu = actions.menu(course, CourseActions.Place.COURSE_PAGE);
            if (!menu.getItems().isEmpty()) {
                menu.show(more, Side.BOTTOM, 0, 6);
            }
        });
        if (!actions.menu(course, CourseActions.Place.COURSE_PAGE).getItems().isEmpty()) {
            row.add(more);
        }
        HBox buttons = new HBox(12);
        buttons.getChildren().setAll(row);
        buttons.setAlignment(Pos.CENTER_LEFT);
        buttons.getStyleClass().add("course-hero-actions");
        return buttons;
    }

    // ---- notices ------------------------------------------------------------------------------

    /** Archived, or updated by a newer import in the last weeks; null when neither. */
    private Node notice(Data loaded) {
        CourseSummary course = loaded.detail().summary();
        if (course.isArchived()) {
            Button restore = new Button("Restore course");
            restore.getStyleClass().add("small");
            restore.setOnAction(event -> actions.unarchive(course));
            return notice(Tone.WARNING, Feather.ARCHIVE, "This course is archived",
                    "It's kept on this computer. Find it in the Library under Creating with Show archived.", restore);
        }
        CourseVersion update = recentUpdate(course, loaded.versions(), context.clock().millis());
        if (update != null) {
            String title = "Updated to version " + update.number() + " · " + Formats.ago(update.createdAt(), context.clock());
            String notes = update.notes().isBlank() ? "Your progress carried over to the new version."
                    : Formats.shorten(update.notes(), 220);
            return notice(Tone.ACCENT, Feather.REFRESH_CW, title, notes, null);
        }
        return null;
    }

    /**
     * The version an imported course was updated to in the last {@link #UPDATE_NOTE_DAYS}, or null: the
     * course's own version, when it is the newest of several and arrived recently. Going back to an older
     * version is not an update.
     */
    static CourseVersion recentUpdate(CourseSummary course, List<CourseVersion> versions, long now) {
        if (course.origin() != CourseOrigin.IMPORTED || versions.size() < 2) {
            return null;
        }
        CourseVersion current = versions.stream().filter(v -> v.number() == course.versionNumber())
                .findFirst().orElse(null);
        if (current == null || versions.stream().anyMatch(v -> v.number() > current.number())) {
            return null;
        }
        return now - current.createdAt() < UPDATE_NOTE_DAYS.toMillis() ? current : null;
    }

    /** A tinted band under the header: an icon, a short title, a sentence and an optional button. */
    private static Node notice(Tone tone, Ikon icon, String title, String text, Node action) {
        var glyph = Icons.of(icon, 18);
        glyph.getStyleClass().add("course-notice-icon");
        Label heading = new Label(title);
        heading.getStyleClass().addAll("semibold", "course-notice-title");
        heading.setWrapText(true);
        Label body = new Label(text);
        body.getStyleClass().add("course-notice-text");
        body.setWrapText(true);
        VBox words = new VBox(2, heading, body);
        HBox.setHgrow(words, Priority.ALWAYS);
        HBox box = new HBox(14, glyph, words);
        if (action != null) {
            box.getChildren().add(action);
        }
        box.setAlignment(Pos.CENTER_LEFT);
        box.getStyleClass().add("course-notice");
        Tone.apply(box, tone);
        return box;
    }

    // ---- modules and material -------------------------------------------------------------------

    private Node modulesSection(CourseDetail detail) {
        CourseSummary course = detail.summary();
        SectionHeader header = new SectionHeader("Modules");
        if (course.itemCount() > 0) {
            long studied = Math.round(course.progress() * course.itemCount());
            header.subtitle(Formats.number(studied) + " of " + Formats.count(course.itemCount(), "question", "questions")
                    + " studied");
        }
        if (detail.modules().isEmpty()) {
            path = new ModulePath(List.of(), null);
            Label none = new Label(course.createdHere()
                    ? "This course has no modules yet. Add them in the Studio, then add PDFs to each one."
                    : "This course has no modules yet.");
            none.getStyleClass().addAll("text-muted", "course-none");
            none.setWrapText(true);
            return new VBox(16, header, none);
        }
        path = new ModulePath(detail.modules(), course.itemCount() == 0 ? null
                : module -> navigator.navigate(new Destination.Study(SessionRequest.moduleQuiz(course.id(), module.id()))));
        return new VBox(20, header, path);
    }

    /** The course's PDFs; a learner sees the ones ready to read. Null when there is nothing to list. */
    private Node materialSection(CourseSummary course, List<DocumentSummary> documents) {
        List<DocumentSummary> shown = course.createdHere() ? documents
                : documents.stream().filter(DocumentSummary::isReady).toList();
        SectionHeader header = new SectionHeader("Material")
                .subtitle("Open a PDF to read it in Ask, beside your questions.");
        if (shown.isEmpty()) {
            if (!course.createdHere()) {
                return null;
            }
            Label none = new Label("Add lecture notes or textbook chapters in the Studio. They appear here, "
                    + "ready to read and ask about.");
            none.getStyleClass().addAll("text-muted", "course-none");
            none.setWrapText(true);
            return new VBox(16, header, none);
        }
        VBox rows = new VBox();
        rows.getStyleClass().add("course-material");
        for (DocumentSummary document : shown) {
            if (!rows.getChildren().isEmpty()) {
                Region rule = new Region();
                rule.getStyleClass().add("course-material-rule");
                rows.getChildren().add(rule);
            }
            rows.getChildren().add(materialRow(document));
        }
        Card card = new Card(rows);
        card.setInteractive(false);
        card.getStyleClass().add("course-material-card");
        return new VBox(16, header, card);
    }

    private Node materialRow(DocumentSummary document) {
        String where = document.moduleTitle() == null ? "Whole course" : document.moduleTitle();
        String state = document.isReady() ? Formats.count(document.pageCount(), "page", "pages")
                : document.isProcessing() ? "Being read…" : "Needs attention in the Studio";
        ActionRow row = new ActionRow(Feather.FILE_TEXT, document.title(), state + " · " + where);
        row.getStyleClass().add("course-material-row");
        row.setAccessibleText("Read " + document.title() + ". " + state);
        row.setDisable(!document.isReady());
        row.setOnAction(() -> navigator.navigate(new Destination.Ask(document.courseId(), null,
                new Citation(1, null, document.id(), document.title(), 1, 1, ""))));
        return row;
    }

    // ---- side column ----------------------------------------------------------------------------

    private Node todayCard(Data loaded) {
        CourseSummary course = loaded.detail().summary();
        StudyNext next = loaded.next();
        Card card = new Card();
        card.setInteractive(false);
        card.getStyleClass().add("course-today");
        Label overline = new Label("TODAY");
        overline.getStyleClass().addAll("text-overline", "course-today-overline");
        card.getChildren().add(overline);

        if (course.itemCount() == 0) {
            card.getChildren().add(todayHeadline("No questions yet"));
            card.getChildren().add(todayDetail(course.createdHere()
                    ? "Approve questions in the Studio, and they're ready to study here."
                    : "This course has nothing to study yet. You can still ask about its material."));
        } else if (!course.enrolled()) {
            card.getChildren().add(todayHeadline("You're not studying this course"));
            card.getChildren().add(todayDetail(course.isArchived() ? "Restore the course to study it."
                    : "Once you study it, its reviews appear on Home and in Study next."));
        } else if (!next.caughtUp()) {
            boolean due = next.dueCount() > 0;
            Label numeral = new Label(Formats.number(due ? next.dueCount() : next.newCount()));
            numeral.getStyleClass().addAll("text-numeral", "course-today-numeral");
            Label unit = new Label(due ? (next.dueCount() == 1 ? "review due" : "reviews due")
                    : (next.newCount() == 1 ? "new question" : "new questions"));
            unit.getStyleClass().addAll("medium", "course-today-unit");
            HBox count = new HBox(10, numeral, unit);
            count.setAlignment(Pos.BASELINE_LEFT);
            String minutes = next.estimatedMinutes() <= 1 ? "about a minute"
                    : "about " + Formats.number(next.estimatedMinutes()) + " minutes";
            String extra = due && next.newCount() > 0
                    ? "And " + Formats.count(next.newCount(), "new question", "new questions") + ", " + minutes + "."
                    : capitalise(minutes) + ".";
            card.getChildren().addAll(count, todayDetail(extra));
        } else {
            card.getChildren().add(todayHeadline("All caught up"));
            card.getChildren().add(todayDetail(caughtUpText(course, next)));
        }

        Region rule = new Region();
        rule.getStyleClass().add("course-today-rule");
        ProgressRing ring = new ProgressRing(40, 4);
        ring.setProgress(course.progress());
        long studied = Math.round(course.progress() * course.itemCount());
        Label studiedText = new Label(course.itemCount() == 0 ? "Nothing studied yet"
                : Formats.number(studied) + " of " + Formats.count(course.itemCount(), "question", "questions") + " studied");
        studiedText.getStyleClass().add("course-today-studied");
        studiedText.setWrapText(true);
        HBox progress = new HBox(14, ring, studiedText);
        progress.setAlignment(Pos.CENTER_LEFT);
        Button more = new Button("See progress");
        more.getStyleClass().addAll("quiet", "small");
        more.setGraphic(Icons.of(Feather.ARROW_RIGHT, 14));
        more.setContentDisplay(ContentDisplay.RIGHT);
        more.setOnAction(event -> navigator.navigate(new Destination.Progress(course.id())));
        more.setVisible(course.itemCount() > 0);
        more.setManaged(course.itemCount() > 0);
        card.getChildren().addAll(rule, progress, more);
        card.setSpacing(10);
        return card;
    }

    private String caughtUpText(CourseSummary course, StudyNext next) {
        if (next.nextReviewAt() != null) {
            String when = Formats.inTime(next.nextReviewAt(), context.clock());
            return when.equals("tomorrow") ? "New reviews unlock tomorrow." : "Your next review is " + when + ".";
        }
        if (course.newItemCount() > 0) {
            return "New questions unlock tomorrow.";
        }
        return "Nothing is scheduled. Practise below whenever you like.";
    }

    private static Label todayHeadline(String text) {
        Label label = new Label(text);
        label.getStyleClass().addAll("serif-medium", "course-today-headline");
        label.setWrapText(true);
        return label;
    }

    private static Label todayDetail(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("course-today-detail");
        label.setWrapText(true);
        return label;
    }

    private Node practiseCard(CourseDetail detail, boolean hasFlashcards) {
        CourseSummary course = detail.summary();
        Card card = new Card();
        card.setInteractive(false);
        card.getStyleClass().add("course-practise");
        Label overline = new Label("PRACTISE");
        overline.getStyleClass().addAll("text-overline", "course-practise-overline");

        ActionRow flashcards = option(Feather.LAYERS, "Flashcards",
                hasFlashcards ? "Flip through the course's cards" : "No flashcards in this course");
        flashcards.setDisable(!hasFlashcards);
        flashcardsRow = flashcards;
        flashcards.setOnAction(() -> navigator.navigate(
                new Destination.Study(SessionRequest.flashcards(course.id(), null))));

        long weak = detail.concepts().stream().filter(ConceptMastery::isWeak).count();
        ActionRow weakConcepts = option(Feather.TARGET, "Weak concepts", weak == 0 ? "None so far. Keep studying."
                : Formats.count(weak, "concept needs", "concepts need") + " more practice");
        weakConcepts.setDisable(weak == 0);
        weakConcepts.setOnAction(() -> navigator.navigate(
                new Destination.Study(SessionRequest.weakConcepts(course.id()))));

        List<ModuleSummary> quizzable = detail.modules().stream().filter(m -> m.itemCount() > 0).toList();
        ActionRow quiz = option(Feather.CHECK_SQUARE, "Quiz a module", "Test yourself on one module");
        quiz.setDisable(quizzable.isEmpty());
        quiz.setOnAction(() -> {
            ContextMenu menu = new ContextMenu();
            menu.getStyleClass().add("course-menu");
            for (ModuleSummary module : quizzable) {
                MenuItem item = new MenuItem(module.title());
                item.setMnemonicParsing(false);
                item.setOnAction(e -> navigator.navigate(
                        new Destination.Study(SessionRequest.moduleQuiz(course.id(), module.id()))));
                menu.getItems().add(item);
            }
            menu.show(quiz, Side.BOTTOM, 12, 0);
        });
        VBox options = new VBox(2, flashcards, weakConcepts, quiz);
        card.getChildren().addAll(overline, options);
        card.setSpacing(10);
        return card;
    }

    private static ActionRow option(Ikon icon, String title, String caption) {
        ActionRow row = new ActionRow(icon, title, caption);
        row.getStyleClass().add("practise-option");
        return row;
    }

    private Node conceptsCard(CourseDetail detail) {
        CourseSummary course = detail.summary();
        Card card = new Card();
        card.setInteractive(false);
        card.getStyleClass().add("course-concepts");
        Label overline = new Label("CONCEPTS");
        overline.getStyleClass().addAll("text-overline", "course-concepts-overline");
        Label hint = new Label("Weakest first");
        hint.getStyleClass().addAll("text-caption", "course-concepts-hint");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(overline, spacer, hint);
        head.setAlignment(Pos.CENTER_LEFT);

        List<ConceptMastery> ordered = weakestFirst(detail.concepts());
        FlowPane chips = new FlowPane(8, 8);
        chips.getStyleClass().add("course-concept-chips");
        for (ConceptMastery concept : ordered.stream().limit(CONCEPT_LIMIT).toList()) {
            chips.getChildren().add(conceptChip(concept));
        }
        card.getChildren().addAll(head, chips);
        if (ordered.size() > CONCEPT_LIMIT) {
            Button all = new Button("All " + Formats.number(ordered.size()) + " concepts in Progress");
            all.getStyleClass().addAll("quiet", "small");
            all.setOnAction(event -> navigator.navigate(new Destination.Progress(course.id())));
            card.getChildren().add(all);
        }
        card.setSpacing(14);
        return card;
    }

    /** Concepts by mastery, weakest first; concepts not studied yet come last, in course order. */
    public static List<ConceptMastery> weakestFirst(List<ConceptMastery> concepts) {
        return concepts.stream()
                .sorted(Comparator.comparing((ConceptMastery c) -> c.mastery() == null ? 1 : 0)
                        .thenComparing(c -> c.mastery() == null ? 0 : c.mastery()))
                .toList();
    }

    static Chip conceptChip(ConceptMastery concept) {
        Tone tone = !concept.hasMastery() ? Tone.NEUTRAL
                : concept.isWeak() ? Tone.WARNING : concept.mastery() >= 0.85 ? Tone.SUCCESS : Tone.ACCENT;
        String text = concept.hasMastery() ? concept.name() + "  " + Formats.percent(concept.mastery()) : concept.name();
        Chip chip = new Chip(text, tone);
        chip.getStyleClass().add("course-concept-chip");
        if (!concept.hasMastery()) {
            chip.getStyleClass().add("unstudied");
        }
        Tooltip tip = new Tooltip(conceptTooltip(concept));
        tip.setShowDelay(javafx.util.Duration.millis(250));
        tip.setWrapText(true);
        tip.setMaxWidth(280);
        chip.setTooltip(tip);
        chip.setAccessibleText(conceptTooltip(concept));
        return chip;
    }

    /** "Paging: 52% mastered. Right in 6 of your last 10 answers. 8 questions." */
    public static String conceptTooltip(ConceptMastery concept) {
        StringBuilder text = new StringBuilder(concept.name()).append(": ");
        if (!concept.hasMastery()) {
            text.append("not studied yet.");
        } else {
            text.append(Formats.percent(concept.mastery())).append(" mastered.");
        }
        if (concept.accuracy() != null && concept.attemptCount() > 0) {
            int answers = Math.min(10, concept.attemptCount());
            long right = Math.round(concept.accuracy() * answers);
            text.append(answers == 1 ? (right == 1 ? " Your last answer was right." : " Your last answer wasn't right.")
                    : " Right in " + right + " of your last " + answers + " answers.");
        }
        text.append(' ').append(Formats.count(concept.itemCount(), "question", "questions")).append('.');
        return text.toString();
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
