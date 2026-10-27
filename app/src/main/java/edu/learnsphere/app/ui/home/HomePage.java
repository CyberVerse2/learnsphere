package edu.learnsphere.app.ui.home;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.CourseCard;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.ResponsiveGrid;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.library.FilePicker;
import edu.learnsphere.app.ui.library.ImportCourseDialog;
import edu.learnsphere.app.ui.library.LiveLoad;
import edu.learnsphere.app.ui.library.NewCourseDialog;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.events.SettingChanged;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DashboardData;
import edu.learnsphere.core.service.SessionRequest;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * Home: a greeting with the date, the "Study next" hero across every course being studied, the last seven
 * days with the streak, courses to continue, and the questions asked most recently with their sources. A
 * profile without courses is invited to import or create one. Everything reloads as progress, courses,
 * questions or study settings change.
 */
public final class HomePage extends PageView {

    /** Courses shown under Continue. */
    static final int CONTINUE_LIMIT = 3;
    /** The keys of Go › Start studying, shown beside the hero's button. */
    static final KeyCombination START_SHORTCUT =
            new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);

    private record Data(DashboardData dashboard, int courseCount) {
    }

    private final PageHeader header;
    private final StudyNextCard hero;
    private final WeekCard week = new WeekCard();
    private final RecentQuestions questions;
    private final LiveLoad<Data> load;
    private FilePicker files = FilePicker.system();

    public HomePage(AppContext context, Navigator navigator) {
        super(context, navigator);
        page().getStylesheets().add(Objects.requireNonNull(HomePage.class.getResource("home.css"), "home.css")
                .toExternalForm());
        page().getStyleClass().add("home-page");
        header = new PageHeader(greeting(workspace().profile().displayName(), LocalTime.now(context.clock())))
                .overline(Formats.longDate(LocalDate.now(context.clock())));
        hero = new StudyNextCard(this::startStudying, () -> navigator.navigate(Destination.LIBRARY), START_SHORTCUT);
        HBox.setHgrow(hero, Priority.ALWAYS);
        questions = new RecentQuestions(
                question -> navigator.navigate(new Destination.Ask(question.courseId(), question.threadId(), null)),
                (question, citation) -> navigator.navigate(
                        new Destination.Ask(question.courseId(), question.threadId(), citation)));
        load = new LiveLoad<>(() -> new Data(workspace().progress().dashboard(),
                workspace().courses().list(CourseFilter.all()).size()), this::render, this::showError, disposer);

        HBox top = new HBox(24, Skeleton.block(-1, 248), Skeleton.block(340, 248));
        HBox.setHgrow(top.getChildren().getFirst(), Priority.ALWAYS);
        ResponsiveGrid cards = ResponsiveGrid.forCards();
        for (int i = 0; i < CONTINUE_LIMIT; i++) {
            cards.getChildren().add(Skeleton.card());
        }
        show(header, top, new VBox(16, Skeleton.line(140), cards));
    }

    @Override
    public void onShown() {
        load.request();
        on(ProgressChanged.class, event -> load.request());
        on(CourseChanged.class, event -> load.request());
        on(ItemsChanged.class, event -> load.request());
        on(SettingChanged.class, event -> load.request());
    }

    // ---- what tests and the screenshot tour reach --------------------------------------------

    public StudyNextCard hero() {
        return hero;
    }

    public WeekCard week() {
        return week;
    }

    public RecentQuestions recentQuestions() {
        return questions;
    }

    /** The greeting at the top. */
    public Label greetingLabel() {
        return header.titleLabel();
    }

    /** Finished loads (tests wait for a reload). */
    public int loads() {
        return load.completed();
    }

    /** Replaces the file dialog of Import a course (tests). */
    public void setFilePicker(FilePicker picker) {
        files = Objects.requireNonNull(picker, "picker");
    }

    /** Asks for a course file and imports it. */
    public void importCourse() {
        Path file = files.chooseCourseFile(page());
        if (file != null) {
            new ImportCourseDialog(workspace(), disposer, file,
                    courseId -> navigator.navigate(new Destination.Course(courseId)), this::importCourse).show(page());
        }
    }

    /** Opens the New course sheet; the course opens in the Studio. */
    public NewCourseDialog newCourse() {
        return new NewCourseDialog(workspace(), workspace().profile().displayName(), List.of(), disposer,
                (Course course) -> navigator.navigate(new Destination.Studio(course.id(), StudioTab.DETAILS)))
                .show(page());
    }

    // ---- rendering ---------------------------------------------------------------------------

    private void render(Data loaded) {
        DashboardData d = loaded.dashboard();
        header.setTitle(greeting(d.profileName(), LocalTime.now(context.clock())));
        if (loaded.courseCount() == 0 && d.continueCourses().isEmpty()) {
            show(header, welcome());
            return;
        }
        hero.show(d, context.clock());
        week.show(d);
        week.setMinWidth(300);
        week.setPrefWidth(340);
        week.setMaxWidth(340);
        HBox top = new HBox(24, hero, week);
        top.getStyleClass().add("home-top");

        List<Node> sections = new ArrayList<>(List.of(header, top));
        if (!d.continueCourses().isEmpty()) {
            sections.add(continueSection(d.continueCourses()));
        }
        Node recent = recentSection(d);
        if (recent != null) {
            sections.add(recent);
        }
        show(sections.toArray(Node[]::new));
    }

    private Node welcome() {
        EmptyState empty = new EmptyState(EmptyIllustration.Kind.LIBRARY, "Start with a course",
                "Import a course someone shared with you, or turn your own PDFs into a course to study.")
                .primary("Import a course", this::importCourse)
                .secondary("Create a course", this::newCourse);
        empty.getStyleClass().add("home-welcome");
        return empty;
    }

    private Node continueSection(List<CourseSummary> courses) {
        SectionHeader title = new SectionHeader("Continue")
                .action("Library", () -> navigator.navigate(Destination.LIBRARY));
        ResponsiveGrid grid = ResponsiveGrid.forCards();
        grid.getStyleClass().add("home-continue");
        for (CourseSummary course : courses.stream().limit(CONTINUE_LIMIT).toList()) {
            CourseCard card = new CourseCard(course, CourseCard.Mode.STUDYING);
            card.setOnOpen(() -> navigator.navigate(new Destination.Course(course.id())));
            grid.getChildren().add(card);
            card.animateIn();
        }
        return new VBox(16, title, grid);
    }

    /** Recent questions, or an invitation to ask one when a course is being studied; null otherwise. */
    private Node recentSection(DashboardData d) {
        if (!d.recentQuestions().isEmpty()) {
            questions.show(d.recentQuestions(), context.clock());
            return new VBox(16, new SectionHeader("Recent questions"), questions);
        }
        if (d.continueCourses().isEmpty()) {
            return null;
        }
        CourseSummary course = d.continueCourses().getFirst();
        var icon = Icons.of(Feather.MESSAGE_CIRCLE, 20);
        icon.getStyleClass().add("home-ask-icon");
        Label title = new Label("Ask about your courses");
        title.getStyleClass().addAll("semibold", "home-ask-title");
        Label text = new Label("Answers come from your course's PDFs and show the pages they rely on.");
        text.getStyleClass().add("home-ask-text");
        text.setWrapText(true);
        VBox words = new VBox(2, title, text);
        HBox.setHgrow(words, Priority.ALWAYS);
        Button ask = new Button("Ask a question");
        ask.setOnAction(event -> navigator.navigate(Destination.Ask.newThread(course.id())));
        HBox row = new HBox(16, icon, words, ask);
        row.setAlignment(Pos.CENTER_LEFT);
        Card card = new Card(row);
        card.setInteractive(false);
        card.getStyleClass().addAll("well", "home-ask");
        return new VBox(16, new SectionHeader("Recent questions"), card);
    }

    private void startStudying() {
        navigator.navigate(new Destination.Study(SessionRequest.studyNext()));
    }

    /** "Good morning, Amara": the part of the day and the learner's first name. */
    static String greeting(String name, LocalTime time) {
        String first = name == null || name.isBlank() ? "" : name.strip().split("\\s+")[0];
        int hour = time.getHour();
        String part = hour < 5 ? "Good evening" : hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
        return first.isEmpty() ? part : part + ", " + first;
    }
}
