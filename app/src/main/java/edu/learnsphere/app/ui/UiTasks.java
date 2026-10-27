package edu.learnsphere.app.ui;

import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.util.Cancellable;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a blocking service call off the FX thread and hands the outcome back on it. This is how every view
 * calls services:
 *
 * <pre>{@code
 * disposer.add(UiTasks.run(() -> workspace.courses().list(CourseFilter.studying()),
 *         courses -> list.setAll(courses),
 *         message -> status.setText(message.text())));
 * }</pre>
 *
 * <p>Failures are logged with their stack trace and reach {@code onError} as the exception's
 * {@link UserMessage} ({@link LearnSphereException#userMessageOf}), never as exception text. Cancelling the
 * returned handle (a view's {@link View#dispose()} does it through its {@link Disposer}) drops the outcome:
 * neither callback runs afterwards. The worker thread is not interrupted, because an interrupt closes the
 * database and index channels the call may be using; the call finishes and its result is discarded.
 */
public final class UiTasks {

    private static final Logger LOG = LoggerFactory.getLogger(UiTasks.class);

    private UiTasks() {
    }

    /**
     * Runs {@code work} on {@link UiExecutors#background()}.
     *
     * @param onSuccess called on the FX thread with the result, unless cancelled first
     * @param onError   called on the FX thread with the sentence to show, unless cancelled first
     */
    public static <T> Cancellable run(Callable<? extends T> work, Consumer<? super T> onSuccess,
                                      Consumer<? super UserMessage> onError) {
        return run(UiExecutors.background(), work, onSuccess, onError);
    }

    /** Like {@link #run(Callable, Consumer, Consumer)} on another executor (tests). */
    static <T> Cancellable run(Executor executor, Callable<? extends T> work, Consumer<? super T> onSuccess,
                               Consumer<? super UserMessage> onError) {
        Objects.requireNonNull(work, "work");
        Objects.requireNonNull(onSuccess, "onSuccess");
        Objects.requireNonNull(onError, "onError");
        Handle handle = new Handle();
        try {
            executor.execute(() -> handle.execute(work, onSuccess, onError));
        } catch (RejectedExecutionException e) {
            // Only during shutdown: nobody is waiting for the outcome any more.
            LOG.debug("Background work rejected", e);
            handle.cancel();
        }
        return handle;
    }

    private static final class Handle implements Cancellable {

        private final AtomicBoolean cancelled = new AtomicBoolean();

        <T> void execute(Callable<? extends T> work, Consumer<? super T> onSuccess, Consumer<? super UserMessage> onError) {
            if (cancelled.get()) {
                return;
            }
            T result;
            try {
                result = work.call();
            } catch (CancellationException e) {
                return;
            } catch (Throwable e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                LOG.warn("Background work failed", e);
                UserMessage message = LearnSphereException.userMessageOf(e);
                Fx.run(() -> {
                    if (!cancelled.get()) {
                        onError.accept(message);
                    }
                });
                return;
            }
            Fx.run(() -> {
                if (!cancelled.get()) {
                    onSuccess.accept(result);
                }
            });
        }

        @Override
        public void cancel() {
            cancelled.set(true);
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get();
        }
    }
}
