package edu.learnsphere.app.ui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Where UI code runs blocking service calls. Services are thread-safe and blocking, so view models call
 * them on {@link #background()} and apply results with {@link Fx#run(Runnable)}.
 */
public final class UiExecutors {

    private static final ExecutorService BACKGROUND = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("ui-background-", 0).factory());

    private UiExecutors() {
    }

    /** Runs each task on its own virtual thread. */
    public static ExecutorService background() {
        return BACKGROUND;
    }

    /** Stops accepting tasks (on exit). */
    public static void shutdown() {
        BACKGROUND.shutdown();
    }
}
