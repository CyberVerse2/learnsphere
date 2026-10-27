package edu.learnsphere.app.ui.library;

import edu.learnsphere.app.ui.Disposer;
import edu.learnsphere.app.ui.UiTasks;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.util.Cancellable;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/**
 * A screen's data load that runs again whenever something it shows changes. Requests that arrive while a load
 * is running are folded into one more load after it, so a burst of events (an import adds dozens of
 * questions) costs at most two loads, and an older result never replaces a newer one. FX thread only.
 *
 * @param <T> what the load produces
 */
public final class LiveLoad<T> {

    private final Callable<? extends T> work;
    private final Consumer<? super T> onResult;
    private final Consumer<? super UserMessage> onError;
    private final Disposer disposer;
    private Cancellable running;
    private boolean again;
    private int completed;

    /**
     * @param work     the blocking load, run off the FX thread
     * @param onResult applies a result on the FX thread
     * @param onError  shows a failure on the FX thread
     * @param disposer the view's disposer: a load still running when the view goes is dropped
     */
    public LiveLoad(Callable<? extends T> work, Consumer<? super T> onResult, Consumer<? super UserMessage> onError,
                    Disposer disposer) {
        this.work = Objects.requireNonNull(work, "work");
        this.onResult = Objects.requireNonNull(onResult, "onResult");
        this.onError = Objects.requireNonNull(onError, "onError");
        this.disposer = Objects.requireNonNull(disposer, "disposer");
    }

    /** Loads now, or once more after the load that is running. */
    public void request() {
        if (disposer.isDisposed()) {
            return;
        }
        if (running != null) {
            again = true;
            return;
        }
        again = false;
        running = disposer.add(UiTasks.run(work, result -> {
            running = null;
            completed++;
            onResult.accept(result);
            if (again) {
                request();
            }
        }, message -> {
            running = null;
            onError.accept(message);
            if (again) {
                request();
            }
        }));
    }

    /** True while a load is running. */
    public boolean isLoading() {
        return running != null;
    }

    /** How many loads have finished (tests wait on it). */
    public int completed() {
        return completed;
    }
}
