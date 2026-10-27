package edu.learnsphere.app.ui.progress;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.PageView;
import edu.learnsphere.app.ui.StudioTab;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.components.Card;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.PageHeader;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.charts.CanvasChart;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.events.SettingChanged;
import edu.learnsphere.core.service.CourseProgress;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.util.Cancellable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Progress across every course being studied, or for one course picked in the header: the four numbers
 * worth knowing (streak or course progress, reviews, accuracy, time) with the last five weeks of study days;
 * mastery by concept, weakest first, with "Practise weak concepts"; the reviews coming up over the next 14
 * days; how much is remembered week by week; and, for one course, completion per module. On a course made in
 * this profile it also shows how each question is answered, by the creator and by their class.
 *
 * <p>Picking another course in the header switches the data in place (the destination is replaced, so the
 * breadcrumbs follow and Back still leaves the screen). The screen reloads when progress, courses, the
 * course's questions or the retention goal change.
 */
public final class ProgressPage extends PageView {

    static final String STYLESHEET = Objects.requireNonNull(ProgressPage.class.getResource("progress.css"),
            "progress.css").toExternalForm();

    private final CoursePicker picker = new CoursePicker();
    private Destination.Progress destination;
    private ProgressData data;
    private MasterySection mastery;
    private Cancellable pending;
    private boolean masteryExpanded;

    public ProgressPage(AppContext context, Navigator navigator, Destination.Progress destination) {
        super(context, navigator);
        this.destination = Objects.requireNonNull(destination, "destination");
        page().getStylesheets().add(STYLESHEET);
        page().getStyleClass().add("progress-page");
        picker.onPick(choice -> switchTo(choice.id()));
        showSkeleton();
    }

    @Override
    public void onShown() {
        reload(true);
        on(ProgressChanged.class, event -> reload(false));
        on(CourseChanged.class, this::courseChanged);
        on(ItemsChanged.class, event -> {
            if (data != null && data.creator() != null && event.courseId().equals(destination.courseId())) {
                reload(false);
            }
        });
        on(SettingChanged.class, event -> {
            if (SettingKeys.DESIRED_RETENTION.key().equals(event.key())) {
                reload(false);
            }
        });
    }

    /** What is on screen: the overview ({@code courseId} null) or one course. */
    Destination.Progress destination() {
        return destination;
    }

    /** The data on screen, or null while the first load runs. */
    ProgressData data() {
        return data;
    }

    /** True while data is being read. */
    boolean isLoading() {
        return pending != null;
    }

    CoursePicker picker() {
        return picker;
    }

    /** The mastery section on screen, or null when there is none. */
    MasterySection mastery() {
        return mastery;
    }

    /** Shows {@code courseId} (null for every course) in place, as if picked in the header. */
    void switchTo(String courseId) {
        if (Objects.equals(courseId, destination.courseId())) {
            return;
        }
        destination = new Destination.Progress(courseId);
        masteryExpanded = false;
        navigator.replace(destination, false);
        reload(true);
    }

    private void courseChanged(CourseChanged event) {
        if (event.type() == ChangeType.DELETED && Objects.equals(event.courseId(), destination.courseId())) {
            switchTo(null);
        } else {
            reload(false);
        }
    }

    // ---- loading ------------------------------------------------------------------------------

    private void reload(boolean animate) {
        if (pending != null) {
            pending.cancel();
        }
        Workspace ws = workspace();
        String courseId = destination.courseId();
        if (data != null && !Objects.equals(courseId, data.progress().courseId())) {
            // Another course is on its way: keep the header live and dim the rest until it arrives.
            dimContent();
        }
        pending = disposer.add(UiTasks.run(() -> ProgressData.load(ws, courseId), loaded -> {
            pending = null;
            if (Objects.equals(courseId, destination.courseId())) {
                render(loaded, animate);
            }
        }, message -> {
            pending = null;
            showError(message);
        }));
    }

    private void dimContent() {
        for (Node node : page().column().getChildren()) {
            if (!node.getStyleClass().contains("page-header") && !node.getStyleClass().contains("stale")) {
                node.getStyleClass().add("stale");
            }
        }
    }

    private void showSkeleton() {
        HBox cells = new HBox(24);
        for (int i = 0; i < 4; i++) {
            VBox cell = new VBox(12, Skeleton.line(72), Skeleton.block(96, 40), Skeleton.line(140));
            HBox.setHgrow(cell, Priority.ALWAYS);
            cell.setMaxWidth(Double.MAX_VALUE);
            cells.getChildren().add(cell);
        }
        Card ledger = skeletonCard(cells);
        VBox bars = new VBox(16, Skeleton.line(180), Skeleton.block(-1, 10), Skeleton.block(-1, 10),
                Skeleton.block(-1, 10), Skeleton.block(-1, 10));
        HBox trends = new HBox(24, skeletonCard(new VBox(16, Skeleton.line(120), Skeleton.block(-1, 180))),
                skeletonCard(new VBox(16, Skeleton.line(120), Skeleton.block(-1, 180))));
        trends.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));
        VBox title = new VBox(10, Skeleton.block(220, 40), Skeleton.line(300));
        show(title, ledger, skeletonCard(bars), trends);
    }

    private static Card skeletonCard(Node content) {
        Card card = new Card(content);
        card.setInteractive(false);
        card.getStyleClass().addAll("panel", "progress-skeleton");
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    @Override
    protected void showError(UserMessage message) {
        data = null;
        mastery = null;
        EmptyState error = new EmptyState(EmptyIllustration.Kind.SEARCH, "Your progress couldn't be shown", message.text());
        Runnable tryAgain = () -> {
            showSkeleton();
            reload(true);
        };
        Runnable showAll = () -> {
            showSkeleton();
            switchTo(null);
        };
        if (destination.courseId() == null) {
            error.primary("Try again", tryAgain).secondary("Go to Home", () -> navigator.navigate(Destination.HOME));
        } else if (isGone(message)) {
            // Trying again can't bring back a course that isn't there: lead to the courses that are.
            error.primary("Show all courses", showAll).secondary("Go to Home", () -> navigator.navigate(Destination.HOME));
        } else {
            error.primary("Try again", tryAgain).secondary("Show all courses", showAll);
        }
        show(error);
    }

    /** True when the message says the course on screen doesn't exist (any more). */
    private static boolean isGone(UserMessage message) {
        return message.equals(UserMessage.COURSE_NOT_FOUND) || message.equals(UserMessage.NOT_AVAILABLE);
    }

    // ---- rendering ----------------------------------------------------------------------------

    private void render(ProgressData loaded, boolean animate) {
        boolean sameView = data != null && Objects.equals(data.progress().courseId(), loaded.progress().courseId());
        if (!sameView) {
            masteryExpanded = false;
        } else if (mastery != null) {
            masteryExpanded = mastery.isExpanded();
        }
        data = loaded;
        mastery = null;
        CourseProgress p = loaded.progress();
        picker.show(loaded.studying(), loaded.isOverview() ? CoursePicker.Choice.ALL
                : loaded.course() != null ? CoursePicker.Choice.of(loaded.course())
                : new CoursePicker.Choice(p.courseId(), p.title(), p.coverHue() == null ? 0 : p.coverHue()));

        List<Node> sections = new ArrayList<>();
        sections.add(header(loaded));
        boolean primaryUsed = false;
        if (loaded.isOverview() && p.courses().isEmpty()) {
            sections.add(emptyCard(new EmptyState(EmptyIllustration.Kind.LIBRARY, "No courses to follow yet",
                    "Add a course from your Library and study it. Your streak, accuracy and the reviews coming up "
                            + "appear here after your first session.")
                    .primary("Open Library", () -> navigator.navigate(Destination.LIBRARY))));
            primaryUsed = true;
        } else if (creatorLeads(loaded)) {
            // A course made here and not studied: how its questions are answered is what there is to see.
            primaryUsed = false;
        } else if (!loaded.hasStudied()) {
            sections.add(emptyCard(notStudied(loaded)));
            primaryUsed = true;
            if (!loaded.isOverview() && !p.modules().isEmpty()) {
                sections.add(ModulesSection.of(p.modules()));
            }
        } else {
            sections.add(Ledger.of(loaded));
            mastery = new MasterySection(p.concepts(), loaded.conceptCourses(), this::practiseWeak, masteryExpanded);
            sections.add(mastery.node());
            primaryUsed = mastery.node().lookup(".practise-weak") != null;
            Card forecast = TrendSections.forecast(p.forecast());
            // The forecast starts today, so its first day is the learner's today.
            Card retention = TrendSections.retention(p.retention(), loaded.retentionGoal(),
                    p.forecast().isEmpty() ? null : p.forecast().getFirst().date());
            HBox trends = new HBox(24, forecast, retention);
            trends.getStyleClass().add("trend-row");
            for (Card card : List.of(forecast, retention)) {
                // Equal starting widths, so the two cards share the row evenly.
                card.setPrefWidth(400);
                card.setMinWidth(0);
                card.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(card, Priority.ALWAYS);
            }
            sections.add(trends);
            if (!loaded.isOverview() && !p.modules().isEmpty()) {
                sections.add(ModulesSection.of(p.modules()));
            }
        }
        if (loaded.creator() != null && (loaded.hasStudied() || !loaded.creator().items().isEmpty())) {
            // With no questions to study, the card above already says what to do.
            CreatorSection.Actions actions = creatorActions(loaded);
            sections.add(CreatorSection.of(loaded.creator(), actions, !primaryUsed));
            if (creatorLeads(loaded) && loaded.creator().hasAnswers()) {
                sections.add(CreatorSection.selfHint(actions));
            }
        }
        show(sections.toArray(Node[]::new));
        if (!animate) {
            for (Node node : page().column().lookupAll(".chart")) {
                if (node instanceof CanvasChart chart) {
                    chart.setAnimated(false);
                }
            }
        }
    }

    /** True for a course made here that the learner hasn't studied but that has questions to answer. */
    private static boolean creatorLeads(ProgressData loaded) {
        return loaded.creator() != null && !loaded.hasStudied() && !loaded.creator().items().isEmpty();
    }

    private PageHeader header(ProgressData loaded) {
        CourseProgress p = loaded.progress();
        PageHeader header;
        if (loaded.isOverview()) {
            int n = p.courses().size();
            header = new PageHeader("Progress");
            if (n > 0) {
                header.subtitle(n == 1 ? "In the one course you're studying." : "Across the " + n + " courses you're studying.");
            }
        } else {
            header = new PageHeader(p.title()).overline("Progress").subtitle(courseLine(loaded.course()));
            header.titleLabel().getStyleClass().add("progress-course-title");
        }
        // With nothing to pick from, the picker would only offer what is on screen.
        if (picker.getItems().size() > 1) {
            header.actions(picker);
        }
        return header;
    }

    /** "A course you made · 5 modules · 48 questions · 12 due now". */
    static String courseLine(CourseSummary course) {
        if (course == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (course.createdHere()) {
            parts.add("A course you made");
        }
        parts.add(Formats.count(course.moduleCount(), "module", "modules"));
        parts.add(Formats.count(course.itemCount(), "question", "questions"));
        parts.add(course.dueCount() == 0 ? "Nothing due now" : Formats.number(course.dueCount()) + " due now");
        String line = String.join(" · ", parts);
        return Character.toUpperCase(line.charAt(0)) + line.substring(1);
    }

    private EmptyState notStudied(ProgressData loaded) {
        if (loaded.isOverview()) {
            return new EmptyState(EmptyIllustration.Kind.PROGRESS, "Your progress appears after your first session",
                    "Study a few questions and you'll see your streak, accuracy, each concept's mastery and the "
                            + "reviews coming up.")
                    .primary("Start studying", () -> navigator.navigate(new Destination.Study(SessionRequest.studyNext())));
        }
        String courseId = loaded.progress().courseId();
        int[] counts = loaded.startedOfTotal();
        if (counts[1] == 0) {
            EmptyState none = new EmptyState(EmptyIllustration.Kind.QUESTIONS, "Nothing to study in this course yet",
                    loaded.creator() != null
                            ? "Approve questions in the review queue, then study them to see your progress here."
                            : "When the course has questions to study, your progress in it appears here.");
            if (loaded.creator() != null) {
                none.primary("Open the review queue",
                        () -> navigator.navigate(new Destination.Studio(courseId, StudioTab.REVIEW)));
            } else {
                none.primary("Open the course", () -> navigator.navigate(new Destination.Course(courseId)));
            }
            return none;
        }
        return new EmptyState(EmptyIllustration.Kind.PROGRESS, "You haven't studied this course yet",
                "Study a few of its " + Formats.count(counts[1], "question", "questions") + " and you'll see your "
                        + "accuracy, each concept's mastery and the reviews coming up.")
                .primary("Study this course",
                        () -> navigator.navigate(new Destination.Study(SessionRequest.studyNext(courseId))));
    }

    private static Card emptyCard(EmptyState empty) {
        Card card = new Card(empty);
        card.setInteractive(false);
        card.getStyleClass().addAll("panel", "progress-empty");
        return card;
    }

    private void practiseWeak() {
        navigator.navigate(new Destination.Study(SessionRequest.weakConcepts(destination.courseId())));
    }

    private CreatorSection.Actions creatorActions(ProgressData loaded) {
        String courseId = loaded.progress().courseId();
        return new CreatorSection.Actions() {
            @Override
            public void openQuestion(String itemId) {
                navigator.navigate(Destination.Studio.question(courseId, itemId));
            }

            @Override
            public void openStudio() {
                navigator.navigate(new Destination.Studio(courseId, StudioTab.DETAILS));
            }

            @Override
            public void openReviewQueue() {
                navigator.navigate(new Destination.Studio(courseId, StudioTab.REVIEW));
            }

            @Override
            public void publishAndShare() {
                navigator.navigate(new Destination.Studio(courseId, StudioTab.PUBLISH));
            }

            @Override
            public void studyCourse() {
                navigator.navigate(new Destination.Study(SessionRequest.studyNext(courseId)));
            }
        };
    }
}
