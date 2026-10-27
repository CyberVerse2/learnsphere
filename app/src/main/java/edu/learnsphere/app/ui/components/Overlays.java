package edu.learnsphere.app.ui.components;

import java.util.Objects;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * The layers above a window's content: toasts in the bottom-right corner and modal dialogs over a scrim.
 * The main window installs them once on its root; {@link Toasts} and {@link ConfirmDialog} find them from
 * any node in the window with {@link #find(Node)}.
 */
public final class Overlays {

    private static final String KEY = Overlays.class.getName();

    private final StackPane host;
    private final VBox toasts = new VBox(8);
    private final StackPane modals = new StackPane();

    private Overlays(StackPane host) {
        this.host = host;
        toasts.getStyleClass().add("toast-layer");
        toasts.setAlignment(Pos.BOTTOM_RIGHT);
        toasts.setPickOnBounds(false);
        toasts.setFillWidth(false);
        StackPane.setAlignment(toasts, Pos.BOTTOM_RIGHT);
        modals.getStyleClass().add("modal-layer");
        modals.setPickOnBounds(false);
        modals.setVisible(false);
        modals.getChildren().addListener((javafx.collections.ListChangeListener<Node>) change ->
                modals.setVisible(!modals.getChildren().isEmpty()));
    }

    /** Adds the overlay layers on top of {@code host}'s children. Idempotent. */
    public static Overlays install(StackPane host) {
        Objects.requireNonNull(host, "host");
        Object existing = host.getProperties().get(KEY);
        if (existing instanceof Overlays overlays) {
            overlays.raise();
            return overlays;
        }
        Overlays overlays = new Overlays(host);
        host.getChildren().addAll(overlays.toasts, overlays.modals);
        host.getProperties().put(KEY, overlays);
        return overlays;
    }

    /**
     * The overlays of the window {@code node} is in: the nearest ancestor that has them installed.
     *
     * @throws IllegalStateException when {@code node} is not in a window with overlays
     */
    public static Overlays find(Node node) {
        Objects.requireNonNull(node, "node");
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.getProperties().get(KEY) instanceof Overlays overlays) {
                return overlays;
            }
        }
        if (node.getScene() != null) {
            Parent root = node.getScene().getRoot();
            if (root.getProperties().get(KEY) instanceof Overlays overlays) {
                return overlays;
            }
        }
        throw new IllegalStateException("No overlays above " + node);
    }

    /** Keeps the layers above content added after installation. */
    public void raise() {
        toasts.toFront();
        modals.toFront();
    }

    /** The toast stack (bottom-right). */
    public VBox toasts() {
        return toasts;
    }

    /** The modal layer (empty and hidden when no dialog is open). */
    public StackPane modals() {
        return modals;
    }

    /** True while a modal dialog is open. */
    public boolean hasModal() {
        return !modals.getChildren().isEmpty();
    }

    /** The window root the layers are installed on. */
    public StackPane host() {
        return host;
    }
}
