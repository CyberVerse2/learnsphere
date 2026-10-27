package edu.learnsphere.app.ui.study;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Formats;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.SessionItem;
import edu.learnsphere.core.service.SourceRef;
import java.time.Clock;
import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * What the learner sees after answering: the verdict (with the check drawn in when right), for short answers
 * the study assistant's grade with its feedback, the points worth including and a way to dispute it, then the
 * explanation set as a note in the margin of the material, a "From your material" link to the page it comes
 * from, when the question comes back in plain words, and Continue. FX thread only.
 */
final class FeedbackView {

    /** What the feedback's buttons do. */
    interface Actions {
        /** Opens the page the question comes from. */
        void openSource(SourceRef source);

        /** Counts a short answer the study assistant marked down as correct. */
        void dispute(Feedback feedback);

        /** Moves on to the next card (or the summary). */
        void next();
    }

    private final SessionItem item;
    private final Clock clock;
    private final Actions actions;
    private final VBox panel = new VBox();
    private final VBox root;
    private final Button continueButton;
    private Feedback feedback;
    private boolean comesBack;
    private Button disputeButton;
    private Label disputeError;

    /**
     * @param comesBack true when the item was queued to come back later in this session
     * @param last      true on the session's last card ("See summary" instead of "Continue")
     */
    FeedbackView(SessionItem item, Feedback feedback, boolean comesBack, boolean last, Clock clock, Actions actions) {
        this.item = Objects.requireNonNull(item, "item");
        this.feedback = Objects.requireNonNull(feedback, "feedback");
        this.comesBack = comesBack;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.actions = Objects.requireNonNull(actions, "actions");
        panel.getStyleClass().addAll("feedback", "feedback-panel");
        continueButton = new Button(last ? "See summary" : "Continue", Icons.of(Feather.ARROW_RIGHT, 16));
        continueButton.getStyleClass().addAll("primary", "large", "continue-button");
        continueButton.setContentDisplay(ContentDisplay.RIGHT);
        continueButton.setOnAction(event -> actions.next());
        HBox actionRow = new HBox(12, continueButton, StudyParts.key("Enter"));
        actionRow.setAlignment(Pos.CENTER_LEFT);
        actionRow.getStyleClass().add("feedback-actions");
        root = new VBox(24, panel, actionRow);
        root.getStyleClass().add("feedback-view");
        build(true);
    }

    Node root() {
        return root;
    }

    Feedback feedback() {
        return feedback;
    }

    Button continueButton() {
        return continueButton;
    }

    /** The dispute button, or null when the grade cannot be disputed (tests). */
    Button disputeButton() {
        return disputeButton;
    }

    /** Shows the grade after a dispute. */
    void update(Feedback disputed, boolean nowComesBack) {
        feedback = Objects.requireNonNull(disputed, "disputed");
        comesBack = nowComesBack;
        build(false);
    }

    /** Says "See summary" on the session's last card, "Continue" otherwise. */
    void setLast(boolean value) {
        continueButton.setText(value ? "See summary" : "Continue");
    }

    /** Shows that the dispute is being saved. */
    void setDisputing(boolean busy) {
        if (disputeButton != null) {
            disputeButton.setDisable(busy);
            disputeButton.setGraphic(busy ? new ActivityIndicator(14) : Icons.of(Feather.FLAG, 14));
        }
    }

    /** Shows why the dispute could not be saved. */
    void showDisputeError(UserMessage message) {
        setDisputing(false);
        if (disputeError != null) {
            disputeError.setText(message.text());
            disputeError.setManaged(true);
            disputeError.setVisible(true);
        }
    }

    private void build(boolean animate) {
        Tone tone = tone();
        panel.getStyleClass().removeAll("correct", "partial", "wrong", "neutral");
        panel.getStyleClass().add(tone.styleClass);
        panel.getChildren().clear();
        disputeButton = null;
        disputeError = null;

        VBox head = new VBox(10);
        if (feedback.gradedBy() == GradedBy.AI) {
            Label graded = new Label("Graded by your study assistant", Icons.of(Feather.FEATHER, 13));
            graded.getStyleClass().addAll("medium", "graded-by");
            head.getChildren().add(graded);
        }
        Node mark = switch (tone) {
            case CORRECT -> animate ? StudyParts.drawnCheck(28) : staticMark(Feather.CHECK);
            case WRONG -> staticMark(Feather.X);
            case PARTIAL -> staticMark(Feather.MINUS);
            case NEUTRAL -> staticMark(Feather.LAYERS);
        };
        mark.getStyleClass().add("verdict-mark");
        Label verdict = new Label(StudyText.verdict(item.type(), feedback));
        verdict.getStyleClass().addAll("text-title", "verdict");
        HBox verdictRow = new HBox(12, mark, verdict);
        verdictRow.setAlignment(Pos.CENTER_LEFT);
        if (feedback.gradedBy() == GradedBy.AI && !feedback.disputed()) {
            Label score = new Label(Formats.percent(feedback.score()));
            score.getStyleClass().addAll("medium", "score");
            score.setAccessibleText("Score " + Formats.percent(feedback.score()));
            verdictRow.getChildren().add(score);
        }
        head.getChildren().add(verdictRow);
        panel.getChildren().add(head);

        if (feedback.gradedBy() == GradedBy.AI) {
            addGrade();
        }
        if (!feedback.explanation().isBlank()) {
            Label explanation = new Label(feedback.explanation());
            explanation.getStyleClass().addAll("text-reading", "explanation");
            explanation.setWrapText(true);
            explanation.setMinHeight(Region.USE_PREF_SIZE);
            HBox.setHgrow(explanation, Priority.ALWAYS);
            Region rule = new Region();
            rule.getStyleClass().add("gloss-rule");
            HBox gloss = new HBox(16, rule, explanation);
            gloss.getStyleClass().add("gloss");
            panel.getChildren().add(gloss);
        }

        VBox meta = new VBox(6);
        meta.getStyleClass().add("feedback-meta");
        SourceRef source = feedback.source();
        if (source != null) {
            String where = (source.documentTitle().isBlank() ? "" : source.documentTitle() + ", ")
                    + Formats.pages(source.pageStart(), source.pageEnd());
            Button link = new Button("From your material  ·  " + where, Icons.of(Feather.BOOK_OPEN, 14));
            link.getStyleClass().addAll("quiet", "small", "source-link");
            link.setMnemonicParsing(false);
            link.setAccessibleText("From your material: open " + where);
            link.setOnAction(event -> actions.openSource(source));
            meta.getChildren().add(link);
        }
        Label next = new Label(StudyText.comesBack(feedback.nextDueAt(), comesBack, clock),
                Icons.of(comesBack ? Feather.ROTATE_CCW : Feather.CALENDAR, 14));
        next.getStyleClass().add("next-review");
        next.setWrapText(true);
        meta.getChildren().add(next);
        panel.getChildren().add(meta);

        if (feedback.disputable() && feedback.rating().value() <= Rating.HARD.value()) {
            disputeButton = new Button("Dispute grade", Icons.of(Feather.FLAG, 14));
            disputeButton.getStyleClass().addAll("small", "dispute-button");
            disputeButton.setOnAction(event -> actions.dispute(feedback));
            Label why = new Label("Think your answer was right? Disputing counts it as correct.");
            why.getStyleClass().add("text-caption");
            why.setWrapText(true);
            HBox.setHgrow(why, Priority.ALWAYS);
            HBox row = new HBox(14, disputeButton, why);
            row.setAlignment(Pos.CENTER_LEFT);
            disputeError = new Label();
            disputeError.getStyleClass().addAll("text-caption", "text-danger");
            disputeError.setManaged(false);
            disputeError.setVisible(false);
            VBox dispute = new VBox(8, row, disputeError);
            dispute.getStyleClass().add("dispute");
            panel.getChildren().add(dispute);
        }
        if (animate) {
            Motion.enter(root);
        }
    }

    /** The grade of a short answer: feedback, missing points and the reference answer. */
    private void addGrade() {
        if (feedback.graderFeedback() != null && !feedback.graderFeedback().isBlank()) {
            Label said = new Label(feedback.graderFeedback());
            said.getStyleClass().addAll("serif-italic", "grader-feedback");
            said.setWrapText(true);
            said.setMinHeight(Region.USE_PREF_SIZE);
            panel.getChildren().add(said);
        }
        if (!feedback.missingPoints().isEmpty() && !feedback.disputed()) {
            VBox points = new VBox(6);
            Label title = new Label("Worth including");
            title.getStyleClass().addAll("semibold", "block-title");
            points.getChildren().add(title);
            for (String point : feedback.missingPoints()) {
                Label dot = new Label("•");
                dot.getStyleClass().add("missing-dot");
                Label text = new Label(point);
                text.getStyleClass().add("missing-point");
                text.setWrapText(true);
                HBox row = new HBox(10, dot, text);
                row.setAlignment(Pos.TOP_LEFT);
                points.getChildren().add(row);
            }
            points.getStyleClass().add("missing-points");
            panel.getChildren().add(points);
        }
        if (!feedback.correctAnswer().isBlank()) {
            panel.getChildren().add(answerBlock("A complete answer", feedback.correctAnswer()));
        }
    }

    private static Node answerBlock(String title, String answer) {
        Label heading = new Label(title);
        heading.getStyleClass().addAll("semibold", "block-title");
        Label text = new Label(answer.strip());
        text.getStyleClass().addAll("text-reading", "reference-answer");
        text.setWrapText(true);
        text.setMinHeight(Region.USE_PREF_SIZE);
        VBox block = new VBox(6, heading, text);
        block.getStyleClass().add("answer-block");
        return block;
    }

    private static Node staticMark(Feather icon) {
        Label mark = new Label(null, Icons.of(icon, 18));
        mark.getStyleClass().add("verdict-icon");
        mark.setMinSize(28, 28);
        mark.setPrefSize(28, 28);
        mark.setMaxSize(28, 28);
        mark.setAlignment(Pos.CENTER);
        return mark;
    }

    private Tone tone() {
        if (item.type() == ItemType.FLASHCARD) {
            return Tone.NEUTRAL;
        }
        if (feedback.disputed()) {
            return Tone.CORRECT;
        }
        if (feedback.gradedBy() == GradedBy.AI) {
            return feedback.rating() == Rating.AGAIN ? Tone.WRONG
                    : feedback.rating() == Rating.HARD ? Tone.PARTIAL : Tone.CORRECT;
        }
        return feedback.correct() ? Tone.CORRECT : Tone.WRONG;
    }

    /** The colour of the verdict. */
    private enum Tone {
        CORRECT("correct"),
        PARTIAL("partial"),
        WRONG("wrong"),
        NEUTRAL("neutral");

        private final String styleClass;

        Tone(String styleClass) {
            this.styleClass = styleClass;
        }
    }
}
