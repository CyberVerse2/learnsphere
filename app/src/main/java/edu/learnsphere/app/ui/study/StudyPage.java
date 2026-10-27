package edu.learnsphere.app.ui.study;

import edu.learnsphere.app.AppContext;
import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.Destination;
import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.Navigator;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.app.ui.View;
import edu.learnsphere.app.ui.components.Chip;
import edu.learnsphere.app.ui.components.ConfidenceChips;
import edu.learnsphere.app.ui.components.EmptyIllustration;
import edu.learnsphere.app.ui.components.EmptyState;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.ProgressBarSlim;
import edu.learnsphere.app.ui.components.ProgressRing;
import edu.learnsphere.app.ui.components.Skeleton;
import edu.learnsphere.app.ui.components.StatTile;
import edu.learnsphere.app.ui.components.Toasts;
import edu.learnsphere.app.ui.components.Tone;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.ConceptChange;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SessionSummary;
import edu.learnsphere.core.service.SourceRef;
import edu.learnsphere.core.service.StudySession;
import edu.learnsphere.core.service.Workspace;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Polyline;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

/**
 * A study session in focus mode: a slim progress bar at the top, the prompt set large, options as big rows
 * with number shortcuts, confidence as three chips, then the explanation, where it came from in the
 * material and when the question comes back. Flashcards flip and are rated Again, Hard, Good or Easy. The
 * session ends on a summary of accuracy, time and what improved.
 *
 * <p>Keys: 1–9 choose an option (or rate a flashcard), Enter checks the answer and moves on, Space turns a
 * flashcard, Escape ends the session.
 */
public final class StudyPage implements View {

    private final AppContext context;
    private final Navigator navigator;
    private final Destination.Study destination;
    private final Disposer disposer = new Disposer();
    private final VBox root = new VBox();
    private final ProgressBarSlim bar = new ProgressBarSlim(4);
    private final Label counter = new Label();
    private final Label where = new Label();
    private final VBox stage = new VBox(28);
    private final ScrollPane scroller = new ScrollPane();
    private StudySession session;
    private int index;
    private int correctCount;
    private Runnable primaryAction;
    private List<Node> optionRows = List.of();
    private String chosen;
    private ConfidenceChips confidence;
    private TextInputControl typed;
    private long shownAt;
    private Feedback feedback;
    private Runnable flip;
    private List<Runnable> ratings = List.of();

    public StudyPage(AppContext context, Navigator navigator, Destination.Study destination) {
        this.context = context;
        this.navigator = navigator;
        this.destination = destination;
        root.getStyleClass().add("study-page");

        Button end = new Button("End session", Icons.of(Feather.X, 16));
        end.getStyleClass().addAll("quiet", "neutral", "small");
        end.setOnAction(event -> end());
        where.getStyleClass().add("text-caption");
        counter.getStyleClass().add("text-caption");
        counter.setMinWidth(Region.USE_PREF_SIZE);
        Region left = new Region();
        HBox.setHgrow(left, Priority.ALWAYS);
        Region right = new Region();
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox top = new HBox(end, left, where, right, counter);
        top.setAlignment(Pos.CENTER);
        top.getStyleClass().add("study-top");
        bar.getStyleClass().add("study-progress");
        bar.setMaxWidth(Double.MAX_VALUE);

        stage.getStyleClass().add("study-stage");
        stage.setMaxWidth(720);
        stage.getChildren().setAll(Skeleton.line(160), Skeleton.block(-1, 90), Skeleton.block(-1, 240));
        StackPane centring = new StackPane(stage);
        centring.setAlignment(Pos.TOP_CENTER);
        centring.getStyleClass().add("study-centring");
        scroller.setContent(centring);
        scroller.setFitToWidth(true);
        scroller.getStyleClass().add("study-scroller");
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        VBox.setVgrow(scroller, Priority.ALWAYS);

        root.getChildren().addAll(bar, top, scroller);
        root.addEventFilter(KeyEvent.KEY_PRESSED, this::key);
    }

    @Override
    public Region root() {
        return root;
    }

    /** The session on screen, or null while it starts. */
    public StudySession session() {
        return session;
    }

    @Override
    public void onShown() {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        disposer.add(UiTasks.run(() -> workspace.practice().start(destination.request()), this::begin, message ->
                stage.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.SEARCH, "This session couldn't start",
                        message.text()).primary("Go to Home", () -> navigator.navigate(Destination.HOME)))));
    }

    @Override
    public void dispose() {
        disposer.dispose();
    }

    private void begin(StudySession started) {
        session = started;
        where.setText(started.moduleTitle() != null ? started.courseTitle() + " · " + started.moduleTitle()
                : started.courseTitle() != null ? started.courseTitle() : "Study next");
        if (started.items().isEmpty()) {
            counter.setText("");
            stage.getChildren().setAll(new EmptyState(EmptyIllustration.Kind.CAUGHT_UP, "You're all caught up",
                    "New reviews unlock tomorrow.").primary("Back to Home", () -> navigator.navigate(Destination.HOME)));
            return;
        }
        index = 0;
        showItem();
    }

    private void showItem() {
        SessionItem item = session.items().get(index);
        counter.setText((index + 1) + " of " + session.items().size());
        bar.setProgress(index / (double) session.items().size());
        feedback = null;
        chosen = null;
        typed = null;
        flip = null;
        ratings = List.of();
        optionRows = List.of();
        shownAt = context.clock().millis();

        List<String> tags = new ArrayList<>(item.conceptNames());
        if (item.isNew()) {
            tags.addFirst("New");
        }
        Label overline = new Label(String.join("  ·  ", tags).toUpperCase(Locale.ROOT));
        overline.getStyleClass().addAll("text-overline", "study-overline");
        Label prompt = new Label(item.type() == ItemType.TRUE_FALSE ? "True or false: " + item.prompt() : item.prompt());
        prompt.getStyleClass().addAll("text-display", "study-prompt");
        prompt.setWrapText(true);
        VBox head = new VBox(14, overline, prompt);

        List<Node> body = new ArrayList<>();
        body.add(head);
        switch (item.type()) {
            case MCQ, TRUE_FALSE -> body.add(options(item));
            case CLOZE -> body.add(typedAnswer(new TextField(), "Type the missing words"));
            case SHORT_ANSWER -> body.add(typedAnswer(new TextArea(), "Answer in a sentence or two"));
            case FLASHCARD -> body.add(flashcard(item));
        }
        if (item.type() != ItemType.FLASHCARD) {
            confidence = new ConfidenceChips();
            Label sure = new Label("How sure are you?");
            sure.getStyleClass().add("text-caption");
            HBox sureRow = new HBox(14, sure, confidence);
            sureRow.setAlignment(Pos.CENTER_LEFT);
            Button check = new Button("Check answer");
            check.getStyleClass().addAll("primary", "large");
            check.setOnAction(event -> submit(item));
            primaryAction = () -> submit(item);
            HBox actions = new HBox(16, check, sureRow);
            actions.setAlignment(Pos.CENTER_LEFT);
            actions.getStyleClass().add("study-actions");
            body.add(actions);
        }
        stage.getChildren().setAll(body);
        Motion.enter(stage);
        scroller.setVvalue(0);
        Fx.run(() -> {
            if (typed != null) {
                typed.requestFocus();
            } else {
                root.requestFocus();
            }
        });
    }

    private Node options(SessionItem item) {
        VBox list = new VBox(10);
        List<Node> rows = new ArrayList<>();
        for (int i = 0; i < item.choices().size(); i++) {
            String choice = item.choices().get(i);
            String value = item.type() == ItemType.TRUE_FALSE ? choice.toLowerCase(Locale.ROOT) : choice;
            Label key = new Label(Integer.toString(i + 1));
            key.getStyleClass().add("option-key");
            key.setMinWidth(Region.USE_PREF_SIZE);
            Label text = new Label(choice);
            text.getStyleClass().add("option-text");
            text.setWrapText(true);
            text.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(text, Priority.ALWAYS);
            StackPane mark = new StackPane();
            mark.getStyleClass().add("option-mark");
            mark.setMinSize(24, 24);
            HBox row = new HBox(16, key, text, mark);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("study-option");
            row.setUserData(value);
            row.setFocusTraversable(true);
            row.setOnMouseClicked(event -> choose(value));
            rows.add(row);
            list.getChildren().add(row);
        }
        optionRows = rows;
        return list;
    }

    private void choose(String value) {
        if (feedback != null) {
            return;
        }
        chosen = value;
        for (Node row : optionRows) {
            row.getStyleClass().remove("selected");
            if (value.equals(row.getUserData())) {
                row.getStyleClass().add("selected");
            }
        }
    }

    private Node typedAnswer(TextInputControl input, String prompt) {
        input.setPromptText(prompt);
        input.getStyleClass().add("study-input");
        if (input instanceof TextArea area) {
            area.setWrapText(true);
            area.setPrefRowCount(3);
        }
        typed = input;
        return input;
    }

    private Node flashcard(SessionItem item) {
        Label face = new Label("Think of the answer, then turn the card.");
        face.getStyleClass().addAll("text-muted", "flashcard-hint");
        face.setWrapText(true);
        StackPane card = new StackPane(face);
        card.getStyleClass().addAll("card", "flat", "flashcard");
        card.setMinHeight(200);
        Button turn = new Button("Show answer");
        turn.getStyleClass().addAll("primary", "large");
        HBox actions = new HBox(12, turn);
        actions.getStyleClass().add("study-actions");
        VBox box = new VBox(20, card, actions);
        flip = () -> {
            flip = null;
            Motion.flip(card, () -> {
                Label back = new Label(item.flashcardBack());
                back.getStyleClass().addAll("text-reading", "flashcard-back");
                back.setWrapText(true);
                card.getChildren().setAll(back);
            });
            actions.getChildren().setAll(ratingButtons(item));
        };
        turn.setOnAction(event -> flip.run());
        primaryAction = () -> {
            if (flip != null) {
                flip.run();
            }
        };
        return box;
    }

    private List<Node> ratingButtons(SessionItem item) {
        Label how = new Label("How well did you know it?");
        how.getStyleClass().add("text-caption");
        List<Node> nodes = new ArrayList<>();
        nodes.add(how);
        List<Runnable> actions = new ArrayList<>();
        Rating[] order = {Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY};
        String[] labels = {"Again", "Hard", "Good", "Easy"};
        for (int i = 0; i < order.length; i++) {
            Rating rating = order[i];
            Button button = new Button((i + 1) + "  " + labels[i]);
            button.getStyleClass().add("rating-button");
            if (rating == Rating.GOOD) {
                button.getStyleClass().add("primary");
            }
            Runnable rate = () -> rate(item, rating);
            button.setOnAction(event -> rate.run());
            actions.add(rate);
            nodes.add(button);
        }
        ratings = actions;
        primaryAction = () -> rate(item, Rating.GOOD);
        return nodes;
    }

    private void rate(SessionItem item, Rating rating) {
        if (feedback != null) {
            return;
        }
        long duration = Math.max(0, context.clock().millis() - shownAt);
        send(Response.flashcard(item.itemId(), rating, duration), item);
    }

    private void submit(SessionItem item) {
        if (feedback != null) {
            return;
        }
        String answer = typed != null ? typed.getText().strip() : chosen;
        if (answer == null || answer.isEmpty()) {
            for (Node row : optionRows) {
                Motion.shake(row.getParent());
                break;
            }
            if (typed != null) {
                Motion.shake(typed);
            }
            return;
        }
        Confidence level = confidence == null ? null : confidence.getConfidence();
        long duration = Math.max(0, context.clock().millis() - shownAt);
        send(Response.answer(item.itemId(), answer, level == null ? Confidence.FAIRLY_SURE : level, false, duration), item);
    }

    private void send(Response response, SessionItem item) {
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        primaryAction = null;
        disposer.add(UiTasks.run(() -> workspace.practice().submit(session.id(), response),
                result -> showFeedback(item, response, result), message -> {
                    primaryAction = () -> submit(item);
                    Toasts.error(root, message);
                }));
    }

    private void showFeedback(SessionItem item, Response response, Feedback result) {
        feedback = result;
        if (result.correct()) {
            correctCount++;
        }
        bar.setProgress((index + 1) / (double) session.items().size());
        for (Node row : optionRows) {
            Object value = row.getUserData();
            boolean picked = value.equals(response.answer());
            boolean right = item.type() == ItemType.TRUE_FALSE
                    ? value.equals(result.correctAnswer().toLowerCase(Locale.ROOT)) : value.equals(result.correctAnswer());
            row.getStyleClass().removeAll("selected");
            row.setDisable(false);
            if (picked && result.correct()) {
                row.getStyleClass().add("correct");
                drawCheck((StackPane) ((HBox) row).getChildren().get(2));
            } else if (picked) {
                row.getStyleClass().add("wrong");
                ((StackPane) ((HBox) row).getChildren().get(2)).getChildren().setAll(Icons.of(Feather.X, 18));
                Motion.shake(row);
            } else if (right) {
                row.getStyleClass().add("revealed");
                ((StackPane) ((HBox) row).getChildren().get(2)).getChildren().setAll(Icons.of(Feather.CHECK, 18));
            } else {
                row.getStyleClass().add("dimmed");
            }
        }
        if (typed != null) {
            typed.setEditable(false);
            typed.getStyleClass().add(result.correct() ? "correct" : "wrong");
            if (!result.correct()) {
                Motion.shake(typed);
            }
        }
        // Replace the actions row with the feedback panel.
        stage.getChildren().removeIf(node -> node.getStyleClass().contains("study-actions")
                || node instanceof VBox box && box.getChildren().stream().anyMatch(n -> n.getStyleClass().contains("study-actions")));
        if (item.type() == ItemType.FLASHCARD) {
            stage.getChildren().add(flashcardBack(item));
        }
        stage.getChildren().add(feedbackPanel(item, result));
        Fx.run(() -> {
            scroller.setVvalue(1);
            root.requestFocus();
        });
    }

    private Node flashcardBack(SessionItem item) {
        Label back = new Label(item.flashcardBack());
        back.getStyleClass().addAll("text-reading", "flashcard-back");
        back.setWrapText(true);
        StackPane card = new StackPane(back);
        card.getStyleClass().addAll("card", "flat", "flashcard");
        return card;
    }

    private void drawCheck(StackPane holder) {
        Polyline check = new Polyline(4, 12, 10, 18, 20, 6);
        check.getStyleClass().add("check-mark");
        holder.getChildren().setAll(check);
        Motion.drawIn(check, 30, Duration.millis(260));
    }

    private Node feedbackPanel(SessionItem item, Feedback result) {
        boolean flashcard = item.type() == ItemType.FLASHCARD;
        Label verdict = new Label(flashcard ? "Noted" : result.correct() ? "Correct" : "Not quite");
        verdict.getStyleClass().addAll("text-title", "feedback-verdict");
        VBox panel = new VBox(12, verdict);
        panel.getStyleClass().addAll("feedback-panel", flashcard ? "neutral" : result.correct() ? "correct" : "wrong");
        if (!result.correct() && !flashcard && item.type() != ItemType.MCQ && item.type() != ItemType.TRUE_FALSE) {
            Label answer = new Label("Answer: " + result.correctAnswer());
            answer.getStyleClass().addAll("medium");
            answer.setWrapText(true);
            panel.getChildren().add(answer);
        }
        if (result.graderFeedback() != null) {
            Label graded = new Label(result.graderFeedback() + " Graded by your study assistant.");
            graded.getStyleClass().add("text-muted");
            graded.setWrapText(true);
            panel.getChildren().add(graded);
        }
        if (result.explanation() != null && !result.explanation().isBlank()) {
            Label explanation = new Label(result.explanation());
            explanation.getStyleClass().add("text-reading");
            explanation.setWrapText(true);
            panel.getChildren().add(explanation);
        }
        HBox meta = new HBox(16);
        meta.setAlignment(Pos.CENTER_LEFT);
        SourceRef source = result.source();
        if (source != null) {
            Button from = new Button("From your material: " + source.documentTitle() + ", "
                    + Formats.pages(source.pageStart(), source.pageEnd()), Icons.of(Feather.BOOK_OPEN, 14));
            from.getStyleClass().addAll("quiet", "small", "source-link");
            Citation citation = new Citation(1, source.chunkId(), source.documentId(), source.documentTitle(),
                    source.pageStart(), source.pageEnd(), "");
            from.setOnAction(event -> openSource(citation));
            meta.getChildren().add(from);
        }
        Label next = new Label(Formats.nextReview(result.nextDueAt(), context.clock()) + ".");
        next.getStyleClass().add("text-caption");
        meta.getChildren().add(next);
        panel.getChildren().add(meta);

        boolean last = index + 1 >= session.items().size();
        Button go = new Button(last ? "See summary" : "Next question", Icons.of(Feather.ARROW_RIGHT, 16));
        go.getStyleClass().addAll("primary", "large");
        go.setContentDisplay(javafx.scene.control.ContentDisplay.RIGHT);
        go.setOnAction(event -> advance());
        primaryAction = this::advance;
        HBox actions = new HBox(go);
        actions.getStyleClass().add("feedback-actions");
        VBox wrapper = new VBox(20, panel, actions);
        Motion.fadeIn(wrapper, Motion.ROUTE);
        return wrapper;
    }

    /** Opens Ask on the cited page; a mixed session finds the course through the document. */
    private void openSource(Citation citation) {
        if (session.courseId() != null) {
            navigator.navigate(new Destination.Ask(session.courseId(), null, citation));
            return;
        }
        Workspace workspace = context.workspace();
        if (workspace == null) {
            return;
        }
        disposer.add(UiTasks.run(() -> workspace.documents().get(citation.documentId()).courseId(),
                courseId -> navigator.navigate(new Destination.Ask(courseId, null, citation)),
                message -> Toasts.error(root, message)));
    }

    private void advance() {
        if (index + 1 < session.items().size()) {
            index++;
            showItem();
        } else {
            finish();
        }
    }

    private void finish() {
        Workspace workspace = context.workspace();
        if (workspace == null || session == null) {
            navigator.navigate(Destination.HOME);
            return;
        }
        primaryAction = null;
        disposer.add(UiTasks.run(() -> workspace.practice().finish(session.id()), this::summary,
                message -> navigator.navigate(Destination.HOME)));
    }

    private void end() {
        if (session == null || session.items().isEmpty() || feedback == null && index == 0) {
            navigator.back();
            if (navigator.current() == null || navigator.current().route() == destination.route()) {
                navigator.navigate(Destination.HOME);
            }
            return;
        }
        finish();
    }

    private void summary(SessionSummary s) {
        bar.setProgress(1);
        counter.setText("");
        Label overline = new Label("SESSION COMPLETE");
        overline.getStyleClass().add("text-overline");
        Label title = new Label(encouragement(s));
        title.getStyleClass().addAll("text-display", "study-prompt");
        title.setWrapText(true);
        ProgressRing ring = new ProgressRing(120, 10);
        ring.setProgress(s.accuracy());
        Label percent = new Label(Formats.percent(s.accuracy()));
        percent.getStyleClass().addAll("text-numeral", "ring-numeral");
        Label caption = new Label("correct");
        caption.getStyleClass().add("text-caption");
        VBox center = new VBox(-4, percent, caption);
        center.setAlignment(Pos.CENTER);
        ring.setCenter(center);
        StatTile answered = new StatTile("Answered", s.answered()).framed(false);
        StatTile time = new StatTile("Time", Formats.duration(s.durationMs())).framed(false);
        StatTile learned = new StatTile("New questions", s.newItemsLearned()).framed(false);
        HBox stats = new HBox(40, ring, answered, time, learned);
        stats.setAlignment(Pos.CENTER_LEFT);
        stats.getStyleClass().addAll("card", "flat", "summary-card");
        List<Node> body = new ArrayList<>(List.of(new VBox(14, overline, title), stats));
        if (!s.improved().isEmpty()) {
            Label heading = new Label("What improved");
            heading.getStyleClass().add("text-heading");
            FlowPane chips = new FlowPane(8, 8);
            for (ConceptChange change : s.improved()) {
                chips.getChildren().add(new Chip(change.name() + "  " + Formats.percent(change.after()), Tone.SUCCESS,
                        Feather.TRENDING_UP));
            }
            body.add(new VBox(12, heading, chips));
        }
        if (s.nextReviewAt() != null) {
            Label next = new Label("Your next review is " + Formats.inTime(s.nextReviewAt(), context.clock()) + ".");
            next.getStyleClass().add("text-muted");
            body.add(next);
        }
        Button home = new Button("Back to Home");
        home.getStyleClass().addAll("primary", "large");
        home.setOnAction(event -> navigator.navigate(Destination.HOME));
        primaryAction = () -> navigator.navigate(Destination.HOME);
        Button more = new Button("Study more");
        more.getStyleClass().add("large");
        more.setOnAction(event -> navigator.replace(new Destination.Study(destination.request()), true));
        HBox actions = new HBox(12, home, more);
        body.add(actions);
        stage.getChildren().setAll(body);
        Motion.enter(stage);
        ring.setProgressNow(0);
        ring.setProgress(s.accuracy());
    }

    static String encouragement(SessionSummary s) {
        if (s.answered() == 0) {
            return "Come back when you're ready";
        }
        if (s.accuracy() >= 0.85) {
            return "Excellent work";
        }
        if (s.accuracy() >= 0.6) {
            return "Good session";
        }
        return "Every review counts";
    }

    private void key(KeyEvent event) {
        boolean inText = event.getTarget() instanceof TextInputControl;
        KeyCode code = event.getCode();
        if (code == KeyCode.ESCAPE) {
            event.consume();
            end();
        } else if (code == KeyCode.ENTER && !(inText && event.isShiftDown())) {
            if (primaryAction != null) {
                event.consume();
                primaryAction.run();
            }
        } else if (!inText && code == KeyCode.SPACE && flip != null) {
            event.consume();
            flip.run();
        } else if (!inText && code.isDigitKey()) {
            int n = Character.getNumericValue(event.getText().isEmpty() ? '0' : event.getText().charAt(0));
            if (!ratings.isEmpty() && n >= 1 && n <= ratings.size()) {
                event.consume();
                ratings.get(n - 1).run();
            } else if (n >= 1 && n <= optionRows.size()) {
                event.consume();
                choose((String) optionRows.get(n - 1).getUserData());
            }
        }
    }
}
