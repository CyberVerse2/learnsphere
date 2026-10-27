package edu.learnsphere.app.ui;

import edu.learnsphere.core.util.Cancellable;
import edu.learnsphere.core.util.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects what a {@link View} must release when it goes away (event subscriptions, running loads) and
 * releases all of it in {@link #dispose()}. Anything added after disposal is released immediately, so a
 * load that finishes late cannot leak a subscription. Thread-safe.
 *
 * <pre>{@code
 * private final Disposer disposer = new Disposer();
 *
 * public void onShown() {
 *     disposer.add(UiTasks.run(() -> workspace.courses().list(CourseFilter.studying()), this::show, this::showError));
 *     disposer.add(Fx.subscribe(workspace.events(), CourseChanged.class, event -> reload()));
 * }
 *
 * public void dispose() {
 *     disposer.dispose();
 * }
 * }</pre>
 */
public final class Disposer {

    private static final Logger LOG = LoggerFactory.getLogger(Disposer.class);

    private final List<Runnable> actions = new ArrayList<>();
    private boolean disposed;

    /** Closes {@code subscription} on disposal; returns it. */
    public Subscription add(Subscription subscription) {
        Objects.requireNonNull(subscription, "subscription");
        onDispose(subscription::close);
        return subscription;
    }

    /** Cancels {@code work} on disposal; returns it. */
    public Cancellable add(Cancellable work) {
        Objects.requireNonNull(work, "work");
        onDispose(work::cancel);
        return work;
    }

    /** Runs {@code action} on disposal (now, when already disposed). */
    public void onDispose(Runnable action) {
        Objects.requireNonNull(action, "action");
        boolean runNow;
        synchronized (this) {
            runNow = disposed;
            if (!disposed) {
                actions.add(action);
            }
        }
        if (runNow) {
            run(action);
        }
    }

    /** True once {@link #dispose()} has run. */
    public synchronized boolean isDisposed() {
        return disposed;
    }

    /** Releases everything, newest first. Idempotent. */
    public void dispose() {
        List<Runnable> toRun;
        synchronized (this) {
            if (disposed) {
                return;
            }
            disposed = true;
            toRun = new ArrayList<>(actions);
            actions.clear();
        }
        for (int i = toRun.size() - 1; i >= 0; i--) {
            run(toRun.get(i));
        }
    }

    private static void run(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.warn("Releasing a view resource failed", e);
        }
    }
}
