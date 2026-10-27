package edu.learnsphere.app.ui.home;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.CitationChip;
import edu.learnsphere.app.ui.components.CourseCard;
import edu.learnsphere.app.ui.components.CourseCover;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.ProgressRing;
import edu.learnsphere.app.ui.components.ResponsiveGrid;
import edu.learnsphere.app.ui.components.SectionHeader;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.charts.WeekStrip;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DashboardData;
import edu.learnsphere.core.service.DayActivity;
import edu.learnsphere.core.service.RecentQuestion;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.StudyNext;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Home: a greeting with the date, the "Study next" hero, this week's reviews and streak, courses to
 * continue, and recent questions with their citations. A profile with no courses sees the welcome instead.
 */
public final class HomePage extends PageView {

    private record Data(DashboardData dashboard, boolean anyCourses) {
    }

    public HomePage(AppContext context, Navigator navigator) {
        super(context, navigator);
        show(Skeleton.block(-1, 34), Skeleton.block(-1, 220), Skeleton.text(3));
    }

    @Override
    public void onShown() {
        refresh();
        on(ProgressChanged.class, event -> refresh());
    }

    private void refresh() {
        load(() -> new Data(workspace().progress().dashboard(),
                !workspace().courses().list(CourseFilter.all()).isEmpty()), this::render);
    }

    private void render(Data data) {
        DashboardData d = data.dashboard();
        PageHeader header = new PageHeader(greeting(d.profileName()))
                .overline(Formats.longDate(d.today()));
        if (!data.anyCourses()) {
            show(header, new EmptyState(EmptyIllustration.Kind.LIBRARY, "Your library is empty",
                    "Import a course someone shared with you, or turn your own PDFs into a course to study.")
                    .primary("Import a course", () -> navigator.navigate(Destination.LIBRARY))
                    .secondary("Create a course", () -> navigator.navigate(Destination.Studio.courses())));
            return;
        }
        HBox top = new HBox(24, hero(d), week(d));
        top.getStyleClass().add("home-top");
        show(header, top, continueSection(d.continueCourses()), recentSection(d.recentQuestions()));
    }

    private String greeting(String name) {
        int hour = LocalTime.now(context.clock()).getHour();
        String first = name.strip().split("\\s+")[0];
        String part = hour < 12 ? "Good morning" : hour < 18 ? "Good afternoon" : "Good evening";
        return part + ", " + first;
    }

    private Node hero(DashboardData d) {
        StudyNext next = d.studyNext();
        Card card = new Card();
        card.setInteractive(false);
        card.getStyleClass().add("study-next");
        HBox.setHgrow(card, Priority.ALWAYS);

        Label overline = new Label("STUDY NEXT");
        overline.getStyleClass().addAll("text-overline", "study-next-overline");
        VBox text = new VBox(6);
        text.getChildren().add(overline);
        int total = next.dueCount() + next.newCount();
        Button start = new Button("Start studying");
        start.getStyleClass().addAll("primary", "large");
        start.setDefaultButton(false);
        start.setOnAction(event -> navigator.navigate(new Destination.Study(SessionRequest.studyNext())));
        if (total == 0) {
            Label title = new Label("You're all caught up");
            title.getStyleClass().add("text-title");
            Label detail = new Label(next.nextReviewAt() == null ? "Add a course to keep learning."
                    : "Your next review is " + Formats.inTime(next.nextReviewAt(), context.clock()) + ".");
            detail.getStyleClass().add("study-next-detail");
            text.getChildren().addAll(title, detail);
            start.setText("Practise anyway");
            start.getStyleClass().remove("primary");
        } else {
            Label numeral = new Label(Integer.toString(next.dueCount()));
            numeral.getStyleClass().addAll("text-numeral", "study-next-numeral");
            Label unit = new Label(next.dueCount() == 1 ? "review due" : "reviews due");
            unit.getStyleClass().add("study-next-unit");
            HBox count = new HBox(10, numeral, unit);
            count.setAlignment(Pos.BASELINE_LEFT);
            String extra = (next.newCount() > 0 ? "and " + Formats.count(next.newCount(), "new question", "new questions")
                    + " · " : "") + "about " + Formats.count(Math.max(1, next.estimatedMinutes()), "minute", "minutes");
            Label detail = new Label(extra);
            detail.getStyleClass().add("study-next-detail");
            text.getChildren().addAll(count, detail);
        }
        Region gap = new Region();
        gap.setMinHeight(14);
        text.getChildren().addAll(gap, start);
        HBox.setHgrow(text, Priority.ALWAYS);

        int done = d.reviewsToday();
        ProgressRing ring = new ProgressRing(112, 9);
        ring.setShowPercent(false);
        Label doneLabel = new Label(Integer.toString(done));
        doneLabel.getStyleClass().addAll("text-numeral", "ring-numeral");
        Label doneCaption = new Label("done today");
        doneCaption.getStyleClass().add("text-caption");
        VBox center = new VBox(-4, doneLabel, doneCaption);
        center.setAlignment(Pos.CENTER);
        ring.setCenter(center);
        ring.setProgress(done + next.dueCount() == 0 ? 1 : done / (double) (done + next.dueCount()));

        HBox row = new HBox(24, text, ring);
        row.setAlignment(Pos.CENTER_LEFT);
        card.getChildren().add(row);
        return card;
    }

    private Node week(DashboardData d) {
        Card card = new Card();
        card.setInteractive(false);
        card.getStyleClass().add("week-card");
        card.setMinWidth(300);
        card.setPrefWidth(330);
        Label title = new Label("This week");
        title.getStyleClass().add("text-heading");
        Label streak = new Label(d.streakDays() == 0 ? "Start a streak today"
                : Formats.count(d.streakDays(), "day", "days") + " in a row");
        streak.getStyleClass().add("week-streak");
        HBox head = new HBox(title, spacer(), streak);
        head.setAlignment(Pos.CENTER_LEFT);
        WeekStrip strip = new WeekStrip();
        strip.setDays(d.week().stream().map(day -> new WeekStrip.Day(Formats.weekdayInitial(day.date()),
                day.reviews(), day.today())).toList());
        int total = d.week().stream().mapToInt(DayActivity::reviews).sum();
        Label caption = new Label(Formats.count(total, "review", "reviews") + " this week");
        caption.getStyleClass().add("text-caption");
        card.getChildren().addAll(head, strip, caption);
        card.setSpacing(14);
        return card;
    }

    private Node continueSection(List<CourseSummary> courses) {
        SectionHeader header = new SectionHeader("Continue").action("Library", () -> navigator.navigate(Destination.LIBRARY));
        ResponsiveGrid grid = ResponsiveGrid.forCards();
        for (CourseSummary course : courses.stream().limit(3).toList()) {
            CourseCard card = new CourseCard(course);
            card.setOnOpen(() -> navigator.navigate(new Destination.Course(course.id())));
            grid.getChildren().add(card);
            card.animateIn();
        }
        VBox section = new VBox(16, header, grid);
        return section;
    }

    private Node recentSection(List<RecentQuestion> questions) {
        SectionHeader header = new SectionHeader("Recent questions");
        VBox list = new VBox(12);
        if (questions.isEmpty()) {
            Label hint = new Label("Questions you ask about your courses appear here, with the pages that answer them.");
            hint.getStyleClass().add("text-muted");
            list.getChildren().add(hint);
        }
        for (RecentQuestion q : questions) {
            list.getChildren().add(recentQuestion(q));
        }
        return new VBox(16, header, list);
    }

    private Node recentQuestion(RecentQuestion q) {
        Card card = new Card();
        card.getStyleClass().add("recent-question");
        card.setFocusTraversable(true);
        CourseCover cover = new CourseCover(q.coverHue(), q.courseId(), CourseCover.Size.TINY);
        Label course = new Label(q.courseTitle() + " · " + Formats.ago(q.askedAt(), context.clock()));
        course.getStyleClass().add("text-caption");
        HBox meta = new HBox(8, cover, course);
        meta.setAlignment(Pos.CENTER_LEFT);
        Label question = new Label(q.question());
        question.getStyleClass().add("recent-question-text");
        question.setWrapText(true);
        Label answer = new Label(Formats.shorten(q.answerPreview().replaceAll("\\s*\\[\\d+]", ""), 190));
        answer.getStyleClass().add("recent-question-answer");
        answer.setWrapText(true);
        HBox chips = new HBox(6);
        for (var citation : q.citations()) {
            CitationChip chip = new CitationChip(citation);
            chip.setOnOpen(c -> navigator.navigate(new Destination.Ask(q.courseId(), q.threadId(), c)));
            chips.getChildren().add(chip);
        }
        card.getChildren().addAll(meta, question, answer);
        if (!chips.getChildren().isEmpty()) {
            card.getChildren().add(chips);
        }
        card.setSpacing(8);
        Runnable open = () -> navigator.navigate(new Destination.Ask(q.courseId(), q.threadId(), null));
        card.setOnMouseClicked(event -> open.run());
        card.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER) {
                open.run();
            }
        });
        return card;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    static String lower(String text) {
        return text.toLowerCase(Locale.ROOT);
    }
}
