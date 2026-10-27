package edu.learnsphere.app.ui.studio;

import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.Fx;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.util.Cancellable;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * Reloads a view's data in the background whenever something changed, at most one load at a time. Changes
 * that arrive while a load runs (a PDF reports every page it reads) start a single further load after it,
 * so a burst of events costs two loads, not one per event. Stops with the {@link Disposer} it is given.
 * FX thread only.
 *
 * @param <T> what a load returns
 */
final class Refresher<T> {

    private final Callable<? extends T> work;
    private final Consumer<? super T> onFx;
    private final Consumer<? super UserMessage> onError;
    private Cancellable current;
    private boolean pending;
    private boolean stopped;

    Refresher(Disposer disposer, Callable<? extends T> work, Consumer<? super T> onFx,
              Consumer<? super UserMessage> onError) {
        this.work = Objects.requireNonNull(work, "work");
        this.onFx = Objects.requireNonNull(onFx, "onFx");
        this.onError = Objects.requireNonNull(onError, "onError");
        disposer.onDispose(this::stop);
    }

    /** Loads now, or once more after the load that is running. */
    void request() {
        Fx.requireFxThread();
        if (stopped) {
            return;
        }
        if (current != null) {
            pending = true;
            return;
        }
        start();
    }

    /** True while a load runs. */
    boolean isLoading() {
        return current != null;
    }

    private void start() {
        pending = false;
        current = UiTasks.run(work, result -> finish(() -> onFx.accept(result)),
                message -> finish(() -> onError.accept(message)));
    }

    private void finish(Runnable deliver) {
        current = null;
        deliver.run();
        if (pending && !stopped) {
            start();
        }
    }

    private void stop() {
        stopped = true;
        if (current != null) {
            current.cancel();
            current = null;
        }
    }
}
