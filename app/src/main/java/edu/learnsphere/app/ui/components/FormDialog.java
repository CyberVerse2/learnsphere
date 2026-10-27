package edu.learnsphere.app.ui.components;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.util.Cancellable;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableBooleanValue;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/**
 * A small form in a card over the dimmed window: a title, an optional sentence, the fields, a line for what
 * went wrong, and Cancel plus a button that says what happens ("Save name", "Delete profile"). The confirm
 * button can run blocking work in the background ({@link #action}): the form stays open and quiet while it
 * runs, closes when it succeeds, and shows the {@link UserMessage} inline when it fails, so the learner can
 * correct a field and try again.
 *
 * <p>The first text field gets the focus (Cancel does in a {@link #danger()} form with no fields); Enter
 * confirms when the confirm button is enabled, Escape cancels, and focus stays inside while it is open.
 * {@link #progress} shows a card with a working indicator and no buttons for work that cannot be stopped.
 *
 * <pre>{@code
 * TextField name = new TextField(profile.displayName());
 * FormDialog.of("Rename profile")
 *         .content(FormDialog.field("Name", name))
 *         .confirm("Save name")
 *         .confirmEnabled(name.textProperty().isNotEmpty())
 *         .action(() -> {
 *             String chosen = name.getText().strip();
 *             return () -> profiles.rename(id, chosen);
 *         }, renamed -> Toasts.success(root, "Name saved"))
 *         .show(root);
 * }</pre>
 */
public final class FormDialog {

    private static final String STYLESHEET = Objects.requireNonNull(
            FormDialog.class.getResource("form-dialog.css"), "form-dialog.css").toExternalForm();

    private final String title;
    private String message;
    private final List<Node> content = new ArrayList<>();
    private String confirmLabel = "Done";
    private String cancelLabel = "Cancel";
    private boolean danger;
    private boolean wide;
    private ObservableBooleanValue confirmEnabled;
    private Supplier<? extends Callable<?>> task;
    private Consumer<Object> onDone;
    private Supplier<UserMessage> validation = () -> null;

    private FormDialog(String title) {
        this.title = Objects.requireNonNull(title, "title");
    }

    /** A form titled {@code title}, e.g. "Change password". */
    public static FormDialog of(String title) {
        return new FormDialog(title);
    }

    /**
     * A card with a working indicator and no buttons, for work the learner waits for ("Backing up your
     * profile"). Close the returned handle when the work ends.
     */
    public static Handle progress(Node anchor, String title, String message) {
        FormDialog dialog = new FormDialog(title).message(message);
        Modal modal = dialog.new Modal(new CompletableFuture<>(), true);
        Overlays.find(anchor).modals().getChildren().add(modal);
        modal.open();
        return modal;
    }

    /** One or two sentences under the title. */
    public FormDialog message(String text) {
        message = text;
        return this;
    }

    /** The fields and anything else the form shows, top to bottom. */
    public FormDialog content(Node... nodes) {
        content.addAll(List.of(nodes));
        return this;
    }

    /** The confirm button's label, naming the action (default "Done"). */
    public FormDialog confirm(String label) {
        confirmLabel = Objects.requireNonNull(label, "label");
        return this;
    }

    /** The cancel button's label (default "Cancel"), or null for a form with only the confirm button. */
    public FormDialog cancel(String label) {
        cancelLabel = label;
        return this;
    }

    /** Marks the action as destructive: danger colour. */
    public FormDialog danger() {
        danger = true;
        return this;
    }

    /** A wider card, for reading (licences). */
    public FormDialog wide() {
        wide = true;
        return this;
    }

    /** Enables the confirm button only while {@code enabled} holds. */
    public FormDialog confirmEnabled(ObservableBooleanValue enabled) {
        confirmEnabled = enabled;
        return this;
    }

    /**
     * A check that runs on the FX thread before the action: it returns what is wrong with the fields ("The
     * passwords don't match."), or null when they are fine.
     */
    public FormDialog validate(Supplier<UserMessage> check) {
        validation = Objects.requireNonNull(check, "check");
        return this;
    }

    /**
     * What confirming does. {@code task} is asked on the FX thread once the form is confirmed and valid, so it
     * reads the fields there; the work it returns runs in the background with the form waiting, and its result
     * goes to {@code onDone} on the FX thread after the form closes. A failure keeps the form open with its
     * message.
     *
     * <pre>{@code
     * .action(() -> {
     *     String name = field.getText().strip();
     *     return () -> profiles.rename(id, name);
     * }, renamed -> Toasts.success(root, "Changes saved"))
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    public <T> FormDialog action(Supplier<Callable<T>> task, Consumer<? super T> onDone) {
        this.task = Objects.requireNonNull(task, "task");
        Objects.requireNonNull(onDone, "onDone");
        this.onDone = result -> onDone.accept((T) result);
        return this;
    }

    /**
     * Opens the form in the window of {@code anchor}. {@link Handle#result()} completes with true when the
     * form was confirmed (and its action succeeded), false when it was cancelled.
     */
    public Handle show(Node anchor) {
        Modal modal = new Modal(new CompletableFuture<>(), false);
        Overlays.find(anchor).modals().getChildren().add(modal);
        modal.open();
        return modal;
    }

    /**
     * A labelled field: the label above the control, and an optional hint below it.
     *
     * @param hint a short sentence under the control, or null
     */
    public static VBox field(String label, Node control, String hint) {
        Label text = new Label(label);
        text.getStyleClass().add("field-label");
        text.setLabelFor(control);
        VBox box = new VBox(6, text, control);
        box.getStyleClass().add("form-field");
        if (hint != null) {
            Label below = new Label(hint);
            below.getStyleClass().add("form-field-hint");
            below.setWrapText(true);
            box.getChildren().add(below);
        }
        return box;
    }

    /** A labelled field without a hint. */
    public static VBox field(String label, Node control) {
        return field(label, control, null);
    }

    /** An open form. */
    public interface Handle {

        /** Completes with true when confirmed, false when cancelled or closed. */
        CompletableFuture<Boolean> result();

        /** Shows {@code message} in the form's error line. */
        void showError(UserMessage message);

        /** The error line's text, empty when there is none. */
        String errorText();

        /** Closes the form as cancelled (no effect when it already closed). */
        void close();

        /** True while the form is on screen. */
        boolean isOpen();

        /** The confirm button (tests). */
        Button confirmButton();

        /** The card (tests and the component gallery). */
        Region card();
    }

    /** The open form: scrim plus card. */
    private final class Modal extends StackPane implements Handle {

        private final CompletableFuture<Boolean> result;
        private final boolean progressOnly;
        private final VBox card = new VBox();
        private final VBox body = new VBox();
        private final Label error = new Label();
        private final Button confirm = new Button(confirmLabel);
        private final Button cancel = cancelLabel == null ? null : new Button(cancelLabel);
        private final BooleanProperty busy = new SimpleBooleanProperty(this, "busy");
        private final ActivityIndicator spinner = new ActivityIndicator(18);
        private ChangeListener<Node> focusTrap;
        private Node focusBefore;
        private Cancellable running;

        Modal(CompletableFuture<Boolean> result, boolean progressOnly) {
            this.result = result;
            this.progressOnly = progressOnly;
            getStylesheets().add(STYLESHEET);
            getStyleClass().addAll("modal", "form-dialog");
            Region scrim = new Region();
            scrim.getStyleClass().add("modal-scrim");
            scrim.setOnMouseClicked(event -> event.consume());

            Label heading = new Label(title);
            heading.getStyleClass().add("modal-title");
            heading.setWrapText(true);
            card.getStyleClass().addAll("modal-card", "form-dialog-card");
            if (wide) {
                card.getStyleClass().add("wide");
            }
            card.getChildren().add(heading);
            if (message != null) {
                Label sentence = new Label(message);
                sentence.getStyleClass().add("modal-message");
                sentence.setWrapText(true);
                card.getChildren().add(sentence);
            }
            if (progressOnly) {
                ActivityIndicator working = new ActivityIndicator(22);
                HBox line = new HBox(working);
                line.getStyleClass().add("form-dialog-progress");
                card.getChildren().add(line);
            } else {
                body.getStyleClass().add("form-dialog-body");
                body.getChildren().setAll(content);
                body.disableProperty().bind(busy);
                if (!content.isEmpty()) {
                    card.getChildren().add(body);
                }
                error.getStyleClass().add("form-dialog-error");
                error.setWrapText(true);
                error.managedProperty().bind(error.textProperty().isNotEmpty());
                error.visibleProperty().bind(error.managedProperty());
                error.setAccessibleRole(AccessibleRole.TEXT);
                card.getChildren().add(error);

                confirm.getStyleClass().addAll("modal-confirm", danger ? "danger" : "primary");
                confirm.setOnAction(event -> confirm());
                confirm.disableProperty().bind(confirmEnabled == null ? busy
                        : Bindings.createBooleanBinding(() -> busy.get() || !confirmEnabled.get(), busy, confirmEnabled));
                spinner.visibleProperty().bind(busy);
                spinner.managedProperty().bind(busy);
                Region push = new Region();
                HBox.setHgrow(push, Priority.ALWAYS);
                HBox buttons = new HBox(12, spinner, push);
                if (cancel != null) {
                    cancel.getStyleClass().add("modal-cancel");
                    cancel.setOnAction(event -> close(false));
                    cancel.disableProperty().bind(busy);
                    buttons.getChildren().add(cancel);
                }
                buttons.getChildren().add(confirm);
                buttons.setAlignment(Pos.CENTER_RIGHT);
                buttons.getStyleClass().add("modal-buttons");
                card.getChildren().add(buttons);
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
                if (!progressOnly && !busy.get()) {
                    close(false);
                }
            } else if (event.getCode() == KeyCode.ENTER && !progressOnly) {
                Node focused = getScene() == null ? null : getScene().getFocusOwner();
                if (focused instanceof javafx.scene.control.TextArea) {
                    return;
                }
                event.consume();
                if (focused == cancel && cancel != null) {
                    close(false);
                } else if (focused instanceof Button button && button != confirm && isInside(button)) {
                    button.fire();
                } else if (!confirm.isDisabled()) {
                    confirm();
                }
            }
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

        private Node initialFocus() {
            if (progressOnly) {
                return card;
            }
            Node field = firstTextInput(body);
            if (field != null) {
                return field;
            }
            return danger && cancel != null ? cancel : confirm;
        }

        private static Node firstTextInput(Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                if (child instanceof TextInputControl input && input.isVisible() && !input.isDisabled()) {
                    return input;
                }
                if (child instanceof Parent nested) {
                    Node found = firstTextInput(nested);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        private boolean isInside(Node node) {
            for (Node n = node; n != null; n = n.getParent()) {
                if (n == card) {
                    return true;
                }
            }
            return false;
        }

        private void confirm() {
            if (busy.get() || result.isDone()) {
                return;
            }
            error.setText("");
            UserMessage problem = validation.get();
            if (problem != null) {
                showError(problem);
                Node field = firstTextInput(body);
                if (field != null) {
                    field.requestFocus();
                }
                return;
            }
            if (task == null) {
                close(true);
                return;
            }
            Callable<?> work = task.get();
            busy.set(true);
            running = UiTasks.run(work, value -> {
                busy.set(false);
                close(true);
                onDone.accept(value);
            }, failure -> {
                busy.set(false);
                showError(failure);
                Node field = firstTextInput(body);
                (field == null ? confirm : field).requestFocus();
            });
        }

        @Override
        public CompletableFuture<Boolean> result() {
            return result;
        }

        @Override
        public void showError(UserMessage failure) {
            error.setText(failure.text());
        }

        @Override
        public String errorText() {
            return error.getText();
        }

        @Override
        public void close() {
            if (running != null) {
                running.cancel();
            }
            close(false);
        }

        @Override
        public boolean isOpen() {
            return !result.isDone();
        }

        @Override
        public Button confirmButton() {
            return confirm;
        }

        @Override
        public Region card() {
            return card;
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
            if (Motion.reduced() || getScene() == null) {
                remove.run();
            } else {
                Motion.fadeOut(this, Duration.millis(120), remove);
            }
        }
    }
}
