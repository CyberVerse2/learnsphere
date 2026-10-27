package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.components.ActivityIndicator;
import edu.learnsphere.app.ui.components.Icons;
import edu.learnsphere.app.ui.components.Overlays;
import edu.learnsphere.core.error.UserMessage;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
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
import javafx.scene.control.ButtonBase;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.feather.Feather;

/**
 * A dialog over the dimmed window with its own content: a form (a course title, a code), a typed
 * confirmation or a page preview. It looks and behaves like the app's confirmation dialog: Escape cancels,
 * Enter confirms (except in a multi-line field), focus stays inside while it is open and returns to where
 * it was after.
 *
 * <p>Confirming calls the {@link #onConfirm} handler, which decides what happens: close the dialog, show a
 * one-sentence error under the content ({@link #showError}), or mark it busy while work runs in the
 * background ({@link #setBusy}). Without a handler, confirming closes the dialog.
 *
 * <pre>{@code
 * StudioDialog.of("Rename PDF").content(field).confirm("Rename").onConfirm(dialog -> {
 *     dialog.setBusy(true);
 *     UiTasks.run(() -> rename(field.getText()), done -> dialog.close(true), dialog::showError);
 * }).show(anchor);
 * }</pre>
 */
final class StudioDialog {

    private final String title;
    private String message;
    private Node content;
    private String confirmLabel = "Done";
    private String cancelLabel = "Cancel";
    private boolean danger;
    private boolean wide;
    private boolean closeOnly;
    private Node initialFocus;
    private Consumer<StudioDialog> onConfirm = dialog -> dialog.close(true);
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private Modal modal;

    private StudioDialog(String title) {
        this.title = Objects.requireNonNull(title, "title");
    }

    /** A dialog titled {@code title}, e.g. "Rename PDF". */
    static StudioDialog of(String title) {
        return new StudioDialog(title);
    }

    /** One or two sentences under the title. */
    StudioDialog message(String text) {
        message = text;
        return this;
    }

    /** The dialog's body: fields, a preview. */
    StudioDialog content(Node node) {
        content = node;
        return this;
    }

    /** The confirm button's label, naming what it does ("Create course"). */
    StudioDialog confirm(String label) {
        confirmLabel = Objects.requireNonNull(label, "label");
        return this;
    }

    /** The cancel button's label (default "Cancel"). */
    StudioDialog cancel(String label) {
        cancelLabel = Objects.requireNonNull(label, "label");
        return this;
    }

    /** A destructive action: danger colour, focus starts on Cancel. */
    StudioDialog danger() {
        danger = true;
        return this;
    }

    /** A wider card, for previews. */
    StudioDialog wide() {
        wide = true;
        return this;
    }

    /** Only one button, which closes the dialog (a preview); {@code label} names it. */
    StudioDialog closeOnly(String label) {
        closeOnly = true;
        cancelLabel = Objects.requireNonNull(label, "label");
        return this;
    }

    /** The node focused when the dialog opens (default: the confirm button, or Cancel for danger). */
    StudioDialog focus(Node node) {
        initialFocus = node;
        return this;
    }

    /** What confirming does; the handler closes the dialog when it is done. */
    StudioDialog onConfirm(Consumer<StudioDialog> handler) {
        onConfirm = Objects.requireNonNull(handler, "handler");
        return this;
    }

    /**
     * Opens the dialog in the window of {@code anchor}. Completes on the FX thread with true when the dialog
     * closed after confirming, false when it was cancelled.
     */
    CompletableFuture<Boolean> show(Node anchor) {
        StackPane layer = Overlays.find(anchor).modals();
        modal = new Modal();
        layer.getChildren().add(modal);
        modal.open();
        return result;
    }

    /** True while a dialog is open in the window of {@code node}. */
    static boolean isOpenIn(Node node) {
        try {
            return Overlays.find(node).hasModal();
        } catch (IllegalStateException notInWindow) {
            return false;
        }
    }

    /** Closes the dialog; {@code confirmed} is what {@link #show} completes with. */
    void close(boolean confirmed) {
        if (modal != null) {
            modal.close(confirmed);
        }
    }

    /** Shows {@code message} under the content and keeps the dialog open. */
    void showError(UserMessage message) {
        showError(message.text());
    }

    /** Shows {@code text} (one sentence) under the content and keeps the dialog open. */
    void showError(String text) {
        setBusy(false);
        if (modal != null) {
            modal.error.setText(text);
            modal.error.setVisible(true);
            modal.error.setManaged(true);
            modal.error.setAccessibleText(text);
        }
    }

    /** Hides the error. */
    void clearError() {
        if (modal != null) {
            modal.error.setVisible(false);
            modal.error.setManaged(false);
        }
    }

    /** While busy, the buttons are disabled, a small indicator turns, and Escape does nothing. */
    void setBusy(boolean busy) {
        if (modal != null) {
            modal.setBusy(busy);
        }
    }

    boolean isOpen() {
        return modal != null && !result.isDone();
    }

    Button confirmButton() {
        return modal == null ? null : modal.confirmButton;
    }

    Button cancelButton() {
        return modal == null ? null : modal.cancelButton;
    }

    /** The open dialog: scrim plus card. */
    private final class Modal extends StackPane {

        private final VBox card;
        private final Button confirmButton;
        private final Button cancelButton;
        private final Label error = new Label();
        private final ActivityIndicator busyIndicator = new ActivityIndicator(16);
        private ChangeListener<Node> focusTrap;
        private Node focusBefore;
        private boolean busy;

        Modal() {
            StudioStyles.apply(this);
            getStyleClass().addAll("modal", "studio-modal");
            Region scrim = new Region();
            scrim.getStyleClass().add("modal-scrim");
            scrim.setOnMouseClicked(event -> event.consume());

            Label heading = new Label(title);
            heading.getStyleClass().add("modal-title");
            heading.setWrapText(true);
            heading.setMnemonicParsing(false);
            card = new VBox(heading);
            if (message != null) {
                Label body = new Label(message);
                body.getStyleClass().add("modal-message");
                body.setWrapText(true);
                body.setMnemonicParsing(false);
                card.getChildren().add(body);
            }
            if (content != null) {
                VBox holder = new VBox(content);
                holder.getStyleClass().add("studio-dialog-content");
                VBox.setVgrow(holder, Priority.ALWAYS);
                card.getChildren().add(holder);
            }
            error.getStyleClass().add("studio-dialog-error");
            error.setGraphic(Icons.of(Feather.ALERT_CIRCLE, 14));
            error.setWrapText(true);
            error.setVisible(false);
            error.setManaged(false);
            card.getChildren().add(error);

            cancelButton = new Button(cancelLabel);
            cancelButton.getStyleClass().add("modal-cancel");
            cancelButton.setOnAction(event -> close(false));
            confirmButton = new Button(confirmLabel);
            confirmButton.getStyleClass().addAll("modal-confirm", danger ? "danger" : "primary");
            confirmButton.setOnAction(event -> confirm());
            busyIndicator.setVisible(false);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox buttons = new HBox(12, busyIndicator, spacer, cancelButton);
            if (closeOnly) {
                cancelButton.getStyleClass().add("primary");
            } else {
                buttons.getChildren().add(confirmButton);
            }
            buttons.setAlignment(Pos.CENTER_RIGHT);
            buttons.getStyleClass().add("modal-buttons");
            card.getChildren().add(buttons);

            card.getStyleClass().addAll("modal-card", "studio-dialog");
            if (wide) {
                card.getStyleClass().add("wide");
            }
            card.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            card.setAccessibleRole(AccessibleRole.DIALOG);
            card.setAccessibleText(title);
            getChildren().addAll(scrim, card);
            addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        }

        private void onKey(KeyEvent event) {
            if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                if (!busy) {
                    close(false);
                }
                return;
            }
            if (event.getCode() != KeyCode.ENTER || event.isShiftDown()) {
                return;
            }
            Node focused = getScene() == null ? null : getScene().getFocusOwner();
            if (focused instanceof TextArea && !event.isShortcutDown()) {
                return;
            }
            event.consume();
            if (busy) {
                return;
            }
            if (focused == cancelButton || closeOnly) {
                close(false);
            } else if (focused instanceof ButtonBase button && focused != confirmButton) {
                button.fire();
            } else if (!confirmButton.isDisabled()) {
                confirm();
            }
        }

        private void confirm() {
            if (!busy && !confirmButton.isDisabled() && !result.isDone()) {
                onConfirm.accept(StudioDialog.this);
            }
        }

        void setBusy(boolean value) {
            busy = value;
            busyIndicator.setVisible(value);
            confirmButton.setDisable(value);
            cancelButton.setDisable(value);
        }

        void open() {
            Scene scene = getScene();
            if (scene != null) {
                focusBefore = scene.getFocusOwner();
                focusTrap = (obs, old, owner) -> {
                    if (owner != null && !isInside(owner) && !result.isDone()) {
                        startFocus().requestFocus();
                    }
                };
                scene.focusOwnerProperty().addListener(focusTrap);
            }
            Platform.runLater(() -> startFocus().requestFocus());
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

        private Node startFocus() {
            if (initialFocus != null) {
                return initialFocus;
            }
            return danger || closeOnly ? cancelButton : confirmButton;
        }

        private boolean isInside(Node node) {
            for (Node n = node; n != null; n = n.getParent()) {
                if (n == card) {
                    return true;
                }
            }
            return false;
        }

        void close(boolean confirmed) {
            if (result.isDone()) {
                return;
            }
            Scene scene = getScene();
            if (scene != null && focusTrap != null) {
                scene.focusOwnerProperty().removeListener(focusTrap);
            }
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
            result.complete(confirmed);
        }
    }
}
