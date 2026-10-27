package edu.learnsphere.app.ui.study;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.SessionItem;
import java.util.ArrayList;
import java.util.List;
import javafx.scene.Node;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import org.kordamp.ikonli.feather.Feather;

/**
 * Fill in the blank: the sentence in large type with a text field where the blank is, so the learner writes the
 * missing words in place. The study service grades tolerantly (case, accents, a small typo in a long word). A
 * wrong answer stays in the field, marked, with the right words set beside it.
 */
final class ClozeQuestion extends QuestionView {

    /** Narrowest and widest the blank grows to as the learner types. */
    static final double MIN_FIELD_WIDTH = 140;
    static final double MAX_FIELD_WIDTH = 460;

    private final TextField field = new TextField();
    private final TextFlow sentence = new TextFlow();

    ClozeQuestion(SessionItem item) {
        super(item);
        field.getStyleClass().addAll("serif-medium", "study-input", "cloze-field");
        field.setPromptText("missing words");
        field.setAccessibleText("The missing words");
        field.setPrefColumnCount(8);
        field.textProperty().addListener((obs, old, text) -> {
            resize();
            changed();
        });
        field.fontProperty().addListener((obs, old, font) -> resize());
        resize();

        sentence.getStyleClass().addAll("study-prompt", "cloze-sentence");
        sentence.getChildren().setAll(parts(item.prompt()));
        sentence.setAccessibleText(item.prompt().replace(ItemType.CLOZE_BLANK, "blank"));
    }

    /** The sentence with the field in place of the first blank (after the sentence when it has none). */
    private List<Node> parts(String prompt) {
        List<Node> nodes = new ArrayList<>();
        int blank = prompt.indexOf(ItemType.CLOZE_BLANK);
        if (blank < 0) {
            nodes.add(text(prompt.strip() + " "));
            nodes.add(field);
            return nodes;
        }
        int end = blank + ItemType.CLOZE_BLANK.length();
        // Blanks written with more underscores than the marker are one blank.
        while (end < prompt.length() && prompt.charAt(end) == '_') {
            end++;
        }
        if (blank > 0) {
            nodes.add(text(prompt.substring(0, blank)));
        }
        nodes.add(field);
        String after = prompt.substring(end).replace(ItemType.CLOZE_BLANK, "_____");
        if (!after.isEmpty()) {
            nodes.add(text(after));
        }
        return nodes;
    }

    private static Text text(String value) {
        Text text = new Text(value);
        text.getStyleClass().addAll("serif-medium", "cloze-text");
        return text;
    }

    /** Fits the field to what is typed, between the narrowest and widest blank. */
    private void resize() {
        String shown = field.getText().isEmpty() ? field.getPromptText() : field.getText();
        Text probe = new Text(shown);
        probe.setFont(field.getFont());
        double width = probe.getLayoutBounds().getWidth() + 28;
        field.setPrefWidth(Math.max(MIN_FIELD_WIDTH, Math.min(MAX_FIELD_WIDTH, width)));
    }

    @Override
    Node prompt() {
        return sentence;
    }

    @Override
    Node body() {
        return null;
    }

    @Override
    boolean hasAnswer() {
        return !field.getText().isBlank();
    }

    @Override
    String answer() {
        return field.getText().strip();
    }

    /** The field the learner types in (tests). */
    TextField field() {
        return field;
    }

    @Override
    void setLocked(boolean locked) {
        field.setEditable(!locked);
    }

    @Override
    void showResult(Feedback feedback) {
        setLocked(true);
        field.setFocusTraversable(false);
        if (feedback.correct()) {
            field.getStyleClass().add("correct");
            return;
        }
        field.getStyleClass().add("wrong");
        Motion.shake(field);
        Label right = new Label(feedback.correctAnswer().strip(), Icons.of(Feather.CHECK, 18));
        right.getStyleClass().addAll("serif-medium", "cloze-answer");
        right.setContentDisplay(ContentDisplay.RIGHT);
        right.setAccessibleText("The missing words are " + feedback.correctAnswer().strip());
        int at = sentence.getChildren().indexOf(field);
        sentence.getChildren().add(at + 1, right);
        Motion.fadeIn(right, Motion.ROUTE);
    }

    @Override
    Node nudgeTarget() {
        return field;
    }

    @Override
    Node initialFocus() {
        return field;
    }
}
