package edu.learnsphere.app.ui.study;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.SessionItem;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

/**
 * A flashcard: the front set large on a card; Space (or "Show answer") turns it with the horizontal
 * scale-through while the back fades in; then the learner rates how well they remembered it with Again, Hard,
 * Good or Easy (keys 1 to 4), each button saying when the card would come back. FX thread only.
 */
final class FlashcardView {

    private static final Rating[] ORDER = {Rating.AGAIN, Rating.HARD, Rating.GOOD, Rating.EASY};

    private final SessionItem item;
    private final StackPane card = new StackPane();
    private final StackPane body = new StackPane();
    private final HBox header = new HBox(10);
    private final Button turnButton = new Button("Show answer");
    private final HBox turnRow;
    private final VBox ratingBox;
    private final Map<Rating, Button> buttons = new EnumMap<>(Rating.class);
    private final Map<Rating, Label> intervals = new EnumMap<>(Rating.class);
    private final VBox root;
    private Consumer<Rating> onRate = rating -> { };
    private Runnable onTurn = () -> { };
    private boolean turned;
    private boolean locked;

    FlashcardView(SessionItem item) {
        this.item = Objects.requireNonNull(item, "item");
        card.getStyleClass().add("flashcard");
        card.setFocusTraversable(false);
        header.getStyleClass().add("flashcard-header");
        header.setAlignment(Pos.CENTER_LEFT);
        StackPane.setAlignment(header, Pos.TOP_LEFT);
        body.getStyleClass().add("flashcard-body");
        body.getChildren().setAll(front());
        StackPane.setMargin(body, new Insets(54, 0, 0, 0));
        card.getChildren().setAll(body, header);
        card.setAccessibleText("Card front: " + item.prompt());

        turnButton.getStyleClass().addAll("primary", "large");
        turnButton.setOnAction(event -> turn());
        turnRow = new HBox(12, turnButton, StudyParts.key("Space"));
        turnRow.setAlignment(Pos.CENTER_LEFT);
        turnRow.getStyleClass().add("study-actions");

        Label how = new Label("How well did you remember it?");
        how.getStyleClass().addAll("text-heading", "rating-question");
        HBox row = new HBox(12);
        row.getStyleClass().add("rating-row");
        for (int i = 0; i < ORDER.length; i++) {
            Rating rating = ORDER[i];
            Button button = ratingButton(rating, i + 1);
            HBox.setHgrow(button, Priority.ALWAYS);
            buttons.put(rating, button);
            row.getChildren().add(button);
        }
        ratingBox = new VBox(14, how, row);
        ratingBox.getStyleClass().add("rating-box");
        ratingBox.setManaged(false);
        ratingBox.setVisible(false);

        root = new VBox(28, card, turnRow, ratingBox);
        root.getStyleClass().add("flashcard-view");
    }

    private Node front() {
        Label front = new Label(item.prompt());
        front.getStyleClass().addAll("text-display", "study-prompt", "flashcard-front");
        front.setWrapText(true);
        front.setTextAlignment(TextAlignment.CENTER);
        front.setMinHeight(Region.USE_PREF_SIZE);
        Label nudge = new Label("Think of the answer, then turn the card.");
        nudge.getStyleClass().addAll("text-caption", "flashcard-nudge");
        VBox face = new VBox(20, front, nudge);
        face.setAlignment(Pos.CENTER);
        face.getStyleClass().add("flashcard-face");
        return face;
    }

    private Node back() {
        Label front = new Label(item.prompt());
        front.getStyleClass().addAll("serif-medium", "flashcard-front-small");
        front.setWrapText(true);
        front.setTextAlignment(TextAlignment.CENTER);
        Region rule = new Region();
        rule.getStyleClass().add("flashcard-rule");
        Label back = new Label(item.flashcardBack() == null ? "" : item.flashcardBack());
        back.getStyleClass().addAll("text-reading", "flashcard-back");
        back.setWrapText(true);
        back.setTextAlignment(TextAlignment.CENTER);
        back.setMinHeight(Region.USE_PREF_SIZE);
        VBox face = new VBox(18, front, rule, back);
        face.setAlignment(Pos.CENTER);
        face.getStyleClass().addAll("flashcard-face", "back");
        return face;
    }

    private Button ratingButton(Rating rating, int number) {
        Label name = new Label(StudyText.ratingLabel(rating));
        name.getStyleClass().addAll("semibold", "rating-name");
        Label when = new Label(" ");
        when.getStyleClass().add("rating-interval");
        intervals.put(rating, when);
        HBox top = new HBox(8, StudyParts.key(Integer.toString(number)), name);
        top.setAlignment(Pos.CENTER_LEFT);
        VBox graphic = new VBox(4, top, when);
        graphic.setAlignment(Pos.CENTER_LEFT);
        Button button = new Button(null, graphic);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.getStyleClass().addAll("rating-button", "rating-" + name.getText().toLowerCase(java.util.Locale.ROOT));
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAccessibleText(StudyText.ratingLabel(rating));
        button.setOnAction(event -> rate(rating));
        return button;
    }

    Node root() {
        return root;
    }

    SessionItem item() {
        return item;
    }

    /** Puts {@code node} on the card's header line (what the card is about). */
    void setHeader(Node node) {
        header.getChildren().setAll(node);
    }

    boolean isTurned() {
        return turned;
    }

    /** What "Show answer" focuses on when the card appears. */
    Node initialFocus() {
        return turnButton;
    }

    void setOnRate(Consumer<Rating> action) {
        onRate = Objects.requireNonNull(action, "action");
    }

    void setOnTurn(Runnable action) {
        onTurn = Objects.requireNonNull(action, "action");
    }

    /** Turns the card to its back and offers the ratings. */
    void turn() {
        if (turned) {
            return;
        }
        turned = true;
        Motion.flip(card, () -> body.getChildren().setAll(back()));
        card.setAccessibleText("Card back: " + item.flashcardBack());
        card.getStyleClass().add("turned");
        turnRow.setManaged(false);
        turnRow.setVisible(false);
        ratingBox.setManaged(true);
        ratingBox.setVisible(true);
        Motion.fadeIn(ratingBox, Motion.FLIP);
        buttons.get(Rating.GOOD).requestFocus();
        onTurn.run();
    }

    /**
     * Shows when the card would come back for each rating.
     *
     * @param due the due time per rating; ratings without one show nothing
     */
    void setNextReviews(Map<Rating, Long> due, long now) {
        for (Rating rating : ORDER) {
            Long at = due.get(rating);
            Label label = intervals.get(rating);
            label.setText(at == null ? " " : StudyText.interval(now, at));
            buttons.get(rating).setAccessibleText(StudyText.ratingLabel(rating)
                    + (at == null ? "" : ", again in " + StudyText.interval(now, at)));
        }
    }

    /** The interval shown on a rating's button (tests). */
    String intervalText(Rating rating) {
        return intervals.get(rating).getText();
    }

    /** Space turns the card; 1 to 4 rate it once turned. */
    boolean key(KeyEvent event) {
        if (locked || event.isShortcutDown() || event.isAltDown()) {
            return false;
        }
        if (!turned && event.getCode() == KeyCode.SPACE) {
            turn();
            return true;
        }
        int n = StudyParts.digit(event);
        if (turned && n >= 1 && n <= ORDER.length) {
            rate(ORDER[n - 1]);
            return true;
        }
        return false;
    }

    private void rate(Rating rating) {
        if (!turned || locked) {
            return;
        }
        onRate.accept(rating);
    }

    /** Stops or allows rating (while the rating is saved). */
    void setLocked(boolean value) {
        locked = value;
        buttons.values().forEach(button -> button.setDisable(value));
    }

    /** Marks the rating given and leaves the others quiet. */
    void showResult(Feedback feedback) {
        setLocked(true);
        buttons.forEach((rating, button) -> {
            button.setDisable(false);
            button.setMouseTransparent(true);
            button.setFocusTraversable(false);
            button.getStyleClass().add(rating == feedback.rating() ? "rated" : "dimmed");
        });
    }
}
