package edu.learnsphere.app.ui.components;

import edu.learnsphere.app.theme.Motion;
import edu.learnsphere.core.error.UserMessage;
import java.util.Objects;
import javafx.animation.PauseTransition;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.feather.Feather;

/**
 * Short notices in the window's bottom-right corner that go away by themselves: "Course published",
 * "Couldn't save your changes. Try again." At most {@value #MAX_VISIBLE} show at once (the oldest leaves
 * first); hovering one keeps it until the pointer leaves. An error toast takes a {@link UserMessage}, never
 * exception text.
 *
 * <pre>{@code
 * Toasts.success(root, "Course published");
 * Toasts.error(root, message);
 * Toasts.show(root, Toasts.Kind.INFO, "Item rejected", "Undo", this::undo);
 * }</pre>
 */
public final class Toasts {

    /** What a toast reports. */
    public enum Kind {
        INFO(Feather.INFO),
        SUCCESS(Feather.CHECK_CIRCLE),
        ERROR(Feather.ALERT_CIRCLE);

        private final Ikon icon;

        Kind(Ikon icon) {
            this.icon = icon;
        }
    }

    /** Toasts shown at once. */
    public static final int MAX_VISIBLE = 3;
    /** How long an information or success toast stays. */
    public static final Duration DURATION = Duration.seconds(4);
    /** How long an error toast stays (long enough to read and act). */
    public static final Duration ERROR_DURATION = Duration.seconds(7);

    private Toasts() {
    }

    public static Toast info(Node anchor, String message) {
        return show(anchor, Kind.INFO, message, null, null);
    }

    public static Toast success(Node anchor, String message) {
        return show(anchor, Kind.SUCCESS, message, null, null);
    }

    public static Toast error(Node anchor, UserMessage message) {
        return show(anchor, Kind.ERROR, message.text(), null, null);
    }

    /**
     * Shows a toast in the window of {@code anchor}.
     *
     * @param actionLabel a button on the toast (e.g. "Undo"), or null
     * @param action      what the button does (the toast closes after), or null
     */
    public static Toast show(Node anchor, Kind kind, String message, String actionLabel, Runnable action) {
        return show(anchor, kind, message, actionLabel, action, kind == Kind.ERROR ? ERROR_DURATION : DURATION);
    }

    /** Like {@link #show(Node, Kind, String, String, Runnable)} with an explicit duration. */
    public static Toast show(Node anchor, Kind kind, String message, String actionLabel, Runnable action,
                             Duration duration) {
        VBox layer = Overlays.find(anchor).toasts();
        Toast toast = new Toast(kind, message, actionLabel, action, duration);
        while (layer.getChildren().stream().filter(n -> n instanceof Toast t && !t.closing).count() >= MAX_VISIBLE) {
            layer.getChildren().stream().filter(n -> n instanceof Toast t && !t.closing).findFirst()
                    .ifPresent(n -> ((Toast) n).dismiss());
        }
        layer.getChildren().add(toast);
        toast.start();
        return toast;
    }

    /**
     * A toast that is not shown in a window and never times out, for the component gallery and tests that
     * check its look.
     */
    public static Toast sample(Kind kind, String message, String actionLabel) {
        return new Toast(kind, message, actionLabel, actionLabel == null ? null : () -> { }, Duration.INDEFINITE);
    }

    /** One toast. {@link #dismiss()} closes it early. */
    public static final class Toast extends HBox {

        private final Kind kind;
        private final Label message;
        private final PauseTransition timer;
        private boolean closing;

        Toast(Kind kind, String text, String actionLabel, Runnable action, Duration duration) {
            super(12);
            this.kind = Objects.requireNonNull(kind, "kind");
            getStyleClass().addAll("toast", "toast-" + kind.name().toLowerCase(java.util.Locale.ROOT));
            setAlignment(Pos.CENTER_LEFT);
            setAccessibleRole(AccessibleRole.TEXT);
            setAccessibleText(text);
            message = new Label(Objects.requireNonNull(text, "text"));
            message.getStyleClass().add("toast-message");
            message.setWrapText(true);
            message.setMaxWidth(300);
            HBox.setHgrow(message, Priority.ALWAYS);
            var icon = Icons.of(kind.icon, 18);
            icon.getStyleClass().add("toast-icon");
            getChildren().addAll(icon, message);
            if (actionLabel != null && action != null) {
                Button button = new Button(actionLabel);
                button.getStyleClass().addAll("toast-action");
                button.setOnAction(event -> {
                    action.run();
                    dismiss();
                });
                getChildren().add(button);
            }
            IconButton close = new IconButton(Feather.X, "Dismiss").small();
            close.getStyleClass().add("toast-close");
            close.setOnAction(event -> dismiss());
            getChildren().add(close);
            timer = new PauseTransition(duration);
            timer.setOnFinished(event -> dismiss());
            hoverProperty().addListener((obs, was, hovering) -> {
                if (closing) {
                    return;
                }
                if (hovering) {
                    timer.pause();
                } else {
                    timer.playFromStart();
                }
            });
        }

        void start() {
            Motion.enter(this);
            timer.playFromStart();
        }

        public Kind kind() {
            return kind;
        }

        public String message() {
            return message.getText();
        }

        /** True until the toast starts leaving. */
        public boolean isShowing() {
            return !closing && getParent() != null;
        }

        /** Closes the toast now. */
        public void dismiss() {
            if (closing) {
                return;
            }
            closing = true;
            timer.stop();
            Runnable remove = () -> {
                if (getParent() instanceof VBox layer) {
                    layer.getChildren().remove(this);
                }
            };
            if (Motion.reduced()) {
                remove.run();
            } else {
                Motion.fadeOut(this, Duration.millis(140), remove);
            }
        }
    }
}
