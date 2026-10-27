package edu.learnsphere.app.ui.progress;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.ProgressRing;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.StatTile;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.app.ui.components.charts.BarChart;
import edu.learnsphere.app.ui.components.charts.ColumnChart;
import edu.learnsphere.app.ui.components.charts.LineChart;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.CourseProgress;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.ForecastDay;
import edu.learnsphere.core.service.ModuleProgress;
import edu.learnsphere.core.service.RetentionPoint;
import edu.learnsphere.core.service.SettingKeys;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Progress across every course being studied, or for one course: streak, reviews, accuracy and study
 * time; mastery by concept; the reviews coming up over the next 14 days; how well reviews are remembered
 * week by week; and, for one course, completion per module. Charts carry direct labels in the theme
 * colours.
 */
public final class ProgressPage extends PageView {

    private static final double STAT_WIDTH = 200;

    /** Concepts shown in the mastery chart before "and N more". */
    static final int CONCEPT_LIMIT = 10;
    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private record Data(CourseProgress progress, double retentionGoal) {
    }

    private final Destination.Progress destination;

    public ProgressPage(AppContext context, Navigator navigator, Destination.Progress destination) {
        super(context, navigator);
        this.destination = destination;
        HBox tiles = new HBox(16);
        for (int i = 0; i < 4; i++) {
            Node tile = Skeleton.block(-1, 112);
            HBox.setHgrow(tile, Priority.ALWAYS);
            tiles.getChildren().add(tile);
        }
        show(Skeleton.block(240, 40), tiles, Skeleton.block(-1, 320));
    }

    @Override
    public void onShown() {
        refresh();
        on(ProgressChanged.class, event -> refresh());
    }

    private void refresh() {
        String courseId = destination.courseId();
        load(() -> new Data(courseId == null ? workspace().progress().overview() : workspace().progress().course(courseId),
                workspace().settings().get(SettingKeys.DESIRED_RETENTION)), this::render);
    }

    private void render(Data data) {
        CourseProgress p = data.progress();
        PageHeader header = new PageHeader(p.isOverview() ? "Progress" : p.title())
                .subtitle(p.isOverview() ? "How your studying is going across every course you're taking."
                        : "How your studying of this course is going.")
                .actions(coursePicker(p));
        if (!p.isOverview()) {
            header.overline("Progress");
        }
        if (p.totalReviews() == 0 && p.concepts().stream().allMatch(c -> c.mastery() == null)) {
            show(header, new EmptyState(EmptyIllustration.Kind.PROGRESS, "Nothing to measure yet",
                    "Study a few questions and your progress, strongest topics and upcoming reviews appear here.")
                    .primary("Go to Library", () -> navigator.navigate(Destination.LIBRARY)));
            return;
        }
        List<Node> sections = new ArrayList<>();
        sections.add(header);
        sections.add(stats(p));
        sections.add(mastery(p.concepts()));
        HBox charts = new HBox(24, forecast(p.forecast()), retention(p.retention(), data.retentionGoal()));
        charts.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        sections.add(charts);
        if (!p.isOverview() && !p.modules().isEmpty()) {
            sections.add(modules(p.modules()));
        }
        show(sections.toArray(Node[]::new));
    }

    private Node coursePicker(CourseProgress p) {
        ComboBox<CourseSummary> picker = new ComboBox<>();
        CourseSummary all = null;
        picker.getItems().add(all);
        picker.getItems().addAll(p.courses());
        picker.setCellFactory(list -> new CourseCell());
        picker.setButtonCell(new CourseCell());
        picker.setPrefWidth(280);
        picker.getSelectionModel().select(p.courses().stream().filter(c -> c.id().equals(p.courseId())).findFirst()
                .orElse(null));
        picker.setOnAction(event -> {
            CourseSummary chosen = picker.getValue();
            navigator.navigate(chosen == null ? Destination.Progress.overview() : new Destination.Progress(chosen.id()));
        });
        picker.setAccessibleText("Course");
        return picker;
    }

    /** "All courses" or the course's title. */
    private static final class CourseCell extends ListCell<CourseSummary> {
        @Override
        protected void updateItem(CourseSummary item, boolean empty) {
            super.updateItem(item, empty);
            setText(empty ? null : item == null ? "All courses" : item.title());
        }
    }

    private Node stats(CourseProgress p) {
        StatTile streak = new StatTile("Streak", p.streakDays()).unit(p.streakDays() == 1 ? "day" : "days")
                .detail(p.streakDays() > 0 ? "Keep it going today" : "Study today to start one",
                        p.streakDays() > 0 ? Tone.ACCENT : Tone.NEUTRAL);
        StatTile reviews = new StatTile("Reviews", p.totalReviews()).detail("Answers you've given", Tone.NEUTRAL);
        StatTile accuracy = new StatTile("Accuracy", p.accuracy() == null ? "–" : Formats.percent(p.accuracy()))
                .detail("Answered correctly", Tone.NEUTRAL);
        if (p.accuracy() != null) {
            accuracy.format(v -> Math.round(v) + "%");
            accuracy.countTo(Math.round(p.accuracy() * 100));
        }
        long minutes = Math.round(p.studyTimeMs() / 60_000.0);
        StatTile time = minutes < 60
                ? new StatTile("Time studied", minutes).unit("min")
                : new StatTile("Time studied", Formats.hours(p.studyTimeMs())).unit("h");
        time.detail("In study sessions", Tone.NEUTRAL);
        ProgressRing ring = new ProgressRing(56, 6);
        ring.setProgress(p.progress());
        StatTile progress = new StatTile("Course progress", Formats.percent(p.progress())).graphic(ring)
                .detail("Questions you've started", Tone.NEUTRAL);
        List<StatTile> tiles = p.isOverview() ? List.of(streak, reviews, accuracy, time)
                : List.of(progress, reviews, accuracy, time);
        streak.countTo(p.streakDays());
        reviews.countTo(p.totalReviews());
        HBox row = new HBox(16);
        row.getStyleClass().add("stat-row");
        for (StatTile tile : tiles) {
            // Equal starting widths, so the tiles share the row evenly.
            tile.setPrefWidth(STAT_WIDTH);
            tile.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(tile, Priority.ALWAYS);
            row.getChildren().add(tile);
        }
        return row;
    }

    private Node mastery(List<ConceptMastery> concepts) {
        List<ConceptMastery> measured = concepts.stream().filter(c -> c.mastery() != null)
                .sorted(Comparator.comparingDouble(ConceptMastery::mastery)).toList();
        long unmeasured = concepts.size() - measured.size();
        SectionHeader header = new SectionHeader("Mastery by concept")
                .subtitle("Weakest first. Concepts under 60% come back more often in Study next.");
        Card card = panel(header);
        if (measured.isEmpty()) {
            Label none = new Label("Answer a few questions and each concept's mastery appears here.");
            none.getStyleClass().add("text-muted");
            card.getChildren().add(none);
            return card;
        }
        BarChart chart = new BarChart();
        chart.setBars(measured.stream().limit(CONCEPT_LIMIT)
                .map(c -> new BarChart.Bar(c.name(), c.mastery(), Formats.percent(c.mastery()), c.mastery() < 0.6))
                .toList());
        card.getChildren().add(chart);
        long hidden = measured.size() - Math.min(CONCEPT_LIMIT, measured.size());
        if (hidden > 0 || unmeasured > 0) {
            List<String> notes = new ArrayList<>();
            if (hidden > 0) {
                notes.add(Formats.count(hidden, "stronger concept", "stronger concepts") + " not shown");
            }
            if (unmeasured > 0) {
                notes.add(Formats.count(unmeasured, "concept", "concepts") + " not studied yet");
            }
            Label note = new Label(String.join(" · ", notes));
            note.getStyleClass().add("text-caption");
            card.getChildren().add(note);
        }
        return card;
    }

    private Node forecast(List<ForecastDay> days) {
        int total = days.stream().mapToInt(ForecastDay::dueCount).sum();
        SectionHeader header = new SectionHeader("Coming up").subtitle(total == 0 ? "No reviews in the next two weeks."
                : Formats.count(total, "review", "reviews") + " in the next 14 days");
        ColumnChart chart = new ColumnChart();
        List<ColumnChart.Column> columns = new ArrayList<>();
        for (int i = 0; i < days.size(); i++) {
            ForecastDay day = days.get(i);
            String label = i == 0 ? "Today" : i % 2 == 0 ? Formats.weekdayShort(day.date()) : "";
            columns.add(new ColumnChart.Column(label, day.dueCount(),
                    day.dueCount() == 0 ? "" : Integer.toString(day.dueCount()), i == 0));
        }
        chart.setColumns(columns);
        Card card = panel(header);
        card.getChildren().add(chart);
        return card;
    }

    private Node retention(List<RetentionPoint> points, double goal) {
        SectionHeader header = new SectionHeader("Remembered").subtitle("Share of reviews answered correctly, week by week");
        Card card = panel(header);
        if (points.size() < 2) {
            Label none = new Label("After two weeks of reviews, your retention trend appears here.");
            none.getStyleClass().add("text-muted");
            none.setWrapText(true);
            card.getChildren().add(none);
            return card;
        }
        LineChart chart = new LineChart();
        double low = Math.min(goal, points.stream().mapToDouble(RetentionPoint::retention).min().orElse(goal));
        chart.setRange(Math.max(0, Math.floor((low - 0.15) * 10) / 10), 1);
        chart.setPoints(points.stream().map(point -> new LineChart.Point(point.weekStart().format(DAY_MONTH),
                point.retention())).toList());
        chart.setReference(goal, "Goal " + Formats.percent(goal));
        card.getChildren().add(chart);
        return card;
    }

    private Node modules(List<ModuleProgress> modules) {
        SectionHeader header = new SectionHeader("Modules");
        Card card = panel(header);
        GridPane grid = new GridPane();
        grid.getStyleClass().add("module-progress-grid");
        grid.setHgap(24);
        grid.setVgap(14);
        int row = 0;
        for (ModuleProgress module : modules) {
            Label title = new Label(module.title());
            title.getStyleClass().add("medium");
            title.setMinWidth(180);
            ProgressBarSlim bar = new ProgressBarSlim();
            bar.setProgress(module.completion());
            bar.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(bar, Priority.ALWAYS);
            Label value = new Label(Formats.percent(module.completion()));
            value.getStyleClass().add("text-caption");
            value.setMinWidth(40);
            value.setAlignment(Pos.CENTER_RIGHT);
            Label due = new Label(module.dueCount() == 0 ? "Nothing due" : module.dueCount() + " due");
            due.getStyleClass().addAll("text-caption", module.dueCount() == 0 ? "text-faint" : "text-accent");
            due.setMinWidth(90);
            grid.addRow(row++, title, bar, value, due);
        }
        card.getChildren().add(grid);
        return card;
    }

    private static Card panel(Node header) {
        Card card = new Card(header);
        card.setInteractive(false);
        card.getStyleClass().add("panel");
        card.setSpacing(20);
        VBox.setVgrow(card, Priority.NEVER);
        return card;
    }
}
