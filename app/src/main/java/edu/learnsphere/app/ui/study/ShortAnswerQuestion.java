package edu.learnsphere.app.ui.study;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.SessionItem;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;

/**
 * A short written answer, checked by the study assistant against the material. Enter checks it and Shift+Enter
 * starts a new line. The answer stays on screen after checking, tinted by how it was graded.
 */
final class ShortAnswerQuestion extends QuestionView {

    private final TextArea area = new TextArea();
    private final VBox body;

    ShortAnswerQuestion(SessionItem item) {
        super(item);
        area.getStyleClass().addAll("serif", "study-input", "short-answer-input");
        area.setPromptText("Write your answer in a sentence or two");
        area.setAccessibleText("Your answer");
        area.setWrapText(true);
        area.setPrefRowCount(4);
        area.textProperty().addListener((obs, old, text) -> changed());
        Label help = new Label("Your study assistant checks it against your material.");
        help.getStyleClass().addAll("text-caption", "study-help");
        help.setWrapText(true);
        body = new VBox(10, area, help);
        body.getStyleClass().add("short-answer");
    }

    @Override
    Node body() {
        return body;
    }

    @Override
    boolean hasAnswer() {
        return !area.getText().isBlank();
    }

    @Override
    String answer() {
        return area.getText().strip();
    }

    /** The text area (tests). */
    TextArea area() {
        return area;
    }

    /** Starts a new line where the caret is (Shift+Enter). */
    void newLine() {
        if (area.isEditable()) {
            area.replaceSelection("\n");
        }
    }

    @Override
    void setLocked(boolean locked) {
        area.setEditable(!locked);
    }

    @Override
    void showResult(Feedback feedback) {
        setLocked(true);
        area.getStyleClass().removeAll("correct", "partial", "wrong");
        area.getStyleClass().add(feedback.disputed() ? "correct"
                : feedback.rating() == Rating.AGAIN ? "wrong"
                : feedback.rating() == Rating.HARD ? "partial" : "correct");
        if (feedback.rating() == Rating.AGAIN && !feedback.disputed()) {
            Motion.shake(area);
        }
        body.getChildren().removeIf(node -> node.getStyleClass().contains("study-help"));
    }

    @Override
    Node nudgeTarget() {
        return area;
    }

    @Override
    Node initialFocus() {
        return area;
    }
}
