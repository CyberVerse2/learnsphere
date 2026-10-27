package edu.learnsphere.app.dev;

import atlantafx.base.controls.ToggleSwitch;
import edu.learnsphere.app.theme.Theme;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Avatar;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.CitationChip;
import edu.learnsphere.app.ui.components.ConfidenceChips;
import edu.learnsphere.app.ui.components.ConfirmDialog;
import edu.learnsphere.app.ui.components.CourseCard;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.IconButton;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.ProgressRing;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.app.ui.components.SegmentedControl;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.StatTile;
import edu.learnsphere.app.ui.components.StatusPill;
import edu.learnsphere.app.ui.components.ToggleChip;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.app.ui.components.TopBar;
import edu.learnsphere.app.ui.components.charts.BarChart;
import edu.learnsphere.app.ui.components.charts.ColumnChart;
import edu.learnsphere.app.ui.components.charts.LineChart;
import edu.learnsphere.app.ui.components.charts.WeekStrip;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.CourseSummary;
import java.util.List;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * Every component of the design system, light on the left and dark on the right, built from the same code
 * so the two appearances can be compared side by side. Demo builds only (the "Components" destination and
 * the screenshot tour's {@code gallery} shots).
 */
public final class ComponentGallery implements View {

    /** Width of one appearance's column. */
    public static final double COLUMN_WIDTH = 640;

    private final ScrollPane root = new ScrollPane();
    private final HBox columns;

    public ComponentGallery() {
        columns = new HBox(column(false), column(true));
        columns.getStyleClass().add("gallery");
        root.setContent(columns);
        root.setFitToWidth(true);
        root.getStyleClass().addAll("page", "gallery-scroller");
        root.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
    }

    @Override
    public Region root() {
        return root;
    }

    /** Both columns, for rendering the whole gallery to one image. */
    public Region content() {
        return columns;
    }

    private VBox column(boolean dark) {
        VBox column = new VBox(40);
        column.getStyleClass().addAll("gallery-column", dark ? Theme.SCOPE_DARK : Theme.SCOPE_LIGHT);
        column.setMinWidth(COLUMN_WIDTH);
        column.setPrefWidth(COLUMN_WIDTH);
        HBox.setHgrow(column, Priority.ALWAYS);
        Label title = new Label(dark ? "Dark" : "Light");
        title.getStyleClass().add("text-display");
        Label subtitle = new Label("Every LearnSphere component, drawn from the theme's colour and type tokens.");
        subtitle.getStyleClass().add("text-muted");
        column.getChildren().addAll(new VBox(6, title, subtitle),
                section("Type", type()),
                section("Colour", colours()),
                section("Buttons", buttons()),
                section("Inputs", inputs()),
                section("Choices", choices()),
                section("Chips and status", chips()),
                section("Progress", progress()),
                section("Stats", stats()),
                section("Course covers", covers()),
                section("Course card", courseCard()),
                section("Charts", charts()),
                section("Headers and navigation", headers()),
                section("Lists and tables", lists()),
                section("Notifications and dialogs", feedback()),
                section("Empty and loading", emptyAndLoading()));
        return column;
    }

    private static Node section(String name, Node content) {
        Label label = new Label(name.toUpperCase(java.util.Locale.ROOT));
        label.getStyleClass().addAll("text-overline", "gallery-section-title");
        return new VBox(14, label, content);
    }

    private static Node type() {
        Label display = new Label("Process scheduling");
        display.getStyleClass().add("text-display");
        Label title = new Label("Operating Systems Principles");
        title.getStyleClass().add("text-title");
        Label heading = new Label("Continue where you left off");
        heading.getStyleClass().add("text-heading");
        Label body = new Label("Interface text is set in Inter at 14 px with comfortable line spacing.");
        body.getStyleClass().add("text-body");
        body.setWrapText(true);
        Label reading = new Label("A context switch saves the state of the running process into its control block "
                + "and loads the saved state of the next one.");
        reading.getStyleClass().add("text-reading");
        reading.setWrapText(true);
        Label caption = new Label("Lecture 1 · page 11 · 2 min ago");
        caption.getStyleClass().add("text-caption");
        Label numeral = new Label("128");
        numeral.getStyleClass().add("text-numeral");
        return new VBox(10, display, title, heading, body, reading, caption, numeral);
    }

    private static Node colours() {
        String[][] tokens = {
                {"-ls-bg", "Paper"}, {"-ls-surface", "Surface"}, {"-ls-surface-sunken", "Sunken"},
                {"-ls-border", "Border"}, {"-ls-text", "Text"}, {"-ls-text-muted", "Muted"},
                {"-ls-text-faint", "Faint"}, {"-ls-accent", "Accent"}, {"-ls-accent-soft", "Accent soft"},
                {"-ls-success", "Success"}, {"-ls-warning", "Warning"}, {"-ls-danger", "Danger"},
                {"-ls-highlight", "Highlight"}};
        FlowPane flow = new FlowPane(12, 12);
        for (String[] token : tokens) {
            Region swatch = new Region();
            swatch.getStyleClass().add("gallery-swatch");
            swatch.setStyle("-fx-background-color: -ls-border-strong, " + token[0] + ";");
            Label name = new Label(token[1]);
            name.getStyleClass().add("text-caption");
            VBox box = new VBox(6, swatch, name);
            box.setAlignment(Pos.TOP_CENTER);
            box.setPrefWidth(72);
            flow.getChildren().add(box);
        }
        return flow;
    }

    private static Node buttons() {
        Button primary = new Button("Start studying");
        primary.getStyleClass().add("primary");
        Button secondary = new Button("Import course", Icons.of(Feather.DOWNLOAD));
        Button quiet = new Button("See all");
        quiet.getStyleClass().add("quiet");
        Button danger = new Button("Delete course");
        danger.getStyleClass().add("danger");
        Button disabled = new Button("Publish");
        disabled.getStyleClass().add("primary");
        disabled.setDisable(true);
        Button small = new Button("Approve", Icons.of(Feather.CHECK, 14));
        small.getStyleClass().addAll("primary", "small");
        Button smallSecondary = new Button("Edit");
        smallSecondary.getStyleClass().add("small");
        Button large = new Button("Check answer");
        large.getStyleClass().addAll("primary", "large");
        Button quietNeutral = new Button("End session", Icons.of(Feather.X, 14));
        quietNeutral.getStyleClass().addAll("quiet", "neutral", "small");
        IconButton icon = new IconButton(Feather.MORE_HORIZONTAL, "More");
        IconButton iconSmall = new IconButton(Feather.X, "Close").small();
        FlowPane flow = new FlowPane(12, 12, primary, secondary, quiet, danger, disabled);
        FlowPane flow2 = new FlowPane(12, 12, large, small, smallSecondary, quietNeutral, icon, iconSmall);
        flow.setAlignment(Pos.CENTER_LEFT);
        flow2.setAlignment(Pos.CENTER_LEFT);
        return new VBox(14, flow, flow2);
    }

    private static Node inputs() {
        TextField text = new TextField();
        text.setPromptText("Course title");
        TextField filled = new TextField("Operating Systems Principles");
        PasswordField password = new PasswordField();
        password.setPromptText("Password (optional)");
        TextArea area = new TextArea("A first course on what an operating system does: running processes, deciding "
                + "who gets the CPU, managing memory and keeping files safe.");
        area.setWrapText(true);
        area.setPrefRowCount(3);
        ComboBox<String> combo = new ComboBox<>(FXCollections.observableArrayList("All courses",
                "Operating Systems Principles", "Research Methods in Computing"));
        combo.getSelectionModel().selectFirst();
        combo.setPrefWidth(260);
        Spinner<Integer> spinner = new Spinner<>(5, 100, 20, 5);
        spinner.setPrefWidth(110);
        Slider slider = new Slider(0, 359, 214);
        slider.setPrefWidth(260);
        HBox row = new HBox(12, combo, spinner);
        row.setAlignment(Pos.CENTER_LEFT);
        return new VBox(12, text, filled, password, area, row, slider);
    }

    private static Node choices() {
        CheckBox on = new CheckBox("Describe figures and diagrams");
        on.setSelected(true);
        CheckBox off = new CheckBox("Show hints");
        CheckBox mixed = new CheckBox("Select all");
        mixed.setAllowIndeterminate(true);
        mixed.setIndeterminate(true);
        ToggleGroup group = new ToggleGroup();
        RadioButton a = new RadioButton("Whole course");
        a.setToggleGroup(group);
        a.setSelected(true);
        RadioButton b = new RadioButton("One module");
        b.setToggleGroup(group);
        ToggleSwitch on2 = new ToggleSwitch("Reduce motion");
        on2.setSelected(true);
        ToggleSwitch off2 = new ToggleSwitch("Play sounds");
        SegmentedControl<String> segmented = new SegmentedControl<>(List.of("Studying", "Creating"), s -> s);
        ToggleButton toggle = new ToggleButton("Only due");
        toggle.setSelected(true);
        ToggleButton toggleOff = new ToggleButton("Show drafts");
        Hyperlink link = new Hyperlink("Open the PDF");
        HBox checks = new HBox(20, on, off, mixed);
        HBox radios = new HBox(20, a, b);
        HBox switches = new HBox(28, on2, off2);
        HBox toggles = new HBox(12, segmented, toggle, toggleOff, link);
        toggles.setAlignment(Pos.CENTER_LEFT);
        return new VBox(16, checks, radios, switches, toggles);
    }

    private static Node chips() {
        FlowPane tones = new FlowPane(8, 8, new Chip("Scheduling"), new Chip("New", Tone.ACCENT),
                new Chip("Mastered", Tone.SUCCESS, Feather.CHECK), new Chip("3 drafts to review", Tone.WARNING),
                new Chip("Couldn't read", Tone.DANGER), new Chip("AI draft", Tone.ACCENT, Feather.FEATHER));
        ToggleChip filter = new ToggleChip("Multiple choice");
        filter.setSelected(true);
        FlowPane toggles = new FlowPane(8, 8, filter, new ToggleChip("Flashcards"), new ToggleChip("Short answer"));
        ConfidenceChips confidence = new ConfidenceChips();
        confidence.setConfidence(Confidence.FAIRLY_SURE);
        Citation citation = new Citation(1, "c1", "d1", "Lecture 2 – CPU Scheduling", 12, 12,
                "Round robin gives each ready process a fixed time quantum in turn.");
        Citation range = new Citation(2, "c2", "d1", "Lecture 2 – CPU Scheduling", 14, 15, "");
        HBox citations = new HBox(6, new CitationChip(citation), new CitationChip(range));
        StatusPill ready = new StatusPill(StatusPill.Status.READY);
        StatusPill working = new StatusPill(StatusPill.Status.WORKING, "Reading your PDF… page 14 of 120");
        StatusPill review = new StatusPill(StatusPill.Status.NEEDS_REVIEW, "3 pages to check");
        StatusPill failed = new StatusPill(StatusPill.Status.FAILED, "This PDF is password protected");
        FlowPane pills = new FlowPane(10, 10, ready, working, review, failed);
        return new VBox(14, tones, toggles, confidence, citations, pills);
    }

    private static Node progress() {
        ProgressRing ring = new ProgressRing(96, 8);
        ring.setProgressNow(0.62);
        ProgressRing done = new ProgressRing(64, 6);
        done.setProgressNow(1);
        ProgressRing small = ProgressRing.small();
        small.setProgressNow(0.35);
        HBox rings = new HBox(28, ring, done, small, new ActivityIndicator(22));
        rings.setAlignment(Pos.CENTER_LEFT);
        ProgressBarSlim accent = new ProgressBarSlim();
        accent.setProgressNow(0.45);
        ProgressBarSlim success = new ProgressBarSlim().tone(Tone.SUCCESS);
        success.setProgressNow(0.8);
        ProgressBarSlim warning = new ProgressBarSlim().tone(Tone.WARNING);
        warning.setProgressNow(0.25);
        ProgressBar stock = new ProgressBar(0.6);
        stock.setMaxWidth(Double.MAX_VALUE);
        return new VBox(16, rings, accent, success, warning, stock);
    }

    private static Node stats() {
        StatTile due = new StatTile("Due today", 24).detail("About 12 minutes", Tone.NEUTRAL);
        StatTile streak = new StatTile("Streak", 9).unit("days").detail("Your longest yet", Tone.SUCCESS);
        ProgressRing ring = new ProgressRing(52, 5);
        ring.setProgressNow(0.72);
        StatTile mastery = new StatTile("Mastery", "72%").graphic(ring);
        HBox row = new HBox(12, due, streak, mastery);
        row.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        return row;
    }

    private static Node covers() {
        HBox sizes = new HBox(16);
        sizes.setAlignment(Pos.BOTTOM_LEFT);
        int[] hues = {214, 32, 152, 280, 350};
        for (int i = 0; i < hues.length; i++) {
            sizes.getChildren().add(new CourseCover(hues[i], "gallery-" + i, CourseCover.Size.MEDIUM));
        }
        HBox small = new HBox(10, new CourseCover(214, "a", CourseCover.Size.SMALL),
                new CourseCover(32, "b", CourseCover.Size.SMALL), new CourseCover(152, "c", CourseCover.Size.TINY),
                new CourseCover(280, "d", CourseCover.Size.TINY), new Avatar("AO", 18, 34), new Avatar("JK", 190, 34));
        small.setAlignment(Pos.CENTER_LEFT);
        CourseCover banner = new CourseCover(214, "banner", CourseCover.Size.BANNER);
        return new VBox(14, banner, sizes, small);
    }

    private static Node courseCard() {
        CourseSummary studying = new CourseSummary("gallery-os", "Operating Systems Principles",
                "How a computer shares itself between programs", "Dr. Daniel Mensah", 214, CourseOrigin.IMPORTED,
                CourseStatus.PUBLISHED, Visibility.SHAREABLE, 3, false, true, 4, 4, 0, 0, 64, 0, 18, 12, 0, 0.56,
                null, 0);
        CourseSummary creating = new CourseSummary("gallery-chem", "Organic Chemistry Basics",
                "Structure, naming and reactions of carbon compounds", "Amara Okafor", 152, CourseOrigin.LOCAL,
                CourseStatus.DRAFT, Visibility.PRIVATE, 0, true, false, 3, 6, 1, 1, 30, 14, 0, 0, 0, 0, null, 0);
        CourseCard a = new CourseCard(studying);
        CourseCard b = new CourseCard(creating, CourseCard.Mode.CREATING);
        a.setPrefWidth(300);
        b.setPrefWidth(300);
        HBox row = new HBox(16, a, b);
        return row;
    }

    private static Node charts() {
        BarChart bars = new BarChart();
        bars.setBars(List.of(new BarChart.Bar("Page replacement", 0.38, "38%", true),
                new BarChart.Bar("Deadlock", 0.54, "54%", true),
                new BarChart.Bar("Round robin", 0.71, "71%", false),
                new BarChart.Bar("Process states", 0.92, "92%", false)));
        ColumnChart columns = new ColumnChart();
        String[] days = {"Today", "", "Wed", "", "Fri", "", "Sun", "", "Tue", "", "Thu", "", "Sat", ""};
        int[] counts = {24, 9, 14, 3, 0, 11, 6, 18, 4, 2, 9, 0, 5, 7};
        List<ColumnChart.Column> list = new java.util.ArrayList<>();
        for (int i = 0; i < days.length; i++) {
            list.add(new ColumnChart.Column(days[i], counts[i], counts[i] == 0 ? "" : Integer.toString(counts[i]), i == 0));
        }
        columns.setColumns(list);
        LineChart line = new LineChart();
        line.setRange(0.5, 1);
        line.setPoints(List.of(new LineChart.Point("29 Sep", 0.71), new LineChart.Point("6 Oct", 0.78),
                new LineChart.Point("13 Oct", 0.83), new LineChart.Point("20 Oct", 0.81),
                new LineChart.Point("27 Oct", 0.88)));
        line.setReference(0.9, "Goal 90%");
        LineChart spark = LineChart.sparkline();
        spark.setPoints(List.of(new LineChart.Point("a", 0.4), new LineChart.Point("b", 0.55),
                new LineChart.Point("c", 0.5), new LineChart.Point("d", 0.7), new LineChart.Point("e", 0.82)));
        spark.setPrefWidth(160);
        WeekStrip week = new WeekStrip();
        String[] initials = {"T", "W", "T", "F", "S", "S", "M"};
        int[] reviews = {18, 25, 0, 31, 12, 0, 22};
        List<WeekStrip.Day> weekDays = new java.util.ArrayList<>();
        for (int i = 0; i < initials.length; i++) {
            weekDays.add(new WeekStrip.Day(initials[i], reviews[i], i == initials.length - 1));
        }
        week.setDays(weekDays);
        HBox small = new HBox(32, week, spark);
        small.setAlignment(Pos.CENTER_LEFT);
        return new VBox(24, panel(bars), panel(columns), panel(line), small);
    }

    private static Node headers() {
        PageHeader page = new PageHeader("Library").subtitle("Courses you study and courses you're writing.")
                .actions(primary("New course"));
        SectionHeader section = new SectionHeader("Continue").action("Library", () -> { });
        SectionHeader large = SectionHeader.large("Modules").subtitle("Work through them in order.");
        TopBar bar = new TopBar();
        bar.setCrumbs(List.of(new TopBar.Crumb("Library", () -> { }),
                new TopBar.Crumb("Operating Systems Principles", () -> { }), TopBar.Crumb.current("Ask")));
        bar.canGoBackProperty().set(true);
        TabPane tabs = new TabPane(new Tab("Details"), new Tab("Modules"), new Tab("Review queue"));
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(48);
        tabs.setMaxHeight(48);
        return new VBox(18, bar, page, section, large, tabs);
    }

    /** A row of the table sample. */
    public record Row(String concept, String mastery, String reviews) {
        public String getConcept() {
            return concept;
        }

        public String getMastery() {
            return mastery;
        }

        public String getReviews() {
            return reviews;
        }
    }

    private static Node lists() {
        ListView<String> list = new ListView<>(FXCollections.observableArrayList("Lecture 1 – Processes and Threads",
                "Lecture 2 – CPU Scheduling", "Lecture 3 – Memory Management", "Lecture 4 – File Systems"));
        list.getSelectionModel().select(1);
        list.setPrefHeight(4 * 40 + 4);
        TableView<Row> table = new TableView<>(FXCollections.observableArrayList(
                new Row("Process states", "92%", "14"), new Row("Round robin", "71%", "9"),
                new Row("Page replacement", "38%", "6")));
        TableColumn<Row, String> concept = new TableColumn<>("Concept");
        concept.setCellValueFactory(new PropertyValueFactory<>("concept"));
        concept.setPrefWidth(300);
        TableColumn<Row, String> mastery = new TableColumn<>("Mastery");
        mastery.setCellValueFactory(new PropertyValueFactory<>("mastery"));
        TableColumn<Row, String> reviews = new TableColumn<>("Reviews");
        reviews.setCellValueFactory(new PropertyValueFactory<>("reviews"));
        table.getColumns().setAll(List.of(concept, mastery, reviews));
        table.getSelectionModel().select(1);
        table.setPrefHeight(4 * 40 + 6);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        return new VBox(14, list, table);
    }

    private static Node feedback() {
        VBox toasts = new VBox(10, Toasts.sample(Toasts.Kind.SUCCESS, "Course published", null),
                Toasts.sample(Toasts.Kind.INFO, "Question removed from the queue", "Undo"),
                Toasts.sample(Toasts.Kind.ERROR, "You're offline. Try again when you're connected.", null));
        toasts.setAlignment(Pos.TOP_LEFT);
        toasts.getChildren().forEach(node -> ((Region) node).setMaxWidth(Region.USE_PREF_SIZE));
        Region dialog = ConfirmDialog.of("Delete this course?",
                "Its PDFs, questions and progress are removed from this computer.", "Delete course").danger().preview();
        Label tooltip = new Label("Lecture 2 – CPU Scheduling, p. 12");
        tooltip.getStyleClass().addAll("tooltip", "gallery-tooltip");
        StackPane dialogWell = new StackPane(dialog);
        dialogWell.getStyleClass().add("gallery-well");
        dialogWell.setAlignment(Pos.CENTER_LEFT);
        return new VBox(16, toasts, tooltip, dialogWell);
    }

    private static Node emptyAndLoading() {
        EmptyState empty = new EmptyState(EmptyIllustration.Kind.LIBRARY, "Your library is empty",
                "Import a course someone shared with you, or turn your own PDFs into a course.")
                .primary("Import a course", () -> { }).secondary("Create a course", () -> { });
        FlowPane illustrations = new FlowPane(12, 12);
        for (EmptyIllustration.Kind kind : EmptyIllustration.Kind.values()) {
            EmptyIllustration art = new EmptyIllustration(kind);
            art.setScaleX(0.6);
            art.setScaleY(0.6);
            StackPane holder = new StackPane(new javafx.scene.Group(art));
            illustrations.getChildren().add(holder);
        }
        HBox skeletons = new HBox(16, Skeleton.card(), new VBox(10, Skeleton.line(220), Skeleton.text(3)));
        skeletons.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        return new VBox(20, panel(empty), illustrations, skeletons);
    }

    private static Card panel(Node content) {
        Card card = new Card(content);
        card.setInteractive(false);
        card.getStyleClass().add("panel");
        return card;
    }

    private static Button primary(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("primary");
        return button;
    }
}
