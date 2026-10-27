package edu.learnsphere.app.ui.components;

import edu.learnsphere.app.theme.Motion;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * Asks the learner to confirm something that can't be undone, in a card over a dimmed window. The confirm
 * button says exactly what happens ("Delete course"); destructive confirmations use the danger colour and
 * put the keyboard focus on Cancel. Escape cancels; Enter presses the focused button. Focus stays inside
 * the dialog while it is open.
 *
 * <pre>{@code
 * ConfirmDialog.of("Delete this course?", "Its PDFs, questions and progress are removed from this computer.",
 *         "Delete course").danger().show(root).thenAccept(confirmed -> { if (confirmed) delete(); });
 * }</pre>
 */
public final class ConfirmDialog {

    private final String title;
    private final String message;
    private final String confirmLabel;
    private String cancelLabel = "Cancel";
    private boolean danger;

    private ConfirmDialog(String title, String message, String confirmLabel) {
        this.title = Objects.requireNonNull(title, "title");
        this.message = Objects.requireNonNull(message, "message");
        this.confirmLabel = Objects.requireNonNull(confirmLabel, "confirmLabel");
    }

    /**
     * @param title        the question, e.g. "Delete this course?"
     * @param message      what will happen, in one or two sentences
     * @param confirmLabel the confirm button, naming the action ("Delete course")
     */
    public static ConfirmDialog of(String title, String message, String confirmLabel) {
        return new ConfirmDialog(title, message, confirmLabel);
    }

    /** The cancel button's label (default "Cancel"). */
    public ConfirmDialog cancelLabel(String label) {
        cancelLabel = Objects.requireNonNull(label, "label");
        return this;
    }

    /** Marks the action as destructive: danger colour, focus starts on Cancel. */
    public ConfirmDialog danger() {
        danger = true;
        return this;
    }

    /**
     * Opens the dialog in the window of {@code anchor}. The future completes on the FX thread with true when
     * confirmed, false when cancelled.
     */
    public CompletableFuture<Boolean> show(Node anchor) {
        StackPane layer = Overlays.find(anchor).modals();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Modal modal = new Modal(result);
        layer.getChildren().add(modal);
        modal.open();
        return result;
    }

    /** The open dialog: scrim plus card. Package-visible for tests. */
    final class Modal extends StackPane {

        private final CompletableFuture<Boolean> result;
        private final VBox card;
        private final Button confirm;
        private final Button cancel;
        private ChangeListener<Node> focusTrap;
        private Node focusBefore;

        Modal(CompletableFuture<Boolean> result) {
            this.result = result;
            getStyleClass().add("modal");
            Region scrim = new Region();
            scrim.getStyleClass().add("modal-scrim");
            scrim.setOnMouseClicked(event -> event.consume());

            Label heading = new Label(title);
            heading.getStyleClass().add("modal-title");
            heading.setWrapText(true);
            Label body = new Label(message);
            body.getStyleClass().add("modal-message");
            body.setWrapText(true);

            cancel = new Button(cancelLabel);
            cancel.getStyleClass().add("modal-cancel");
            cancel.setOnAction(event -> close(false));
            confirm = new Button(confirmLabel);
            confirm.getStyleClass().addAll("modal-confirm", danger ? "danger" : "primary");
            confirm.setOnAction(event -> close(true));
            HBox buttons = new HBox(12, cancel, confirm);
            buttons.setAlignment(Pos.CENTER_RIGHT);
            buttons.getStyleClass().add("modal-buttons");

            card = new VBox(heading, body, buttons);
            card.getStyleClass().add("modal-card");
            card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            card.setAccessibleRole(AccessibleRole.DIALOG);
            card.setAccessibleText(title);
            getChildren().addAll(scrim, card);
            addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ESCAPE) {
                    event.consume();
                    close(false);
                } else if (event.getCode() == KeyCode.ENTER) {
                    event.consume();
                    Node focused = getScene() == null ? null : getScene().getFocusOwner();
                    if (focused == confirm) {
                        close(true);
                    } else if (focused == cancel) {
                        close(false);
                    } else {
                        close(!danger);
                    }
                }
            });
        }

        void open() {
            Scene scene = getScene();
            if (scene != null) {
                focusBefore = scene.getFocusOwner();
                focusTrap = (obs, old, owner) -> {
                    if (owner != null && !isInside(owner)) {
                        initialFocus().requestFocus();
                    }
                };
                scene.focusOwnerProperty().addListener(focusTrap);
            }
            Platform.runLater(() -> initialFocus().requestFocus());
            FadeTransition fade = new FadeTransition(Duration.millis(160), this);
            fade.setFromValue(0);
            fade.setToValue(1);
            if (Motion.reduced()) {
                fade.play();
            } else {
                ScaleTransition grow = new ScaleTransition(Duration.millis(180), card);
                grow.setFromX(0.97);
                grow.setFromY(0.97);
                grow.setToX(1);
                grow.setToY(1);
                grow.setInterpolator(Motion.EASE_OUT);
                new ParallelTransition(fade, grow).play();
            }
        }

        Button confirmButton() {
            return confirm;
        }

        Button cancelButton() {
            return cancel;
        }

        private Node initialFocus() {
            return danger ? cancel : confirm;
        }

        private boolean isInside(Node node) {
            for (Node n = node; n != null; n = n.getParent()) {
                if (n == card) {
                    return true;
                }
            }
            return false;
        }

        private void close(boolean confirmed) {
            if (result.isDone()) {
                return;
            }
            Scene scene = getScene();
            if (scene != null && focusTrap != null) {
                scene.focusOwnerProperty().removeListener(focusTrap);
            }
            result.complete(confirmed);
            Runnable remove = () -> {
                if (getParent() instanceof StackPane layer) {
                    layer.getChildren().remove(this);
                }
                if (focusBefore != null && focusBefore.getScene() != null) {
                    focusBefore.requestFocus();
                }
            };
            if (Motion.reduced()) {
                remove.run();
            } else {
                Motion.fadeOut(this, Duration.millis(120), remove);
            }
        }
    }
}
