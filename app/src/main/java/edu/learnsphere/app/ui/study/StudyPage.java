package edu.learnsphere.app.ui.study;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.ConfidenceChips;
import edu.learnsphere.app.ui.components.ConfirmDialog;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.learning.StudyMessages;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.ForecastDay;
import edu.learnsphere.core.service.Hint;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionRequest;
import edu.learnsphere.core.service.SessionSummary;
import edu.learnsphere.core.service.SourceRef;
import edu.learnsphere.core.service.StudyNext;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.Workspace;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.geometry.Bounds;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * A study session in focus mode. A slim progress bar and "3 of 17" sit at the top; the question is set large
 * in the reading face with its answer area below: big option rows (keys 1 to 4), true/false tiles (T and F),
 * a blank to fill in the sentence, a short written answer the study assistant checks, or a flashcard that
 * turns over (Space) and is rated Again, Hard, Good or Easy (keys 1 to 4). Objective questions ask how sure
 * the learner is first; a hint can be shown (H) and counts against the rating.
 *
 * <p>After an answer the page shows the verdict, the right answer, the explanation, a "From your material"
 * link that opens the source page beside the session, and when the question comes back. Answers rated Again
 * come back later in the same session. Enter checks and continues; Escape leaves (answers are already saved).
 * The session ends on a summary, and a session that has nothing in it, or that ended while the app was away,
 * says so and offers the way on.
 *
 * <p>Started from {@link Destination.Study}; "Study more" and "Start a new session" restart within the page,
 * so leaving still returns to where the session was opened from.
 */
public final class StudyPage implements View {

    /** What the page is showing. */
    public enum State {
        /** Starting the session (or finishing it). */
        LOADING,
        /** A question waiting for an answer, or a flashcard waiting to be turned or rated. */
        QUESTION,
        /** An answer being checked. */
        CHECKING,
        /** The result of an answer. */
        FEEDBACK,
        /** The end-of-session summary. */
        SUMMARY,
        /** Nothing to study right now. */
        EMPTY,
        /** The session ended while the app was away; a new one can start. */
        ENDED,
        /** The session could not start. */
        ERROR
    }

    private static final String STYLESHEET = Objects.requireNonNull(
            StudyPage.class.getResource("study.css"), "study.css").toExternalForm();

    private final AppContext context;
    private final Navigator navigator;
    private final Destination.Study destination;
    private final Destination cameFrom;
    private final Disposer disposer = new Disposer();
    private final StackPane root = new StackPane();
    private final ProgressBarSlim bar = new ProgressBarSlim(4);
    private final Label where = new Label();
    private final Label counter = new Label();
    private final VBox stage = new VBox();
    private final StackPane centring = new StackPane(stage);
    private final ScrollPane scroller = new ScrollPane(centring);
    private final StackPane footer = new StackPane();
    private final Button leaveButton = new Button("Leave session", Icons.of(Feather.X, 16));

    private Disposer tasks = new Disposer();
    private SessionRequest request;
    private State state = State.LOADING;
    private StudySession session;
    private StudyQueue queue;
    private QuestionView question;
    private FlashcardView flashcard;
    private FeedbackView feedbackView;
    private SummaryView summaryView;
    private ConfidenceChips confidence;
    private VBox hintBox;
    private Button hintButton;
    private Button checkButton;
    private HBox actionRow;
    private Label checkError;
    private List<Node> actionChildren;
    private SourceSheet sheet;
    private Runnable primary;
    /** The node the page last gave focus to; a delayed focus request applies only while it is still this one. */
    private Node focusTarget;
    /** The question's hint, step by step, once asked for (null before); {@link #hintsShown} are on screen. */
    private List<Hint> hintSteps;
    private int hintsShown;
    private Label hintTitle;
    private VBox hintLines;
    private ConfirmDialog leaveDialog;
    private boolean disposed;
    private long shownAt;
    private int answered;
    private int hintsUsed;

    public StudyPage(AppContext context, Navigator navigator, Destination.Study destination) {
        this.context = Objects.requireNonNull(context, "context");
        this.navigator = Objects.requireNonNull(navigator, "navigator");
        this.destination = Objects.requireNonNull(destination, "destination");
        // The navigator builds a view before it makes it current: this is where the session was opened from.
        this.cameFrom = navigator.current();
        this.request = destination.request();
        root.getStyleClass().add("study-page");
        root.getStylesheets().add(STYLESHEET);

        Button leave = leaveButton;
        leave.getStyleClass().addAll("quiet", "small", "study-leave");
        leave.setOnAction(event -> requestLeave());
        // Escape leaves. The button never takes the keyboard focus (not by Tab, a click or the window's own
        // focus pass when a focused button goes away), so Enter can't leave the session by accident.
        leave.setFocusTraversable(false);
        where.getStyleClass().addAll("medium", "study-where");
        where.setMinWidth(0);
        counter.getStyleClass().addAll("medium", "study-counter");
        counter.setMinWidth(Region.USE_PREF_SIZE);
        Region left = new Region();
        HBox.setHgrow(left, Priority.ALWAYS);
        Region right = new Region();
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox top = new HBox(leave, left, where, right, counter);
        top.setAlignment(Pos.CENTER);
        top.getStyleClass().add("study-top");
        bar.getStyleClass().add("study-progress");
        bar.setMaxWidth(Double.MAX_VALUE);

        stage.getStyleClass().add("study-stage");
        stage.setMaxWidth(720);
        centring.getStyleClass().add("study-centring");
        centring.setAlignment(Pos.TOP_CENTER);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.getStyleClass().add("study-scroller");
        scroller.viewportBoundsProperty().addListener((obs, old, bounds) -> {
            centring.setMinHeight(bounds.getHeight());
            recentre();
        });
        scroller.widthProperty().addListener((obs, old, width) -> recentre());
        stage.widthProperty().addListener((obs, old, width) -> recentre());
        VBox.setVgrow(scroller, Priority.ALWAYS);
        footer.getStyleClass().add("study-footer");

        VBox layout = new VBox(bar, top, scroller, footer);
        layout.getStyleClass().add("study-layout");
        root.getChildren().add(layout);
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::key);
        showLoading();
    }

    @Override
    public Region root() {
        return root;
    }

    @Override
    public void onShown() {
        start();
    }

    @Override
    public void dispose() {
        disposed = true;
        if (leaveDialog != null) {
            // Another page took over while "Leave this session?" was open: it no longer applies.
            leaveDialog.dismiss();
            leaveDialog = null;
        }
        tasks.dispose();
        disposer.dispose();
    }

    // ---- what tests and the screenshot tour read ---------------------------------------------------

    /** The session on screen, or null while it starts. */
    public StudySession session() {
        return session;
    }

    /** What the page is showing. */
    public State state() {
        return state;
    }

    /** The item on screen, or null outside a question. */
    public SessionItem currentItem() {
        return queue != null && queue.hasCurrent() && (state == State.QUESTION || state == State.CHECKING
                || state == State.FEEDBACK) ? queue.current() : null;
    }

    /** The counter at the top, e.g. "3 of 17" (empty outside a session). */
    public String counterText() {
        return counter.getText();
    }

    /** Hints shown in this session. */
    public int hintsUsed() {
        return hintsUsed;
    }

    /** True while the page of the current answer's source is open beside the session. */
    public boolean isSourceOpen() {
        return sheet != null && !sheet.isClosing();
    }

    QuestionView question() {
        return question;
    }

    FlashcardView flashcard() {
        return flashcard;
    }

    FeedbackView feedback() {
        return feedbackView;
    }

    SummaryView summary() {
        return summaryView;
    }

    ConfidenceChips confidence() {
        return confidence;
    }

    Button checkButton() {
        return checkButton;
    }

    Button hintButton() {
        return hintButton;
    }

    SourceSheet sourceSheet() {
        return sheet;
    }

    // ---- starting -----------------------------------------------------------------------------------

    private Workspace workspace() {
        return context.workspace();
    }

    private Clock clock() {
        return context.clock();
    }

    /** Starts the session asked for (again, for "Study more" and after a session ended). */
    private void start() {
        Workspace workspace = workspace();
        if (workspace == null) {
            return;
        }
        tasks.dispose();
        tasks = new Disposer();
        closeSheet();
        session = null;
        queue = null;
        answered = 0;
        hintsUsed = 0;
        showLoading();
        SessionRequest wanted = request;
        tasks.add(UiTasks.run(() -> {
            StudySession started = workspace.practice().start(wanted);
            StudyNext next = started.items().isEmpty() ? workspace.practice().studyNext(wanted.courseId()) : null;
            return new Started(started, next);
        }, this::begin, this::showStartError));
    }

    private record Started(StudySession session, StudyNext next) {
    }

    private void begin(Started started) {
        session = started.session();
        where.setText(StudyText.sessionTitle(session));
        queue = new StudyQueue(session.items());
        if (queue.isEmpty()) {
            showEmpty(started.next());
            return;
        }
        showCard();
    }

    private void showLoading() {
        state = State.LOADING;
        primary = null;
        counter.setText("");
        centring.setAlignment(Pos.TOP_CENTER);
        VBox options = new VBox(12);
        for (int i = 0; i < 4; i++) {
            options.getChildren().add(Skeleton.block(-1, 64));
        }
        VBox prompt = new VBox(12, Skeleton.block(-1, 34), Skeleton.block(420, 34));
        stage.getChildren().setAll(Skeleton.line(180), prompt, options);
        stage.setSpacing(32);
        stage.setAlignment(Pos.TOP_LEFT);
        footer.getChildren().clear();
    }

    private void showStartError(UserMessage message) {
        state = State.ERROR;
        EmptyState empty = new EmptyState(EmptyIllustration.Kind.SEARCH, "This session couldn't start", message.text())
                .primary("Try again", this::start)
                .secondary(homeLabel(), this::home);
        showEmptyState(empty, this::start);
    }

    /**
     * Nothing in the session. A module quiz or weak spots with nothing to practise offers the rest of the course
     * when it has questions ready; otherwise the page says when the next review is.
     */
    private void showEmpty(StudyNext next) {
        state = State.EMPTY;
        boolean readyElsewhere = next != null && !next.caughtUp()
                && (request.kind() == SessionKind.MODULE_QUIZ || request.kind() == SessionKind.WEAK_CONCEPTS);
        String nextReview = next == null ? null : StudyText.nextReview(next.nextReviewAt(), clock());
        String ready = next == null ? null : StudyText.readyToStudy(next.total(), request.courseId() != null);
        EmptyState empty = switch (request.kind()) {
            case MODULE_QUIZ -> new EmptyState(EmptyIllustration.Kind.QUESTIONS,
                    "Nothing to practise in this module right now", readyElsewhere ? ready
                    : nextReview != null ? nextReview
                    : "When this module has questions ready, you can practise them here.");
            case WEAK_CONCEPTS -> new EmptyState(EmptyIllustration.Kind.CAUGHT_UP, "No weak spots right now",
                    "Concepts you find hard show up here as you study." + (readyElsewhere ? "\n" + ready : ""));
            case FLASHCARDS -> new EmptyState(EmptyIllustration.Kind.CAUGHT_UP, "Nothing to review right now",
                    nextReview != null ? nextReview : "Flashcards you study come back here when they're due.");
            case STUDY_NEXT -> new EmptyState(EmptyIllustration.Kind.CAUGHT_UP, "Nothing to review right now",
                    nextReview != null ? nextReview : "Questions you study come back here when they're due.");
        };
        if (readyElsewhere) {
            empty.primary("Start studying", this::studyNextInCourse).secondary(homeLabel(), this::home);
            showEmptyState(empty, this::studyNextInCourse);
        } else {
            empty.primary(homeLabel(), this::home);
            showEmptyState(empty, this::home);
        }
    }

    /** Study next in the session's course (or every course), in this page. */
    private void studyNextInCourse() {
        request = new SessionRequest(SessionKind.STUDY_NEXT, request.courseId(), null, request.size());
        start();
    }

    private void showEnded() {
        state = State.ENDED;
        closeSheet();
        EmptyState empty = new EmptyState(EmptyIllustration.Kind.PROGRESS, "This study session has ended",
                "Your answers so far are saved. Start a new session to pick up the questions still due.")
                .primary("Start a new session", this::start)
                .secondary(homeLabel(), this::home);
        showEmptyState(empty, this::start);
    }

    private void showEmptyState(EmptyState empty, Runnable enter) {
        counter.setText("");
        question = null;
        flashcard = null;
        feedbackView = null;
        summaryView = null;
        primary = enter;
        centring.setAlignment(Pos.CENTER);
        stage.setAlignment(Pos.CENTER);
        stage.getChildren().setAll(empty);
        Motion.enter(stage);
        Node first = empty.actions().getChildren().isEmpty() ? null : empty.actions().getChildren().getFirst();
        footer.getChildren().setAll(first instanceof Button button
                ? StudyParts.legend("Enter", button.getText(), "Esc", "Close") : StudyParts.legend("Esc", "Close"));
        leaveButton.setText("Close");
        if (first != null) {
            focus(first);
        }
    }

    // ---- one card -----------------------------------------------------------------------------------

    private void showCard() {
        SessionItem item = queue.current();
        state = State.QUESTION;
        question = null;
        flashcard = null;
        feedbackView = null;
        confidence = null;
        hintBox = null;
        hintButton = null;
        checkButton = null;
        checkError = null;
        actionRow = null;
        actionChildren = null;
        hintSteps = null;
        hintsShown = 0;
        hintTitle = null;
        hintLines = null;
        shownAt = clock().millis();
        centring.setAlignment(Pos.TOP_CENTER);
        leaveButton.setText("Leave session");
        counter.setText(queue.position() + " of " + queue.total());
        counter.setAccessibleText("Question " + queue.position() + " of " + queue.total());
        bar.setProgress(queue.finished() / (double) queue.total());

        List<Node> body = new ArrayList<>();
        Node focus;
        if (item.type() == ItemType.FLASHCARD) {
            flashcard = new FlashcardView(item);
            flashcard.setHeader(overline(item));
            flashcard.setOnRate(rating -> rate(item, rating));
            flashcard.setOnTurn(() -> footer.getChildren().setAll(
                    StudyParts.legend("1", "Again", "2", "Hard", "3", "Good", "4", "Easy", "Esc", "Leave")));
            body.add(flashcard.root());
            primary = this::flashcardEnter;
            focus = flashcard.initialFocus();
            loadNextReviews(item);
            footer.getChildren().setAll(StudyParts.legend("Space", "Show answer", "Esc", "Leave"));
        } else {
            question = QuestionView.of(item);
            question.setOnChange(this::answerChanged);
            body.add(overline(item));
            VBox head = new VBox(0, question.prompt());
            head.getStyleClass().add("study-question");
            body.add(head);
            if (question.body() != null) {
                body.add(question.body());
            }
            hintBox = new VBox();
            hintBox.getStyleClass().add("hint-holder");
            hintBox.setManaged(false);
            hintBox.setVisible(false);
            body.add(hintBox);
            body.add(answerRow(item));
            primary = this::check;
            focus = question.initialFocus();
            footer.getChildren().setAll(questionLegend(item));
            answerChanged();
        }
        stage.setSpacing(32);
        stage.setAlignment(Pos.TOP_LEFT);
        stage.getChildren().setAll(body);
        Motion.enter(stage);
        scroller.setVvalue(0);
        focus(focus != null ? focus : root);
    }

    /**
     * Gives {@code target} the keyboard focus. Right after the page opens, the scroll pane has not attached its
     * content to the window yet and a focus request would be ignored (the window would then focus its first
     * button, "Leave session"), so the request waits until {@code target} is in the window.
     */
    private void focus(Node target) {
        focusTarget = target;
        if (target.getScene() != null) {
            target.requestFocus();
            return;
        }
        target.sceneProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Scene> property, Scene old, Scene scene) {
                if (scene == null) {
                    return;
                }
                property.removeListener(this);
                // After the pulse that attached it, in which the window settles its own focus.
                Platform.runLater(() -> {
                    if (focusTarget == target && target.getScene() != null) {
                        target.requestFocus();
                    }
                });
            }
        });
    }

    /**
     * Moves the keyboard focus to the question (the page itself for choices and flashcards) when it is on
     * {@code control} or inside it, before {@code control} is disabled or removed. Otherwise the window would hand
     * the focus on to the next button, and the next Enter would press that.
     */
    private void moveFocusOff(Node control) {
        Node owner = root.getScene() == null ? null : root.getScene().getFocusOwner();
        if (control == null || owner == null || !isInside(owner, control)) {
            return;
        }
        Node target = question == null ? null : question.initialFocus();
        focus(target != null && !target.isDisabled() && target.isVisible() ? target : root);
    }

    private static boolean isInside(Node node, Node ancestor) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
    }

    /**
     * Keeps the column where it is when the vertical scroll bar comes and goes: the bar takes its width from the
     * right of the page, which would shift the centred column left by half of it.
     */
    private void recentre() {
        double viewport = scroller.getViewportBounds().getWidth();
        double bar = scroller.getWidth() - scroller.snappedLeftInset() - scroller.snappedRightInset() - viewport;
        double room = viewport - centring.snappedLeftInset() - centring.snappedRightInset() - stage.getWidth();
        stage.setTranslateX(bar > 0.5 && room > 0 ? Math.min(bar, room) / 2 : 0);
    }

    /** Enter on a flashcard: turns it, then presses the rating that has focus. */
    private void flashcardEnter() {
        if (flashcard == null) {
            return;
        }
        if (!flashcard.isTurned()) {
            flashcard.turn();
            return;
        }
        Node owner = root.getScene() == null ? null : root.getScene().getFocusOwner();
        if (owner instanceof Button button && button.getStyleClass().contains("rating-button")) {
            button.fire();
        }
    }

    private Node overline(SessionItem item) {
        List<String> parts = new ArrayList<>();
        parts.add(StudyText.typeLabel(item.type()));
        parts.addAll(item.conceptNames().stream().limit(2).toList());
        Label text = StudyParts.overline(String.join("  ·  ", parts));
        HBox row = new HBox(10, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("study-overline-row");
        if (queue.isComeback()) {
            Label again = new Label("Coming back to this one", Icons.of(Feather.ROTATE_CCW, 12));
            again.getStyleClass().addAll("medium", "study-tag", "comeback");
            again.setMinWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(again);
        } else if (item.isNew()) {
            Label fresh = new Label("First time");
            fresh.getStyleClass().addAll("medium", "study-tag", "first-time");
            fresh.setMinWidth(Region.USE_PREF_SIZE);
            row.getChildren().add(fresh);
        }
        return row;
    }

    private HBox questionLegend(SessionItem item) {
        List<String> keys = new ArrayList<>();
        switch (item.type()) {
            case MCQ -> keys.addAll(List.of("1–" + Math.max(2, item.choices().size()), "Choose"));
            case TRUE_FALSE -> keys.addAll(List.of("T / F", "Choose"));
            case SHORT_ANSWER -> keys.addAll(List.of("Shift Enter", "New line"));
            default -> {
            }
        }
        keys.addAll(List.of("Enter", "Check answer"));
        if (hintLeft() && item.type() != ItemType.CLOZE && item.type() != ItemType.SHORT_ANSWER) {
            keys.addAll(List.of("H", hintsShown == 0 ? "Hint" : "Another hint"));
        }
        keys.addAll(List.of("Esc", "Leave"));
        return StudyParts.legend(keys.toArray(String[]::new));
    }

    /** How sure, the hint and "Check answer". */
    private Node answerRow(SessionItem item) {
        VBox box = new VBox(20);
        box.getStyleClass().add("answer-row");
        if (question.asksConfidence()) {
            confidence = new ConfidenceChips();
            Label sure = new Label("How sure are you?");
            sure.getStyleClass().addAll("medium", "confidence-label");
            sure.setMinWidth(Region.USE_PREF_SIZE);
            HBox sureRow = new HBox(14, sure, confidence);
            sureRow.setAlignment(Pos.CENTER_LEFT);
            sureRow.getStyleClass().add("confidence-row");
            box.getChildren().add(sureRow);
        }
        checkButton = new Button("Check answer");
        checkButton.getStyleClass().addAll("primary", "large", "check-button");
        checkButton.setOnAction(event -> check());
        HBox left = new HBox(12, checkButton, StudyParts.key("Enter"));
        left.setAlignment(Pos.CENTER_LEFT);
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        actionRow = new HBox(12, left, gap);
        actionRow.setAlignment(Pos.CENTER_LEFT);
        actionRow.getStyleClass().add("study-actions");
        if (item.hintAvailable()) {
            hintButton = new Button("Show a hint", Icons.of(Feather.HELP_CIRCLE, 15));
            hintButton.getStyleClass().addAll("quiet", "hint-button");
            hintButton.setOnAction(event -> showHint());
            actionRow.getChildren().add(hintButton);
        }
        checkError = new Label();
        checkError.getStyleClass().addAll("text-caption", "check-error");
        checkError.setWrapText(true);
        checkError.setManaged(false);
        checkError.setVisible(false);
        box.getChildren().addAll(actionRow, checkError);
        return box;
    }

    private void answerChanged() {
        if (checkButton != null && question != null) {
            checkButton.setDisable(!question.hasAnswer());
        }
    }

    private void loadNextReviews(SessionItem item) {
        Workspace workspace = workspace();
        FlashcardView card = flashcard;
        tasks.add(UiTasks.run(() -> workspace.practice().nextReviews(item.itemId()), due -> {
            if (flashcard == card) {
                card.setNextReviews(due, clock().millis());
            }
        }, message -> { }));
    }

    // ---- hints --------------------------------------------------------------------------------------

    /** True while the question on screen has a hint step not shown yet. */
    private boolean hintLeft() {
        return hintButton != null && actionRow != null && actionRow.getChildren().contains(hintButton);
    }

    /**
     * Shows the next step of the hint for the question on screen (H, or "Show a hint"): where to look in the
     * material first, then the hint itself. The steps are fetched once, with the first; asking marks the answer
     * as hinted.
     */
    public void showHint() {
        if (state != State.QUESTION || question == null || !hintLeft()) {
            return;
        }
        if (hintSteps != null) {
            revealHint();
            return;
        }
        SessionItem item = question.item();
        Button button = hintButton;
        moveFocusOff(button);
        button.setDisable(true);
        Workspace workspace = workspace();
        String sessionId = session.id();
        tasks.add(UiTasks.run(() -> workspace.practice().hints(sessionId, item.itemId()), steps -> {
            button.setDisable(false);
            if (state == State.QUESTION && question != null && question.item() == item && !steps.isEmpty()) {
                hintSteps = List.copyOf(steps);
                revealHint();
            }
        }, message -> {
            button.setDisable(false);
            if (state != State.QUESTION || question == null || question.item() != item) {
                return;
            }
            if (ended(message)) {
                showEnded();
            } else {
                Toasts.error(root, message);
            }
        }));
    }

    /** Adds the next hint step to the hint callout (opening it with the first). */
    private void revealHint() {
        if (hintSteps == null || hintsShown >= hintSteps.size()) {
            return;
        }
        Hint step = hintSteps.get(hintsShown);
        hintsShown++;
        hintsUsed++;
        question.applyHint(step);
        Label text = new Label(step.text());
        text.getStyleClass().addAll("text-reading", "hint-text");
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        if (hintLines == null) {
            hintTitle = new Label("Hint", Icons.of(Feather.HELP_CIRCLE, 14));
            hintTitle.getStyleClass().addAll("semibold", "hint-title");
            hintLines = new VBox(4);
            hintLines.getStyleClass().add("hint-lines");
            Label cost = new Label("A question answered with a hint comes back a little sooner.");
            cost.getStyleClass().addAll("text-caption", "hint-cost");
            cost.setWrapText(true);
            cost.setMinHeight(Region.USE_PREF_SIZE);
            VBox callout = new VBox(6, hintTitle, hintLines, cost);
            callout.getStyleClass().add("hint-callout");
            hintBox.getChildren().setAll(callout);
            hintBox.setManaged(true);
            hintBox.setVisible(true);
            hintLines.getChildren().add(text);
            Motion.enter(hintBox);
        } else {
            hintLines.getChildren().add(text);
            Motion.fadeIn(text, Motion.ROUTE);
        }
        if (hintSteps.size() > 1) {
            hintTitle.setText("Hint " + hintsShown + " of " + hintSteps.size());
        }
        if (hintsShown < hintSteps.size()) {
            hintButton.setText("Another hint");
        } else {
            moveFocusOff(hintButton);
            actionRow.getChildren().remove(hintButton);
        }
        footer.getChildren().setAll(questionLegend(question.item()));
    }

    // ---- answering ----------------------------------------------------------------------------------

    /** Checks the answer on screen (Enter); nudges the answer area when there is nothing to check. */
    private void check() {
        if (state != State.QUESTION || question == null) {
            return;
        }
        if (!question.hasAnswer()) {
            Motion.shake(question.nudgeTarget());
            return;
        }
        SessionItem item = question.item();
        Response response = Response.answer(item.itemId(), question.answer(),
                confidence == null ? null : confidence.getConfidence(), hintsShown > 0, elapsed());
        state = State.CHECKING;
        // "Check answer" (or a confidence chip) goes quiet or away while the answer is checked.
        moveFocusOff(actionRow.getParent());
        question.setLocked(true);
        hideCheckError();
        if (confidence != null) {
            confidence.setDisable(true);
        }
        if (item.type() == ItemType.SHORT_ANSWER) {
            Label reading = new Label("Your study assistant is reading your answer…");
            reading.getStyleClass().add("checking-label");
            HBox checking = new HBox(12, new ActivityIndicator(18), reading);
            checking.setAlignment(Pos.CENTER_LEFT);
            checking.getStyleClass().add("checking");
            actionChildren = List.copyOf(actionRow.getChildren());
            actionRow.getChildren().setAll(checking);
        } else {
            checkButton.setDisable(true);
        }
        submit(item, response);
    }

    private void rate(SessionItem item, Rating rating) {
        if (state != State.QUESTION || flashcard == null) {
            return;
        }
        state = State.CHECKING;
        moveFocusOff(flashcard.root());
        flashcard.setLocked(true);
        submit(item, Response.flashcard(item.itemId(), rating, elapsed()));
    }

    private long elapsed() {
        return Math.max(0, clock().millis() - shownAt);
    }

    private void submit(SessionItem item, Response response) {
        Workspace workspace = workspace();
        String sessionId = session.id();
        tasks.add(UiTasks.run(() -> workspace.practice().submit(sessionId, response),
                result -> showFeedback(item, result), message -> submitFailed(item, message)));
    }

    private void submitFailed(SessionItem item, UserMessage message) {
        if (ended(message)) {
            showEnded();
            return;
        }
        state = State.QUESTION;
        if (flashcard != null) {
            flashcard.setLocked(false);
            Toasts.error(root, message);
            return;
        }
        question.setLocked(false);
        if (confidence != null) {
            confidence.setDisable(false);
        }
        // Put "Check answer" back where the checking line was.
        if (actionChildren != null) {
            actionRow.getChildren().setAll(actionChildren);
            actionChildren = null;
        }
        answerChanged();
        checkError.setText(message.text());
        checkError.setManaged(true);
        checkError.setVisible(true);
        if (checkButton != null) {
            checkButton.requestFocus();
        }
    }

    private void hideCheckError() {
        if (checkError != null) {
            checkError.setManaged(false);
            checkError.setVisible(false);
        }
    }

    private static boolean ended(UserMessage message) {
        return message.equals(StudyMessages.SESSION_ENDED) || message.equals(StudyMessages.SESSION_NOT_FOUND);
    }

    private void showFeedback(SessionItem item, Feedback result) {
        state = State.FEEDBACK;
        answered++;
        boolean comesBack = !result.disputed() && result.rating() == Rating.AGAIN && queue.requeueCurrent();
        bar.setProgress((queue.finished() + 1) / (double) queue.total());
        counter.setText(queue.position() + " of " + queue.total());
        if (flashcard != null) {
            flashcard.showResult(result);
        } else {
            question.showResult(result);
            if (confidence != null) {
                confidence.setDisable(true);
            }
            stage.getChildren().removeIf(node -> node.getStyleClass().contains("answer-row"));
        }
        feedbackView = new FeedbackView(item, result, comesBack, queue.isLast(), clock(), new FeedbackView.Actions() {
            @Override
            public void openSource(SourceRef source) {
                StudyPage.this.openSource(item, source);
            }

            @Override
            public void dispute(Feedback feedback) {
                StudyPage.this.dispute(item, feedback);
            }

            @Override
            public void next() {
                StudyPage.this.next();
            }
        });
        stage.getChildren().add(feedbackView.root());
        primary = this::next;
        footer.getChildren().setAll(feedbackLegend());
        FeedbackView shown = feedbackView;
        focus(shown.continueButton());
        Fx.run(() -> reveal(shown.root()));
    }

    /**
     * Scrolls just far enough to show all of {@code node} (the feedback and its Continue button), keeping as
     * much of the question above it on screen as fits; a node taller than the page shows its top.
     */
    private void reveal(Node node) {
        root.applyCss();
        root.layout();
        double viewport = scroller.getViewportBounds().getHeight();
        double overflow = centring.getHeight() - viewport;
        if (overflow <= 0 || node.getScene() == null) {
            return;
        }
        Bounds bounds = centring.sceneToLocal(node.localToScene(node.getBoundsInLocal()));
        double margin = 32;
        double showBottom = bounds.getMaxY() + margin - viewport;
        double showTop = bounds.getMinY() - margin;
        double offset = Math.max(0, Math.min(showBottom, showTop));
        Motion.animate(scroller.vvalueProperty(), Math.min(1, offset / overflow), Motion.PROGRESS);
    }

    private HBox feedbackLegend() {
        return StudyParts.legend("Enter", queue.isLast() ? "See summary" : "Continue", "Esc", "Leave");
    }

    private void dispute(SessionItem item, Feedback feedback) {
        Workspace workspace = workspace();
        FeedbackView view = feedbackView;
        Node owner = root.getScene() == null ? null : root.getScene().getFocusOwner();
        if (owner != null && view.disputeButton() != null && isInside(owner, view.disputeButton())) {
            focus(view.continueButton());
        }
        view.setDisputing(true);
        tasks.add(UiTasks.run(() -> workspace.practice().dispute(feedback.attemptId()), disputed -> {
            if (feedbackView != view) {
                return;
            }
            queue.dropComebacks(item.itemId());
            view.update(disputed, queue.comesBack(item.itemId()));
            view.setLast(queue.isLast());
            if (question instanceof ShortAnswerQuestion shortAnswer) {
                shortAnswer.showResult(disputed);
            }
            counter.setText(queue.position() + " of " + queue.total());
            bar.setProgress((queue.finished() + 1) / (double) queue.total());
            footer.getChildren().setAll(feedbackLegend());
            view.continueButton().requestFocus();
        }, view::showDisputeError));
    }

    /** Moves to the next card, or finishes the session after the last one (Enter on the feedback). */
    private void next() {
        if (state != State.FEEDBACK) {
            return;
        }
        closeSheet();
        if (queue.advance()) {
            showCard();
        } else {
            finish();
        }
    }

    // ---- "From your material" -----------------------------------------------------------------------

    /** Opens the page the current answer comes from beside the session (tour and tests). */
    public boolean openSource() {
        if (state != State.FEEDBACK || feedbackView == null || feedbackView.feedback().source() == null) {
            return false;
        }
        openSource(queue.current(), feedbackView.feedback().source());
        return true;
    }

    private void openSource(SessionItem item, SourceRef source) {
        if (isSourceOpen()) {
            return;
        }
        sheet = new SourceSheet(workspace(), tasks, item.itemId(), source, () -> {
            sheet = null;
            // Back to Continue, as the key legend says, rather than the link that opened the page.
            focus(feedbackView != null && state == State.FEEDBACK ? feedbackView.continueButton() : root);
        });
        root.getChildren().add(sheet);
        sheet.open();
    }

    private void closeSheet() {
        if (sheet != null) {
            SourceSheet open = sheet;
            sheet = null;
            root.getChildren().remove(open);
        }
    }

    // ---- the end --------------------------------------------------------------------------------------

    private void finish() {
        Workspace workspace = workspace();
        String sessionId = session.id();
        String courseId = session.courseId();
        state = State.LOADING;
        primary = null;
        bar.setProgress(1);
        counter.setText("");
        Label wrapping = new Label("Adding up your session…");
        wrapping.getStyleClass().add("checking-label");
        HBox busy = new HBox(12, new ActivityIndicator(18), wrapping);
        busy.setAlignment(Pos.CENTER_LEFT);
        stage.getChildren().setAll(busy);
        footer.getChildren().clear();
        int hints = hintsUsed;
        tasks.add(UiTasks.run(() -> {
            SessionSummary summary = workspace.practice().finish(sessionId);
            StudyNext more = workspace.practice().studyNext(courseId);
            List<ForecastDay> forecast;
            try {
                forecast = courseId == null ? workspace.progress().overview().forecast()
                        : workspace.progress().course(courseId).forecast();
            } catch (RuntimeException e) {
                // The forecast only adds a sentence; the summary stands without it.
                forecast = List.of();
            }
            return new Finished(summary, new SummaryView.Extras(more, forecast, hints));
        }, this::showSummary, message -> {
            state = State.ERROR;
            EmptyState empty = new EmptyState(EmptyIllustration.Kind.PROGRESS, "Your answers are saved",
                    message.text()).primary(homeLabel(), this::home);
            showEmptyState(empty, this::home);
        }));
    }

    private record Finished(SessionSummary summary, SummaryView.Extras extras) {
    }

    private void showSummary(Finished finished) {
        state = State.SUMMARY;
        question = null;
        flashcard = null;
        feedbackView = null;
        centring.setAlignment(Pos.TOP_CENTER);
        summaryView = new SummaryView(finished.summary(), finished.extras(), clock(), this::studyMore, this::home,
                this::progress, homeLabel());
        stage.setAlignment(Pos.TOP_LEFT);
        stage.getChildren().setAll(summaryView.root());
        summaryView.play(finished.summary());
        Button main = summaryView.primaryButton();
        primary = main::fire;
        footer.getChildren().setAll(StudyParts.legend("Enter", main.getText(), "Esc", "Close"));
        leaveButton.setText("Close");
        scroller.setVvalue(0);
        focus(main);
    }

    /** More of what is ready now, in this page so leaving still returns to where the session was opened. */
    private void studyMore() {
        String courseId = request.courseId();
        request = request.kind() == SessionKind.STUDY_NEXT || request.kind() == SessionKind.WEAK_CONCEPTS
                ? request : new SessionRequest(SessionKind.STUDY_NEXT, courseId, null, request.size());
        start();
    }

    private void progress() {
        navigator.replace(new Destination.Progress(session == null ? null : session.courseId()), true);
    }

    // ---- leaving --------------------------------------------------------------------------------------

    /**
     * Leaves the session (Escape, or "Leave session"). After an answer it asks first, saying plainly that the
     * answers are saved; on the summary and the empty states it just goes.
     */
    public void requestLeave() {
        if (isSourceOpen()) {
            sheet.close();
            return;
        }
        boolean midSession = session != null && answered > 0
                && (state == State.QUESTION || state == State.CHECKING || state == State.FEEDBACK);
        if (!midSession) {
            leave();
            return;
        }
        int left = Math.max(0, queue.total() - queue.finished() - (state == State.FEEDBACK ? 1 : 0));
        String message = "Your answers so far are saved."
                + (left > 0 ? " The questions you haven't reached stay ready for next time." : "");
        if (leaveDialog != null) {
            return;
        }
        ConfirmDialog dialog = ConfirmDialog.of("Leave this session?", message, "Leave session")
                .cancelLabel("Keep studying");
        leaveDialog = dialog;
        dialog.show(root).thenAccept(confirmed -> {
            if (leaveDialog == dialog) {
                leaveDialog = null;
            }
            if (confirmed && !disposed) {
                finishQuietly();
                leave();
            }
        });
    }

    /** Wraps the session up in the background as the learner leaves, so it counts in their progress. */
    private void finishQuietly() {
        Workspace workspace = workspace();
        String sessionId = session.id();
        UiTasks.run(() -> workspace.practice().finish(sessionId), summary -> { }, message -> { });
    }

    private void leave() {
        if (navigator.canGoBack()) {
            navigator.back();
        } else {
            navigator.reset(Destination.HOME);
        }
    }

    /** Back to where the session was opened from when that was Home or a course, otherwise Home. */
    private void home() {
        boolean returnable = cameFrom instanceof Destination.Home || cameFrom instanceof Destination.Course;
        if (returnable && navigator.canGoBack()) {
            navigator.back();
        } else {
            navigator.reset(Destination.HOME);
        }
    }

    private String homeLabel() {
        return cameFrom instanceof Destination.Course && navigator.canGoBack() ? "Back to course" : "Back to Home";
    }

    // ---- keys ---------------------------------------------------------------------------------------

    private void key(KeyEvent event) {
        KeyCode code = event.getCode();
        if (isSourceOpen()) {
            if (code == KeyCode.ESCAPE) {
                event.consume();
                sheet.close();
            }
            return;
        }
        if (event.isShortcutDown() || event.isAltDown()) {
            return;
        }
        boolean inText = event.getTarget() instanceof TextInputControl;
        if (code == KeyCode.ESCAPE) {
            event.consume();
            requestLeave();
            return;
        }
        if (code == KeyCode.ENTER) {
            if (inText && event.isShiftDown()) {
                if (question instanceof ShortAnswerQuestion shortAnswer) {
                    event.consume();
                    shortAnswer.newLine();
                }
                return;
            }
            if (event.getTarget() instanceof Button focused && !focused.isDisabled()) {
                // Enter presses the button that has focus ("From your material", "Dispute grade").
                event.consume();
                focused.fire();
                return;
            }
            if (primary != null) {
                event.consume();
                primary.run();
            }
            return;
        }
        if (inText) {
            return;
        }
        if (state == State.QUESTION && flashcard != null && flashcard.key(event)) {
            event.consume();
            return;
        }
        if (state == State.QUESTION && question != null) {
            if (code == KeyCode.H && hintLeft()) {
                event.consume();
                showHint();
                return;
            }
            if (question.key(event)) {
                event.consume();
            }
        }
    }
}
