package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.Disposer;
import java.util.Objects;
import javafx.beans.value.ChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.input.KeyCombination;

/**
 * Window-wide keyboard shortcuts that last as long as a Studio view: added to the scene its root is in and
 * removed when the view goes away (or leaves the scene). A shortcut does nothing while a dialog is open.
 */
final class Shortcuts {

    private Shortcuts() {
    }

    /** Runs {@code action} on {@code keys} while {@code root} is in a scene, until {@code disposer} runs. */
    static void install(Node root, KeyCombination keys, Runnable action, Disposer disposer) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(action, "action");
        Runnable guarded = () -> {
            if (!StudioDialog.isOpenIn(root)) {
                action.run();
            }
        };
        ChangeListener<Scene> listener = (obs, old, scene) -> {
            if (old != null && old.getAccelerators().get(keys) == guarded) {
                old.getAccelerators().remove(keys);
            }
            if (scene != null) {
                scene.getAccelerators().put(keys, guarded);
            }
        };
        root.sceneProperty().addListener(listener);
        if (root.getScene() != null) {
            root.getScene().getAccelerators().put(keys, guarded);
        }
        disposer.onDispose(() -> {
            root.sceneProperty().removeListener(listener);
            Scene scene = root.getScene();
            if (scene != null && scene.getAccelerators().get(keys) == guarded) {
                scene.getAccelerators().remove(keys);
            }
        });
    }
}
