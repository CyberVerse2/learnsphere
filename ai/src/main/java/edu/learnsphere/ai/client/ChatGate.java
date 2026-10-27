package edu.learnsphere.ai.client;

import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Subscription;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Admits requests to the chat server one at a time (or {@code permits} at a time), in
 * {@link ChatPriority} order: {@code INTERACTIVE} before {@code USER_WAITING} before {@code BACKGROUND},
 * first come first served within a priority.
 *
 * <p>Cancelling a waiter's token removes it from the queue ({@link #acquire} throws
 * {@link CancellationException}). When an {@code INTERACTIVE} request arrives while a {@code BACKGROUND}
 * request holds the chat server, the holder's {@link Permit#preemption()} token is cancelled so it can stop,
 * release its permit and queue again behind the interactive request; background work never streams to a
 * user, so it can safely start over.
 *
 * <p>Thread-safe.
 */
public final class ChatGate {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private final PriorityQueue<Waiter> queue = new PriorityQueue<>(
            Comparator.comparingInt((Waiter w) -> w.priority.ordinal()).thenComparingLong(w -> w.sequence));
    private final List<Permit> holders = new ArrayList<>();
    private int available;
    private long sequence;

    /** A gate for a chat server with one slot ({@code --parallel 1}). */
    public ChatGate() {
        this(1);
    }

    /** A gate admitting {@code permits} requests at once. */
    public ChatGate(int permits) {
        if (permits < 1) {
            throw new IllegalArgumentException("permits must be >= 1: " + permits);
        }
        this.available = permits;
    }

    private static final class Waiter {
        final ChatPriority priority;
        final long sequence;
        boolean cancelled;

        Waiter(ChatPriority priority, long sequence) {
            this.priority = priority;
            this.sequence = sequence;
        }
    }

    /**
     * Blocks until the caller may use the chat server. Close the permit when done.
     *
     * @throws CancellationException when {@code token} is cancelled (or the thread interrupted) while waiting
     */
    public Permit acquire(ChatPriority priority, CancellationToken token) {
        Objects.requireNonNull(priority, "priority");
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        lock.lock();
        try {
            Waiter waiter = new Waiter(priority, sequence++);
            queue.add(waiter);
            Subscription wake = token.onCancel(() -> {
                lock.lock();
                try {
                    waiter.cancelled = true;
                    changed.signalAll();
                } finally {
                    lock.unlock();
                }
            });
            try {
                preemptFor(waiter);
                while (true) {
                    if (waiter.cancelled) {
                        queue.remove(waiter);
                        changed.signalAll();
                        throw new CancellationException("Cancelled while waiting for the chat server");
                    }
                    if (available > 0 && queue.peek() == waiter) {
                        queue.poll();
                        available--;
                        Permit permit = new Permit(priority);
                        holders.add(permit);
                        // The next waiter may be admitted too when more than one permit is free.
                        changed.signalAll();
                        return permit;
                    }
                    try {
                        changed.await();
                    } catch (InterruptedException e) {
                        queue.remove(waiter);
                        changed.signalAll();
                        Thread.currentThread().interrupt();
                        throw new CancellationException("Interrupted while waiting for the chat server");
                    }
                }
            } finally {
                wake.close();
            }
        } finally {
            lock.unlock();
        }
    }

    /** Requests waiting for a permit. */
    public int waiting() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /** Requests holding a permit. */
    public int running() {
        lock.lock();
        try {
            return holders.size();
        } finally {
            lock.unlock();
        }
    }

    private void preemptFor(Waiter waiter) {
        if (waiter.priority != ChatPriority.INTERACTIVE || available > 0) {
            return;
        }
        for (Permit holder : holders) {
            if (holder.priority == ChatPriority.BACKGROUND) {
                holder.preemption.cancel();
            }
        }
    }

    private void release(Permit permit) {
        lock.lock();
        try {
            if (holders.remove(permit)) {
                available++;
                changed.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    /** The right to use the chat server until {@link #close()}. */
    public final class Permit implements AutoCloseable {

        private final ChatPriority priority;
        private final CancellationToken preemption = CancellationToken.create();
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(ChatPriority priority) {
            this.priority = priority;
        }

        /** The priority it was acquired with. */
        public ChatPriority priority() {
            return priority;
        }

        /** Cancelled when a more urgent request needs the chat server (background permits only). */
        public CancellationToken preemption() {
            return preemption;
        }

        /** True when this permit's holder was asked to make way. */
        public boolean isPreempted() {
            return preemption.isCancelled();
        }

        /** Releases the chat server to the next waiter. Idempotent. */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                release(this);
            }
        }
    }
}
