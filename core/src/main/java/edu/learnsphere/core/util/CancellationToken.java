package edu.learnsphere.core.util;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cooperative cancellation shared between the caller and long-running work. Work checks
 * {@link #isCancelled()} or calls {@link #throwIfCancelled()} at safe points, and can register
 * {@link #onCancel(Runnable) callbacks} to abort blocking calls such as HTTP requests.
 *
 * <p>Thread-safe. {@link #NONE} is never cancelled and ignores {@link #cancel()}.
 */
public final class CancellationToken implements Cancellable {

    private static final Logger LOG = LoggerFactory.getLogger(CancellationToken.class);

    /** A token that is never cancelled, for callers that do not need cancellation. */
    public static final CancellationToken NONE = new CancellationToken(false);

    private final boolean cancellable;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    private CancellationToken(boolean cancellable) {
        this.cancellable = cancellable;
    }

    /** A new token that is not yet cancelled. */
    public static CancellationToken create() {
        return new CancellationToken(true);
    }

    /**
     * A new token that is cancelled when this one is, and can also be cancelled on its own without
     * affecting this one.
     */
    public CancellationToken child() {
        CancellationToken child = create();
        Subscription link = onCancel(child::cancel);
        child.onCancel(link::close);
        return child;
    }

    @Override
    public void cancel() {
        if (!cancellable || !cancelled.compareAndSet(false, true)) {
            return;
        }
        // Run only the callbacks this call removes: onCancel may race to remove (and run) one itself.
        for (Runnable callback : callbacks) {
            if (callbacks.remove(callback)) {
                run(callback);
            }
        }
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Throws when cancelled.
     *
     * @throws CancellationException when {@link #cancel()} has been called
     */
    public void throwIfCancelled() {
        if (cancelled.get()) {
            throw new CancellationException("Cancelled");
        }
    }

    /**
     * Runs {@code callback} once when the token is cancelled, or immediately on the calling thread if it
     * already is. Callbacks run on the thread that calls {@link #cancel()} and must be quick.
     *
     * @return a subscription that removes the callback
     */
    public Subscription onCancel(Runnable callback) {
        if (!cancellable) {
            return Subscription.NONE;
        }
        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            run(callback);
        }
        return () -> callbacks.remove(callback);
    }

    private static void run(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            LOG.warn("Cancellation callback failed", e);
        }
    }
}
