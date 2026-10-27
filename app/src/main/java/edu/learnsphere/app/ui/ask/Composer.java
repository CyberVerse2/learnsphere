package edu.learnsphere.app.ui.ask;

import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.core.service.AssistantStatus;
import java.util.Objects;
import java.util.function.Consumer;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import org.kordamp.ikonli.feather.Feather;

/**
 * Where the learner writes a question: a box that grows with the question (up to {@value #MAX_ROWS} lines),
 * Ask as the screen's one primary action, and Stop in its place while an answer is on its way. Enter asks,
 * Shift+Enter starts a new line, Escape stops an answer. Above the box, a line says when the study
 * assistant isn't ready, so a question asked meanwhile waits rather than fails. FX thread only.
 */
final class Composer extends VBox {

    /** Lines the box grows to before it scrolls. */
    static final int MAX_ROWS = 6;
    private static final double LINE_HEIGHT = 22;
    private static final double BOX_PADDING = 22;

    private final TextArea input = new TextArea();
    private final Button ask = new Button("Ask");
    private final Button stop = new Button("Stop");
    private final HBox status = new HBox(10);
    private final Label statusText = new Label();
    private final Button statusAction = new Button();
    private final Label hint = new Label("Answers come only from this course's PDFs. Shift+Enter starts a new line.");
    private final BooleanProperty answering = new SimpleBooleanProperty(this, "answering", false);
    private final BooleanProperty blocked = new SimpleBooleanProperty(this, "blocked", false);
    private final Text measure = new Text();
    private Runnable onRestart = () -> { };
    private Runnable onSettings = () -> { };

    /**
     * @param onAsk  called with the question (stripped, never blank) when the learner asks
     * @param onStop called when the learner stops the answer on its way
     */
    Composer(Consumer<String> onAsk, Runnable onStop) {
        Objects.requireNonNull(onAsk, "onAsk");
        Objects.requireNonNull(onStop, "onStop");
        getStyleClass().add("ask-composer-area");

        input.setPromptText("Ask a question about this course");
        input.setWrapText(true);
        input.setPrefRowCount(1);
        input.getStyleClass().add("ask-composer-input");
        input.setAccessibleText("Question");
        HBox.setHgrow(input, Priority.ALWAYS);
        input.disableProperty().bind(answering.or(blocked));
        input.textProperty().addListener((obs, old, text) -> fitHeight());
        input.widthProperty().addListener((obs, old, width) -> fitHeight());
        input.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                if (event.isShiftDown()) {
                    input.replaceSelection("\n");
                } else if (!ask.isDisabled()) {
                    submit(onAsk);
                }
            } else if (event.getCode() == KeyCode.TAB && !event.isControlDown() && !event.isMetaDown()) {
                // The box is for one question, not tabbed text: Tab moves on like everywhere else.
                event.consume();
                input.fireEvent(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.TAB, event.isShiftDown(), true,
                        false, false));
            }
        });

        ask.getStyleClass().addAll("primary", "ask-send");
        ask.setGraphic(Icons.of(Feather.ARROW_UP, 16));
        ask.setMinWidth(Region.USE_PREF_SIZE);
        ask.disableProperty().bind(Bindings.createBooleanBinding(
                () -> answering.get() || blocked.get() || input.getText().isBlank(), answering, blocked, input.textProperty()));
        ask.setOnAction(event -> submit(onAsk));
        ask.visibleProperty().bind(answering.not());
        ask.managedProperty().bind(ask.visibleProperty());

        stop.getStyleClass().add("ask-stop");
        stop.setGraphic(Icons.of(Feather.SQUARE, 12));
        stop.setMinWidth(Region.USE_PREF_SIZE);
        stop.setAccessibleText("Stop the answer");
        stop.setOnAction(event -> {
            stop.setDisable(true);
            onStop.run();
        });
        stop.visibleProperty().bind(answering);
        stop.managedProperty().bind(stop.visibleProperty());
        answering.addListener((obs, was, now) -> stop.setDisable(false));

        StackPane buttons = new StackPane(ask, stop);
        buttons.setAlignment(Pos.BOTTOM_RIGHT);
        buttons.setMinWidth(Region.USE_PREF_SIZE);
        HBox field = new HBox(8, input, buttons);
        field.getStyleClass().add("ask-field");
        field.setAlignment(Pos.BOTTOM_LEFT);
        field.setFillHeight(false);

        statusText.getStyleClass().add("ask-composer-status-text");
        statusText.setWrapText(true);
        statusAction.getStyleClass().addAll("quiet", "small");
        statusAction.setOnAction(event -> {
            if (status.getStyleClass().contains("failed")) {
                onRestart.run();
            } else {
                onSettings.run();
            }
        });
        status.getStyleClass().add("ask-composer-status");
        status.setAlignment(Pos.CENTER_LEFT);
        status.setVisible(false);
        status.setManaged(false);

        hint.getStyleClass().addAll("text-caption", "ask-composer-hint");
        hint.setWrapText(true);
        getChildren().addAll(status, field, hint);
        fitHeight();
    }

    TextArea input() {
        return input;
    }

    Button askButton() {
        return ask;
    }

    Button stopButton() {
        return stop;
    }

    /** True while an answer is on its way: the box is disabled and Stop replaces Ask. */
    void setAnswering(boolean value) {
        answering.set(value);
    }

    boolean isAnswering() {
        return answering.get();
    }

    /** Disables asking altogether (a course the learner can't ask about), with {@code reason} as the hint. */
    void setBlocked(String reason) {
        blocked.set(reason != null);
        hint.setText(reason != null ? reason
                : "Answers come only from this course's PDFs. Shift+Enter starts a new line.");
    }

    /** What the Restart and Settings links on the status line do. */
    void setStatusActions(Runnable restart, Runnable settings) {
        onRestart = Objects.requireNonNull(restart, "restart");
        onSettings = Objects.requireNonNull(settings, "settings");
    }

    /** Shows the study assistant's readiness above the box when it isn't ready; hides the line when it is. */
    void setAssistantStatus(AssistantStatus value) {
        status.getStyleClass().removeAll("starting", "failed", "unavailable");
        if (value == null || value.isReady()) {
            status.setVisible(false);
            status.setManaged(false);
            return;
        }
        statusText.setText(value.message().text());
        switch (value.state()) {
            case STARTING -> {
                status.getStyleClass().add("starting");
                status.getChildren().setAll(new ActivityIndicator(14), statusText);
            }
            case FAILED -> {
                status.getStyleClass().add("failed");
                statusAction.setText("Restart");
                status.getChildren().setAll(Icons.of(Feather.ALERT_CIRCLE, 16), statusText, statusAction);
            }
            default -> {
                status.getStyleClass().add("unavailable");
                statusAction.setText("Open Settings");
                status.getChildren().setAll(Icons.of(Feather.ALERT_CIRCLE, 16), statusText, statusAction);
            }
        }
        status.setVisible(true);
        status.setManaged(true);
    }

    /** True while the readiness line shows. */
    boolean isStatusShown() {
        return status.isVisible();
    }

    String statusText() {
        return status.isVisible() ? statusText.getText() : "";
    }

    /** Replaces the question being written and puts the caret at its end. */
    void draft(String text) {
        input.setText(text);
        input.requestFocus();
        input.positionCaret(input.getLength());
    }

    /** Puts the keyboard focus in the box. */
    void focus() {
        input.requestFocus();
    }

    private void submit(Consumer<String> onAsk) {
        String text = input.getText() == null ? "" : input.getText().strip();
        if (text.isEmpty() || answering.get() || blocked.get()) {
            return;
        }
        onAsk.accept(text);
    }

    /** Grows the box with its text, from one line to {@link #MAX_ROWS}. */
    private void fitHeight() {
        double width = input.getWidth() - BOX_PADDING;
        if (width <= 0) {
            setInputHeight(LINE_HEIGHT + BOX_PADDING);
            return;
        }
        measure.setFont(input.getFont());
        measure.setWrappingWidth(width);
        String text = input.getText() == null || input.getText().isEmpty() ? "X" : input.getText();
        measure.setText(text.endsWith("\n") ? text + "X" : text);
        double lineHeight = Math.max(LINE_HEIGHT, input.getFont().getSize() * 1.45);
        int rows = (int) Math.max(1, Math.round(measure.getLayoutBounds().getHeight() / (input.getFont().getSize() * 1.32)));
        setInputHeight(Math.min(MAX_ROWS, rows) * lineHeight + BOX_PADDING);
    }

    private void setInputHeight(double height) {
        input.setMinHeight(height);
        input.setPrefHeight(height);
        input.setMaxHeight(height);
    }
}
