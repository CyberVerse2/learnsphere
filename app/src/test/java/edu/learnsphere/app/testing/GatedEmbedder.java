package edu.learnsphere.app.testing;

import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.util.CancellationToken;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An embedder that holds every call until the test calls {@link #release()}, so a PDF import can be caught
 * while its passages are being made searchable (for example to quit the app half way). A held call still
 * stops when its job is cancelled, as the real one does when the app quits.
 */
public final class GatedEmbedder implements Embedder {

    private final Embedder delegate;
    private final CountDownLatch entered = new CountDownLatch(1);
    private final AtomicInteger cancelled = new AtomicInteger();
    private volatile boolean released;

    public GatedEmbedder(Embedder delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public List<float[]> embed(List<String> texts, EmbedKind kind, CancellationToken token) {
        entered.countDown();
        while (!released) {
            if (token.isCancelled()) {
                cancelled.incrementAndGet();
                throw new CancellationException("Cancelled while held");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled.incrementAndGet();
                throw new CancellationException("Interrupted while held");
            }
        }
        return delegate.embed(texts, kind, token);
    }

    /** Waits until a call is being held, failing after {@code timeout}. */
    public void awaitHeld(Duration timeout) {
        try {
            if (!entered.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("Nothing reached the embedder within " + timeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted", e);
        }
    }

    /** Lets every held and later call through. */
    public void release() {
        released = true;
    }

    /** Held calls that stopped because their job was cancelled. */
    public int cancelledCalls() {
        return cancelled.get();
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    @Override
    public String modelName() {
        return delegate.modelName();
    }
}
