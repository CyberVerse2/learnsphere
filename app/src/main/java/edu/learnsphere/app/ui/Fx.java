package edu.learnsphere.app.ui;

import javafx.application.Platform;

/** FX-thread helpers. */
public final class Fx {

    private Fx() {
    }

    /** Runs {@code action} on the FX thread: now when already on it, otherwise as soon as possible. */
    public static void run(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /** Fails fast when called off the FX thread. */
    public static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Not on the FX application thread: " + Thread.currentThread().getName());
        }
    }
}
