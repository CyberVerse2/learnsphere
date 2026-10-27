package edu.learnsphere.core.events;

import edu.learnsphere.core.util.Subscription;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers {@link Event}s to subscribers on one background daemon thread, in the order they were
 * published. Publishing never blocks on subscribers. A subscriber that throws is logged and does not
 * affect others. Subscribers that touch JavaFX marshal to the FX thread themselves ({@code Fx.run}).
 *
 * <p>Thread-safe. After {@link #close()}, published events are dropped.
 */
public final class EventBus implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(EventBus.class);

    private final List<Registration<?>> registrations = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "learnsphere-events");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Calls {@code listener} for every published event of {@code type} (including subtypes).
     *
     * @return a subscription; close it to stop receiving events
     */
    public <E extends Event> Subscription subscribe(Class<E> type, Consumer<? super E> listener) {
        Registration<E> registration = new Registration<>(Objects.requireNonNull(type, "type"),
                Objects.requireNonNull(listener, "listener"));
        registrations.add(registration);
        return () -> {
            registration.active = false;
            registrations.remove(registration);
        };
    }

    /** Calls {@code listener} for every published event. */
    public Subscription subscribeAll(Consumer<? super Event> listener) {
        return subscribe(Event.class, listener);
    }

    /** Queues {@code event} for delivery and returns immediately. */
    public void publish(Event event) {
        Objects.requireNonNull(event, "event");
        try {
            executor.execute(() -> deliver(event));
        } catch (RejectedExecutionException e) {
            LOG.debug("Event bus closed; dropping {}", event);
        }
    }

    /**
     * Waits until every event published before this call has been delivered (tests and shutdown).
     *
     * @return false when the timeout elapsed first
     */
    public boolean awaitIdle(Duration timeout) {
        CountDownLatch latch = new CountDownLatch(1);
        try {
            executor.execute(latch::countDown);
            return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Number of active subscriptions. */
    public int subscriberCount() {
        return registrations.size();
    }

    private void deliver(Event event) {
        for (Registration<?> registration : registrations) {
            registration.offer(event);
        }
    }

    /** Stops delivery; events still queued are delivered first, waiting at most a second. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        registrations.clear();
    }

    private static final class Registration<E extends Event> {

        private final Class<E> type;
        private final Consumer<? super E> listener;
        private volatile boolean active = true;

        Registration(Class<E> type, Consumer<? super E> listener) {
            this.type = type;
            this.listener = listener;
        }

        void offer(Event event) {
            if (!active || !type.isInstance(event)) {
                return;
            }
            try {
                listener.accept(type.cast(event));
            } catch (RuntimeException e) {
                LOG.warn("Event listener failed for {}", event, e);
            }
        }
    }
}
