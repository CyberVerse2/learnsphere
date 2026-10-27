package edu.learnsphere.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class ChatGateTest {

    private static void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met in time");
            }
            Thread.sleep(5);
        }
    }

    /** Queues a waiter that records its name when admitted and releases at once. */
    private static CompletableFuture<Void> queue(ChatGate gate, ChatPriority priority, String name, List<String> order,
                                                 CancellationToken token) {
        return CompletableFuture.runAsync(() -> {
            ChatGate.Permit permit = gate.acquire(priority, token);
            try {
                order.add(name);
            } finally {
                permit.close();
            }
        });
    }

    @Test
    void admitsByPriorityThenArrival() throws Exception {
        ChatGate gate = new ChatGate();
        List<String> order = Collections.synchronizedList(new ArrayList<>());
        ChatGate.Permit holder = gate.acquire(ChatPriority.USER_WAITING, CancellationToken.NONE);

        List<CompletableFuture<Void>> waiters = new ArrayList<>();
        String[][] arrivals = {{"BACKGROUND", "b1"}, {"USER_WAITING", "u1"}, {"BACKGROUND", "b2"},
                {"INTERACTIVE", "i1"}, {"USER_WAITING", "u2"}, {"INTERACTIVE", "i2"}};
        for (String[] arrival : arrivals) {
            int before = gate.waiting();
            waiters.add(queue(gate, ChatPriority.valueOf(arrival[0]), arrival[1], order, CancellationToken.NONE));
            waitUntil(() -> gate.waiting() == before + 1);
        }
        holder.close();
        CompletableFuture.allOf(waiters.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);

        assertThat(order).containsExactly("i1", "i2", "u1", "u2", "b1", "b2");
        assertThat(gate.running()).isZero();
        assertThat(gate.waiting()).isZero();
    }

    @Test
    void cancellingAWaiterRemovesItFromTheQueue() throws Exception {
        ChatGate gate = new ChatGate();
        ChatGate.Permit holder = gate.acquire(ChatPriority.INTERACTIVE, CancellationToken.NONE);
        CancellationToken token = CancellationToken.create();
        CompletableFuture<ChatGate.Permit> waiting = CompletableFuture.supplyAsync(
                () -> gate.acquire(ChatPriority.USER_WAITING, token));
        waitUntil(() -> gate.waiting() == 1);

        token.cancel();

        assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
        assertThat(gate.waiting()).isZero();
        holder.close();
        // The gate is free again.
        try (ChatGate.Permit next = gate.acquire(ChatPriority.BACKGROUND, CancellationToken.NONE)) {
            assertThat(next.priority()).isEqualTo(ChatPriority.BACKGROUND);
        }
    }

    @Test
    void anInteractiveRequestAsksABackgroundHolderToMakeWay() throws Exception {
        ChatGate gate = new ChatGate();
        ChatGate.Permit background = gate.acquire(ChatPriority.BACKGROUND, CancellationToken.NONE);
        assertThat(background.isPreempted()).isFalse();

        CompletableFuture<ChatGate.Permit> interactive = CompletableFuture.supplyAsync(
                () -> gate.acquire(ChatPriority.INTERACTIVE, CancellationToken.NONE));
        waitUntil(background::isPreempted);
        background.close();

        try (ChatGate.Permit permit = interactive.get(5, TimeUnit.SECONDS)) {
            assertThat(permit.priority()).isEqualTo(ChatPriority.INTERACTIVE);
            assertThat(permit.isPreempted()).isFalse();
        }
    }

    @Test
    void userWaitingRequestsDoNotPreempt() throws Exception {
        ChatGate gate = new ChatGate();
        ChatGate.Permit background = gate.acquire(ChatPriority.BACKGROUND, CancellationToken.NONE);
        CompletableFuture<ChatGate.Permit> waiting = CompletableFuture.supplyAsync(
                () -> gate.acquire(ChatPriority.USER_WAITING, CancellationToken.NONE));
        waitUntil(() -> gate.waiting() == 1);
        Thread.sleep(50);
        assertThat(background.isPreempted()).isFalse();
        background.close();
        waiting.get(5, TimeUnit.SECONDS).close();
    }

    @Test
    void interactiveHoldersAreNeverPreempted() throws Exception {
        ChatGate gate = new ChatGate();
        ChatGate.Permit first = gate.acquire(ChatPriority.INTERACTIVE, CancellationToken.NONE);
        CompletableFuture<ChatGate.Permit> second = CompletableFuture.supplyAsync(
                () -> gate.acquire(ChatPriority.INTERACTIVE, CancellationToken.NONE));
        waitUntil(() -> gate.waiting() == 1);
        assertThat(first.isPreempted()).isFalse();
        first.close();
        second.get(5, TimeUnit.SECONDS).close();
    }

    @Test
    void closingAPermitTwiceReleasesOnce() throws Exception {
        ChatGate gate = new ChatGate(2);
        ChatGate.Permit a = gate.acquire(ChatPriority.BACKGROUND, CancellationToken.NONE);
        ChatGate.Permit b = gate.acquire(ChatPriority.BACKGROUND, CancellationToken.NONE);
        assertThat(gate.running()).isEqualTo(2);
        a.close();
        a.close();
        assertThat(gate.running()).isEqualTo(1);
        ChatGate.Permit c = gate.acquire(ChatPriority.BACKGROUND, CancellationToken.NONE);
        CompletableFuture<ChatGate.Permit> d = CompletableFuture.supplyAsync(
                () -> gate.acquire(ChatPriority.BACKGROUND, CancellationToken.NONE));
        waitUntil(() -> gate.waiting() == 1);
        b.close();
        d.get(5, TimeUnit.SECONDS).close();
        c.close();
        assertThat(gate.running()).isZero();
    }

    @Test
    void aCancelledTokenIsRefusedAtOnce() {
        ChatGate gate = new ChatGate();
        CancellationToken token = CancellationToken.create();
        token.cancel();
        assertThatThrownBy(() -> gate.acquire(ChatPriority.INTERACTIVE, token)).isInstanceOf(CancellationException.class);
        assertThat(gate.waiting()).isZero();
    }
}
