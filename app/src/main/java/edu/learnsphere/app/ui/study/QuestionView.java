package edu.learnsphere.app.ui.study;

import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.Hint;
import edu.learnsphere.core.service.SessionItem;
import java.util.Objects;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;

/**
 * The answer area of one question the learner checks with "Check answer": multiple choice and true/false
 * ({@link ChoiceQuestion}), fill in the blank ({@link ClozeQuestion}) and short answers
 * ({@link ShortAnswerQuestion}). The study page puts the prompt, the body, the confidence chips and the actions
 * around it, and calls back into it for keys, hints and the result. Flashcards work differently (turn, then
 * rate) and have their own {@link FlashcardView}. FX thread only.
 */
abstract sealed class QuestionView permits ChoiceQuestion, ClozeQuestion, ShortAnswerQuestion {

    protected final SessionItem item;
    private Runnable onChange = () -> { };

    QuestionView(SessionItem item) {
        this.item = Objects.requireNonNull(item, "item");
    }

    /** The item for {@code item}'s type. */
    static QuestionView of(SessionItem item) {
        return switch (item.type()) {
            case MCQ, TRUE_FALSE -> new ChoiceQuestion(item);
            case CLOZE -> new ClozeQuestion(item);
            case SHORT_ANSWER -> new ShortAnswerQuestion(item);
            case FLASHCARD -> throw new IllegalArgumentException("Flashcards have their own view");
        };
    }

    SessionItem item() {
        return item;
    }

    /** The question in large type. */
    Node prompt() {
        Label prompt = new Label(item.prompt());
        prompt.getStyleClass().addAll("text-display", "study-prompt");
        prompt.setWrapText(true);
        prompt.setMinHeight(Label.USE_PREF_SIZE);
        return prompt;
    }

    /** Where the learner answers, below the prompt; null when the prompt holds the answer field. */
    abstract Node body();

    /** True when there is something to check. */
    abstract boolean hasAnswer();

    /** The answer to send. */
    abstract String answer();

    /** True for questions that ask how sure the learner is before checking (objective questions). */
    boolean asksConfidence() {
        return item.type() != ItemType.SHORT_ANSWER;
    }

    /** Handles a key pressed outside a text field (number keys, T and F); true when it was used. */
    boolean key(KeyEvent event) {
        return false;
    }

    /** Shows what a hint changes in the answer area (options it rules out). */
    void applyHint(Hint hint) {
    }

    /** Stops or allows input (while the answer is being checked, and for good after the result). */
    abstract void setLocked(boolean locked);

    /** Shows the result in the answer area: right and wrong choices, the missing word. */
    abstract void showResult(Feedback feedback);

    /** What shakes when "Check answer" is pressed with nothing to check. */
    abstract Node nudgeTarget();

    /** Where keyboard focus goes when the question appears, or null for the page itself. */
    Node initialFocus() {
        return null;
    }

    /** Called whenever the answer changes, so the page can enable "Check answer". */
    void setOnChange(Runnable action) {
        onChange = Objects.requireNonNull(action, "action");
    }

    protected void changed() {
        onChange.run();
    }
}
