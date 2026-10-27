package edu.learnsphere.app.ui.study;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.Hint;
import edu.learnsphere.core.service.SessionItem;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.kordamp.ikonli.feather.Feather;

/**
 * Multiple choice as big rows numbered 1 to n, and true/false as two tiles keyed T and F. Number keys (and T
 * and F) choose; a hint rules options out; after checking, the chosen option turns green with a check drawn
 * in, or red with a single shake while the right one is revealed.
 */
final class ChoiceQuestion extends QuestionView {

    private static final PseudoClass CHOSEN = PseudoClass.getPseudoClass("chosen");

    private final boolean trueFalse;
    private final List<Option> options = new ArrayList<>();
    private final Pane body;
    private Option chosen;
    private boolean locked;

    ChoiceQuestion(SessionItem item) {
        super(item);
        trueFalse = item.type() == ItemType.TRUE_FALSE;
        List<String> choices = item.choices().isEmpty() && trueFalse ? List.of("true", "false") : item.choices();
        for (int i = 0; i < choices.size(); i++) {
            String value = choices.get(i);
            String key = trueFalse ? value.strip().substring(0, 1).toUpperCase(Locale.ROOT) : Integer.toString(i + 1);
            options.add(new Option(value, trueFalse ? StudyText.answerText(ItemType.TRUE_FALSE, value) : value, key));
        }
        if (trueFalse) {
            // A grid of equal columns, so both tiles are the same width whatever their words.
            GridPane tiles = new GridPane();
            tiles.setHgap(16);
            tiles.getStyleClass().add("study-tiles");
            for (int i = 0; i < options.size(); i++) {
                Option option = options.get(i);
                option.getStyleClass().add("tile");
                option.setMaxWidth(Double.MAX_VALUE);
                ColumnConstraints column = new ColumnConstraints();
                column.setPercentWidth(100.0 / options.size());
                column.setFillWidth(true);
                tiles.getColumnConstraints().add(column);
                tiles.add(option, i, 0);
            }
            body = tiles;
        } else {
            VBox rows = new VBox(12);
            rows.getStyleClass().add("study-options");
            rows.getChildren().addAll(options);
            body = rows;
        }
    }

    @Override
    Node body() {
        return body;
    }

    @Override
    boolean hasAnswer() {
        return chosen != null;
    }

    @Override
    String answer() {
        return chosen == null ? "" : trueFalse ? chosen.value.strip().toLowerCase(Locale.ROOT) : chosen.value;
    }

    /** The option rows in display order (tests). */
    List<? extends Region> options() {
        return options;
    }

    /** Chooses the option at {@code index} (0-based); ignored when it is ruled out or input is locked. */
    void choose(int index) {
        if (index >= 0 && index < options.size()) {
            choose(options.get(index));
        }
    }

    private void choose(Option option) {
        if (locked || option.ruledOut) {
            return;
        }
        if (chosen != null) {
            chosen.pseudoClassStateChanged(CHOSEN, false);
        }
        chosen = option;
        option.pseudoClassStateChanged(CHOSEN, true);
        option.setAccessibleText(option.text + ", chosen");
        changed();
    }

    @Override
    boolean key(KeyEvent event) {
        if (locked || event.isShortcutDown() || event.isAltDown()) {
            return false;
        }
        KeyCode code = event.getCode();
        if (trueFalse && (code == KeyCode.T || code == KeyCode.F)) {
            for (Option option : options) {
                if (option.key.equalsIgnoreCase(code.getName())) {
                    choose(option);
                    return true;
                }
            }
            return false;
        }
        int n = StudyParts.digit(event);
        if (n >= 1 && n <= options.size()) {
            choose(n - 1);
            return true;
        }
        if (code == KeyCode.SPACE && event.getTarget() instanceof Option option) {
            choose(option);
            return true;
        }
        return false;
    }

    @Override
    void applyHint(Hint hint) {
        for (Option option : options) {
            if (hint.removedChoices().stream().anyMatch(removed -> same(removed, option.value))) {
                option.ruleOut();
                if (option == chosen) {
                    chosen.pseudoClassStateChanged(CHOSEN, false);
                    chosen = null;
                    changed();
                }
            }
        }
    }

    @Override
    void setLocked(boolean value) {
        locked = value;
        options.forEach(option -> option.setFocusTraversable(!value && !option.ruledOut));
    }

    @Override
    void showResult(Feedback feedback) {
        setLocked(true);
        for (Option option : options) {
            boolean picked = option == chosen;
            boolean right = trueFalse
                    ? option.value.strip().equalsIgnoreCase(feedback.correctAnswer().strip())
                    : same(option.value, feedback.correctAnswer());
            option.pseudoClassStateChanged(CHOSEN, false);
            if (picked && right) {
                option.mark("correct", StudyParts.drawnCheck(22));
                option.setAccessibleText(option.text + ", your answer, correct");
            } else if (picked) {
                option.mark("wrong", Icons.of(Feather.X, 18));
                option.setAccessibleText(option.text + ", your answer, wrong");
                Motion.shake(option);
            } else if (right) {
                option.mark("revealed", Icons.of(Feather.CHECK, 18));
                option.setAccessibleText(option.text + ", the right answer");
            } else {
                option.getStyleClass().add("dimmed");
            }
        }
    }

    @Override
    Node nudgeTarget() {
        return body;
    }

    private static boolean same(String a, String b) {
        return squash(a).equals(squash(b));
    }

    private static String squash(String text) {
        return text == null ? "" : text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** One option: its key, its text and a mark that shows the result. */
    private final class Option extends HBox {

        private final String value;
        private final String text;
        private final String key;
        private final StackPane mark = new StackPane();
        private boolean ruledOut;

        Option(String value, String text, String key) {
            this.value = value;
            this.text = text;
            this.key = key;
            getStyleClass().add("study-option");
            setAlignment(Pos.CENTER_LEFT);
            setSpacing(16);
            Label cap = new Label(key);
            cap.getStyleClass().add("option-key");
            cap.setMinWidth(Region.USE_PREF_SIZE);
            Label label = new Label(text);
            label.getStyleClass().add("option-text");
            label.setWrapText(true);
            label.setMaxWidth(Double.MAX_VALUE);
            label.setMinHeight(Region.USE_PREF_SIZE);
            HBox.setHgrow(label, Priority.ALWAYS);
            mark.getStyleClass().add("option-mark");
            mark.setMinSize(24, 24);
            mark.setMaxSize(24, 24);
            getChildren().addAll(cap, label, mark);
            setFocusTraversable(true);
            setAccessibleRole(AccessibleRole.RADIO_BUTTON);
            setAccessibleText(text);
            setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY) {
                    requestFocus();
                    choose(this);
                }
            });
        }

        void ruleOut() {
            ruledOut = true;
            getStyleClass().add("ruled-out");
            setFocusTraversable(false);
            setAccessibleText(text + ", ruled out by the hint");
        }

        void mark(String state, Node graphic) {
            getStyleClass().add(state);
            mark.getChildren().setAll(graphic);
        }
    }
}
