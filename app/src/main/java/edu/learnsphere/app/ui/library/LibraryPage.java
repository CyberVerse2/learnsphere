package edu.learnsphere.app.ui.library;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.components.CourseCard;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.ResponsiveGrid;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.app.ui.components.SegmentedControl;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.ToggleChip;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseScope;
import edu.learnsphere.core.service.CourseSummary;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * The library: every course in the profile as cards in a responsive grid, on two shelves. Studying holds
 * the courses being studied (and, below them, the ones the learner left); Creating holds the courses made
 * here, with archived ones on request. A search box filters by title, subtitle or author, and each shelf has
 * its own sort. Import course reads a shared course file; New course starts one for the Studio. Every card
 * has a menu with what can be done with that course.
 *
 * <p>Each view has one primary action: New course in the header while cards are shown, or the empty state's
 * own action while a shelf is empty. An empty library shows only the empty state's actions.
 *
 * <p>Cards are kept between renders while their course is unchanged, so searching, sorting and live reloads
 * keep the keyboard focus and replay no entrance motion.
 *
 * <p>Keyboard: Shortcut+F searches, Shortcut+N starts a new course; the arrow keys switch shelves.
 */
public final class LibraryPage extends PageView {

    /** The two halves of the library. */
    public enum Shelf {
        STUDYING("Studying"),
        CREATING("Creating");

        private final String label;

        Shelf(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** How a shelf is ordered. */
    public enum Sort {
        RECENT("Recently opened"),
        DUE("Most due"),
        CHANGED("Recently changed"),
        TITLE("Title");

        private final String label;

        Sort(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Shortcut+F: search. */
    public static final KeyCombination SEARCH_SHORTCUT = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);
    /** Shortcut+N: new course. */
    public static final KeyCombination NEW_COURSE_SHORTCUT =
            new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN);

    private final SegmentedControl<Shelf> shelf = new SegmentedControl<>(List.of(Shelf.values()), Shelf::label);
    private final Map<Shelf, Label> shelfCounts = new EnumMap<>(Shelf.class);
    private final TextField search = new TextField();
    private final ComboBox<Sort> sort = new ComboBox<>();
    private final Map<Shelf, Sort> sortByShelf = new EnumMap<>(Map.of(Shelf.STUDYING, Sort.RECENT, Shelf.CREATING, Sort.RECENT));
    private final ToggleChip showArchived = new ToggleChip("Show archived");
    private final VBox body = new VBox(32);
    private final CourseActions actions;
    private final PageHeader header;
    private final Button importCourse = new Button("Import course", Icons.of(Feather.DOWNLOAD));
    private final Button create = new Button("New course", Icons.of(Feather.PLUS));
    private final StackPane searchBox;
    private final List<LibraryCard> cards = new ArrayList<>();
    /** Cards by course id and shelf, reused while their course is unchanged. */
    private final Map<String, LibraryCard> cardCache = new HashMap<>();
    /** Courses whose card has been on screen, so a card enters with motion only once. */
    private final Set<String> seen = new HashSet<>();
    private boolean emptyStateLeads;
    private boolean headerActions = true;
    private final LiveLoad<List<CourseSummary>> load;
    private List<CourseSummary> courses;
    private final Runnable focusSearch = this::focusSearch;
    private final Runnable newCourse = this::openNewCourse;

    public LibraryPage(AppContext context, Navigator navigator) {
        super(context, navigator);
        page().getStylesheets().add(LibraryStyles.URL);
        page().getStyleClass().add("library-page");
        actions = new CourseActions(context, navigator, this::page, disposer);
        load = new LiveLoad<>(() -> workspace().courses().list(new CourseFilter(CourseScope.ALL, null, true)),
                list -> {
                    courses = list;
                    render();
                }, this::showError, disposer);

        importCourse.setOnAction(event -> startImport());
        create.getStyleClass().add("primary");
        create.setOnAction(event -> openNewCourse());
        header = new PageHeader("Library")
                .subtitle("The courses you study and the ones you're making, all on this computer.")
                .actions(importCourse, create);

        for (Shelf option : Shelf.values()) {
            Label count = new Label();
            count.getStyleClass().add("segment-count");
            shelfCounts.put(option, count);
            shelf.button(option).setGraphic(count);
            shelf.button(option).setContentDisplay(ContentDisplay.RIGHT);
            shelf.button(option).setGraphicTextGap(8);
        }
        shelf.setAccessibleText("Shelf");
        shelf.valueProperty().addListener((obs, old, value) -> {
            sort.getItems().setAll(sortsFor(value));
            sort.setValue(sortByShelf.get(value));
            render();
        });

        search.setPromptText("Search by title or author");
        search.setAccessibleText("Search courses");
        search.getStyleClass().add("library-search-field");
        search.textProperty().addListener((obs, old, value) -> render());
        var searchIcon = Icons.of(Feather.SEARCH, 16);
        searchIcon.getStyleClass().add("library-search-icon");
        searchIcon.setMouseTransparent(true);
        IconButton clear = new IconButton(Feather.X, "Clear search").small();
        clear.getStyleClass().add("library-search-clear");
        clear.setOnAction(event -> {
            search.clear();
            search.requestFocus();
        });
        clear.visibleProperty().bind(search.textProperty().isNotEmpty());
        searchBox = new StackPane(search, searchIcon, clear);
        searchBox.getStyleClass().add("library-search");
        StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
        StackPane.setAlignment(clear, Pos.CENTER_RIGHT);
        searchBox.setPrefWidth(280);
        searchBox.setMinWidth(180);
        search.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE && !search.getText().isEmpty()) {
                event.consume();
                search.clear();
            }
        });

        sort.getItems().setAll(sortsFor(Shelf.STUDYING));
        sort.setValue(Sort.RECENT);
        sort.setAccessibleText("Sort courses");
        sort.getStyleClass().add("library-sort");
        sort.setButtonCell(sortCell(true));
        sort.setCellFactory(list -> sortCell(false));
        sort.valueProperty().addListener((obs, old, value) -> {
            if (value != null) {
                sortByShelf.put(shelf.getValue(), value);
                render();
            }
        });

        showArchived.getStyleClass().add("library-archived-toggle");
        showArchived.selectedProperty().addListener((obs, old, value) -> render());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox controls = new HBox(12, shelf, spacer, showArchived, searchBox, sort);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getStyleClass().add("library-toolbar");

        ResponsiveGrid placeholder = ResponsiveGrid.forCards();
        for (int i = 0; i < 3; i++) {
            placeholder.getChildren().add(Skeleton.card());
        }
        body.getChildren().setAll(placeholder);
        body.getStyleClass().add("library-body");
        show(header, controls, body);
        updateArchivedToggle();
    }

    /** The segmented control (tests and the screenshot tour). */
    public SegmentedControl<Shelf> shelf() {
        return shelf;
    }

    public TextField searchField() {
        return search;
    }

    public ComboBox<Sort> sortBox() {
        return sort;
    }

    public ToggleChip archivedToggle() {
        return showArchived;
    }

    /** The header's New course button (tests). */
    public Button newCourseButton() {
        return create;
    }

    /** The header's Import course button (tests). */
    public Button importButton() {
        return importCourse;
    }

    /** The cards on screen, in order (tests). */
    public List<LibraryCard> cards() {
        return List.copyOf(cards);
    }

    /** Finished loads (tests wait for a reload). */
    public int loads() {
        return load.completed();
    }

    /** The actions behind the card menus (tests replace the file dialogs here). */
    public CourseActions actions() {
        return actions;
    }

    @Override
    public void onShown() {
        refresh();
        on(CourseChanged.class, event -> refresh());
        on(ProgressChanged.class, event -> refresh());
        on(ItemsChanged.class, event -> refresh());
        Scene scene = page().getScene();
        if (scene != null) {
            scene.getAccelerators().put(SEARCH_SHORTCUT, focusSearch);
            scene.getAccelerators().put(NEW_COURSE_SHORTCUT, newCourse);
            disposer.onDispose(() -> {
                scene.getAccelerators().remove(SEARCH_SHORTCUT, focusSearch);
                scene.getAccelerators().remove(NEW_COURSE_SHORTCUT, newCourse);
            });
        }
    }

    /** Asks for a course file and imports it. */
    public void startImport() {
        Path file = actions.filePicker().chooseCourseFile(page());
        if (file != null) {
            importFrom(file);
        }
    }

    /** Shows the import sheet for {@code file}: its preview, then the import. */
    public ImportCourseDialog importFrom(Path file) {
        return new ImportCourseDialog(workspace(), disposer, file,
                courseId -> navigator.navigate(new Destination.Course(courseId)), this::startImport).show(page());
    }

    /** Opens the New course sheet; the new course opens in the Studio. */
    public NewCourseDialog openNewCourse() {
        List<Integer> hues = courses == null ? List.of() : courses.stream().map(CourseSummary::coverHue).toList();
        return new NewCourseDialog(workspace(), workspace().profile().displayName(), hues, disposer,
                (Course course) -> navigator.navigate(new Destination.Studio(course.id(), StudioTab.DETAILS)))
                .show(page());
    }

    private void focusSearch() {
        search.requestFocus();
        search.selectAll();
    }

    /** Loads every course (archived included) once; shelves, search and sort work on that list. */
    private void refresh() {
        load.request();
    }

    private void render() {
        updateArchivedToggle();
        if (courses == null) {
            return;
        }
        Node focused = page().getScene() == null ? null : page().getScene().getFocusOwner();
        emptyStateLeads = false;
        fill();
        // An empty library has nothing to search or sort, and its empty state carries the actions.
        boolean empty = courses.isEmpty();
        if (empty && !search.getText().isEmpty()) {
            search.clear();
        }
        setShown(searchBox, !empty);
        setShown(sort, !empty);
        if (headerActions == empty) {
            headerActions = !empty;
            if (empty) {
                header.actions();
            } else {
                header.actions(importCourse, create);
            }
        }
        // One primary per view: an empty state with its own primary action takes it over from the header.
        create.getStyleClass().remove("primary");
        if (!emptyStateLeads) {
            create.getStyleClass().add("primary");
        }
        pruneCards();
        if (focused != null) {
            restoreFocus(focused);
        }
    }

    /** Puts the active shelf into the body. */
    private void fill() {
        cards.clear();
        boolean anyCourse = !courses.isEmpty();
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        Predicate<CourseSummary> matches = course -> query.isEmpty() || contains(course.title(), query)
                || contains(course.subtitle(), query) || contains(course.authorName(), query);
        Comparator<CourseSummary> order = comparator(sort.getValue() == null ? Sort.RECENT : sort.getValue());

        List<CourseSummary> studying = courses.stream().filter(c -> c.enrolled() && !c.isArchived()).toList();
        List<CourseSummary> left = courses.stream().filter(c -> !c.enrolled() && !c.createdHere() && !c.isArchived()).toList();
        List<CourseSummary> creating = courses.stream().filter(CourseSummary::createdHere).toList();
        long archived = creating.stream().filter(CourseSummary::isArchived).count();
        shelfCounts.get(Shelf.STUDYING).setText(Integer.toString(studying.size()));
        shelfCounts.get(Shelf.CREATING).setText(Long.toString(creating.size() - archived));

        if (shelf.getValue() == Shelf.STUDYING) {
            List<CourseSummary> shown = studying.stream().filter(matches).sorted(order).toList();
            List<CourseSummary> leftShown = left.stream().filter(matches).sorted(order).toList();
            if (studying.isEmpty() && left.isEmpty()) {
                EmptyState empty = new EmptyState(EmptyIllustration.Kind.LIBRARY, "No courses to study yet",
                        "Import a course someone shared with you, or make your own from your PDFs.")
                        .primary("Import a course", this::startImport);
                if (!anyCourse) {
                    empty.secondary("New course", this::openNewCourse);
                }
                lead(empty);
                return;
            }
            if (shown.isEmpty() && leftShown.isEmpty()) {
                body.getChildren().setAll(noMatches(query));
                return;
            }
            List<Node> sections = new ArrayList<>();
            if (!shown.isEmpty()) {
                sections.add(grid(shown, CourseCard.Mode.STUDYING));
            } else if (query.isEmpty()) {
                Label none = new Label("You aren't studying any course right now. Pick one up again below, "
                        + "or import a new one.");
                none.getStyleClass().addAll("text-muted", "library-note");
                none.setWrapText(true);
                sections.add(none);
            }
            if (!leftShown.isEmpty()) {
                sections.add(new VBox(16, new SectionHeader("Not studying")
                        .subtitle("Courses you left. Your progress is kept if you pick one up again."),
                        grid(leftShown, CourseCard.Mode.STUDYING)));
            }
            body.getChildren().setAll(sections);
            return;
        }
        if (creating.isEmpty()) {
            lead(new EmptyState(EmptyIllustration.Kind.DOCUMENTS, "Turn your PDFs into a course",
                    "Add lecture notes or a chapter of a textbook, and LearnSphere drafts questions for you to check.")
                    .primary("New course", this::openNewCourse));
            return;
        }
        List<CourseSummary> visible = creating.stream()
                .filter(c -> showArchived.isSelected() || !c.isArchived()).toList();
        if (visible.isEmpty()) {
            lead(new EmptyState(EmptyIllustration.Kind.DOCUMENTS, "Every course you made is archived",
                    "Archived courses stay on this computer with their questions and PDFs.")
                    .primary("Show archived", () -> showArchived.setSelected(true)));
            return;
        }
        List<CourseSummary> shown = visible.stream().filter(matches).sorted(order).toList();
        if (shown.isEmpty()) {
            body.getChildren().setAll(noMatches(query));
            return;
        }
        ResponsiveGrid grid = grid(shown, CourseCard.Mode.CREATING);
        if (query.isEmpty()) {
            grid.getChildren().add(newCourseTile());
        }
        body.getChildren().setAll(grid);
    }

    /** Shows an empty state whose primary action is the view's one primary. */
    private void lead(EmptyState empty) {
        emptyStateLeads = true;
        body.getChildren().setAll(empty);
    }

    /** Forgets cards of courses that are gone. */
    private void pruneCards() {
        Set<String> ids = new HashSet<>();
        courses.forEach(c -> ids.add(c.id()));
        cardCache.values().removeIf(card -> !ids.contains(card.course().id()));
    }

    /** Keeps the focus on a card's control that a render moved into a new grid. */
    private void restoreFocus(Node focused) {
        for (LibraryCard card : cards) {
            for (Node n = focused; n != null; n = n.getParent()) {
                if (n == card) {
                    focused.requestFocus();
                    return;
                }
            }
        }
    }

    private static void setShown(Node node, boolean value) {
        node.setVisible(value);
        node.setManaged(value);
    }

    private Node noMatches(String query) {
        return new EmptyState(EmptyIllustration.Kind.SEARCH, "No courses match “" + search.getText().strip() + "”",
                "Check the spelling, or search for the author's name.")
                .secondary("Clear search", () -> {
                    search.clear();
                    search.requestFocus();
                });
    }

    private ResponsiveGrid grid(List<CourseSummary> list, CourseCard.Mode mode) {
        ResponsiveGrid grid = ResponsiveGrid.forCards();
        grid.getStyleClass().add("library-grid");
        for (CourseSummary course : list) {
            LibraryCard card = card(course, mode);
            grid.getChildren().add(card);
            cards.add(card);
        }
        return grid;
    }

    /** The card of {@code course} on a shelf: the one already made while the course is unchanged. */
    private LibraryCard card(CourseSummary course, CourseCard.Mode mode) {
        String key = course.id() + '/' + mode;
        LibraryCard kept = cardCache.get(key);
        if (kept != null && kept.course().equals(course)) {
            return kept;
        }
        LibraryCard card = new LibraryCard(course, mode, () -> actions.menu(course, CourseActions.Place.LIBRARY));
        card.card().setOnOpen(() -> {
            if (mode == CourseCard.Mode.CREATING) {
                navigator.navigate(new Destination.Studio(course.id(), StudioTab.DETAILS));
            } else {
                navigator.navigate(new Destination.Course(course.id()));
            }
        });
        cardCache.put(key, card);
        if (seen.add(key)) {
            card.card().animateIn();
        }
        return card;
    }

    /** The last tile of the Creating shelf: starts a new course. */
    private Node newCourseTile() {
        var plus = Icons.of(Feather.PLUS, 22);
        plus.getStyleClass().add("new-course-tile-icon");
        StackPane badge = new StackPane(plus);
        badge.getStyleClass().add("new-course-tile-badge");
        badge.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        Label title = new Label("New course");
        title.getStyleClass().addAll("serif-medium", "new-course-tile-title");
        Label text = new Label("Turn lecture notes or a textbook chapter into questions to study.");
        text.getStyleClass().add("new-course-tile-text");
        text.setWrapText(true);
        text.setMaxWidth(220);
        text.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        VBox tile = new VBox(10, badge, title, text);
        tile.setAlignment(Pos.CENTER);
        tile.getStyleClass().add("new-course-tile");
        tile.setFocusTraversable(true);
        tile.setAccessibleRole(javafx.scene.AccessibleRole.BUTTON);
        tile.setAccessibleText("New course");
        tile.setOnMouseClicked(event -> openNewCourse());
        tile.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER || event.getCode() == KeyCode.SPACE) {
                event.consume();
                openNewCourse();
            }
        });
        return tile;
    }

    private void updateArchivedToggle() {
        boolean creating = shelf.getValue() == Shelf.CREATING;
        boolean any = courses != null && courses.stream().anyMatch(c -> c.createdHere() && c.isArchived());
        showArchived.setVisible(creating && any);
        showArchived.setManaged(creating && any);
    }

    private static List<Sort> sortsFor(Shelf shelf) {
        return shelf == Shelf.STUDYING ? List.of(Sort.RECENT, Sort.DUE, Sort.TITLE)
                : List.of(Sort.RECENT, Sort.CHANGED, Sort.TITLE);
    }

    /** The order of a shelf; ties keep the most recently opened first. */
    static Comparator<CourseSummary> comparator(Sort sort) {
        Comparator<CourseSummary> recent = Comparator.comparing(
                (CourseSummary c) -> c.lastOpenedAt() == null ? Long.MIN_VALUE : c.lastOpenedAt()).reversed();
        Comparator<CourseSummary> title = Comparator.comparing(c -> c.title().toLowerCase(Locale.ROOT));
        return switch (sort) {
            case RECENT -> recent.thenComparing(title);
            case DUE -> Comparator.comparingInt(CourseSummary::dueCount).reversed()
                    .thenComparing(Comparator.comparingInt(CourseSummary::newItemCount).reversed())
                    .thenComparing(recent);
            case CHANGED -> Comparator.comparingLong(CourseSummary::updatedAt).reversed().thenComparing(title);
            case TITLE -> title.thenComparing(recent);
        };
    }

    private static boolean contains(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query);
    }

    private static ListCell<Sort> sortCell(boolean button) {
        return new ListCell<>() {
            @Override
            protected void updateItem(Sort item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : button ? "Sort: " + item.label() : item.label());
            }
        };
    }

    /** True when {@code first} is listed before {@code second} under {@code sort} (tests). */
    static boolean before(Sort sort, CourseSummary first, CourseSummary second) {
        return comparator(Objects.requireNonNull(sort)).compare(first, second) < 0;
    }
}
