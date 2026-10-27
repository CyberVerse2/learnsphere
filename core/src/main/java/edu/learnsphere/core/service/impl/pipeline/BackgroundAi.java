package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.service.AssistantService;
import edu.learnsphere.core.service.AssistantState;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Subscription;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs study-assistant requests for background jobs (imports and generation), where nobody watches each
 * request. A request that fails with {@link UnavailableException} while the assistant is starting (for
 * example right after the profile opens, or while it restarts after a crash) waits until the assistant is
 * ready and is tried again; when the assistant cannot run, or does not become ready in time, the failure is
 * passed on. Answering a question does not use this: the learner is told the assistant's state at once.
 *
 * <p>Thread-safe. Waiting never interrupts the thread; it ends early when the job's token is cancelled.
 */
final class BackgroundAi {

    private static final Logger LOG = LoggerFactory.getLogger(BackgroundAi.class);

    /** How long a job waits for a starting assistant before giving up. */
    static final Duration DEFAULT_MAX_WAIT = Duration.ofMinutes(10);

    /** How often the assistant's state is checked while waiting. */
    static final Duration POLL_INTERVAL = Duration.ofMillis(250);

    /** Tries per request, the first included. */
    static final int MAX_ATTEMPTS = 4;

    private final AssistantService assistant;
    private final Duration maxWait;

    BackgroundAi(AssistantService assistant) {
        this(assistant, DEFAULT_MAX_WAIT);
    }

    BackgroundAi(AssistantService assistant, Duration maxWait) {
        this.assistant = Objects.requireNonNull(assistant, "assistant");
        this.maxWait = Objects.requireNonNull(maxWait, "maxWait");
    }

    /**
     * Runs {@code request}, waiting for the assistant and trying again when it reports that it is not ready.
     *
     * @throws UnavailableException  when the assistant cannot run or stays unavailable
     * @throws CancellationException when {@code token} is cancelled
     */
    <T> T call(Supplier<T> request, CancellationToken token) {
        for (int attempt = 1; ; attempt++) {
            token.throwIfCancelled();
            try {
                return request.get();
            } catch (UnavailableException e) {
                if (attempt >= MAX_ATTEMPTS || !awaitReady(token)) {
                    throw e;
                }
                LOG.debug("Study assistant was not ready; trying again (attempt {})", attempt + 1);
            }
        }
    }

    /**
     * Waits while the assistant is starting. Returns true once it reports ready (after a short pause, so a
     * process that is still coming up gets a moment), false when it cannot run or the wait timed out.
     */
    boolean awaitReady(CancellationToken token) {
        long deadline = System.nanoTime() + maxWait.toNanos();
        while (true) {
            token.throwIfCancelled();
            AssistantState state = assistant.status().state();
            if (state == AssistantState.READY) {
                sleep(POLL_INTERVAL, token);
                return true;
            }
            if (state != AssistantState.STARTING || System.nanoTime() >= deadline) {
                return false;
            }
            sleep(POLL_INTERVAL, token);
        }
    }

    /** Sleeps for {@code duration}, waking early (and throwing) when {@code token} is cancelled. */
    static void sleep(Duration duration, CancellationToken token) {
        CountDownLatch latch = new CountDownLatch(1);
        Subscription wakeUp = token.onCancel(latch::countDown);
        try {
            latch.await(duration.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting for the study assistant");
        } finally {
            wakeUp.close();
        }
        token.throwIfCancelled();
    }
}
