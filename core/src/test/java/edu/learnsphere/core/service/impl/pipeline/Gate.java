package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.util.CancellationToken;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stops a fake study-assistant call at its {@code n}-th invocation until the job is cancelled, to interrupt a
 * job at a known point. Calls before the {@code n}-th pass through.
 */
final class Gate {

    private final int blockAt;
    private final AtomicInteger calls = new AtomicInteger();
    private final CountDownLatch reached = new CountDownLatch(1);

    Gate(int blockAt) {
        this.blockAt = blockAt;
    }

    /** Counts a call; the {@code blockAt}-th one waits for {@code token} to be cancelled and then throws. */
    void pass(CancellationToken token) {
        if (calls.incrementAndGet() != blockAt) {
            return;
        }
        reached.countDown();
        long deadline = System.nanoTime() + PipelineFixture.TIMEOUT.toNanos();
        while (!token.isCancelled() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new CancellationException("Stopped at the gate");
    }

    int calls() {
        return calls.get();
    }

    /** Waits until a call is held at the gate. */
    void awaitReached() {
        try {
            if (!reached.await(PipelineFixture.TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new AssertionError("The job never reached the gate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
