package edu.learnsphere.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CancellationTokenTest {

    @Test
    void cancelsOnceAndRunsCallbacks() {
        CancellationToken token = CancellationToken.create();
        AtomicInteger calls = new AtomicInteger();
        token.onCancel(calls::incrementAndGet);
        assertThat(token.isCancelled()).isFalse();
        token.throwIfCancelled();

        token.cancel();
        token.cancel();

        assertThat(token.isCancelled()).isTrue();
        assertThat(calls).hasValue(1);
        assertThatThrownBy(token::throwIfCancelled).isInstanceOf(CancellationException.class);
    }

    @Test
    void callbacksRegisteredAfterCancelRunImmediately() {
        CancellationToken token = CancellationToken.create();
        token.cancel();
        AtomicInteger calls = new AtomicInteger();
        token.onCancel(calls::incrementAndGet);
        assertThat(calls).hasValue(1);
    }

    @Test
    void closedCallbacksDoNotRun() {
        CancellationToken token = CancellationToken.create();
        AtomicInteger calls = new AtomicInteger();
        token.onCancel(calls::incrementAndGet).close();
        token.cancel();
        assertThat(calls).hasValue(0);
    }

    @Test
    void childrenFollowTheirParent() {
        CancellationToken parent = CancellationToken.create();
        CancellationToken child = parent.child();
        child.cancel();
        assertThat(parent.isCancelled()).isFalse();

        CancellationToken other = parent.child();
        parent.cancel();
        assertThat(other.isCancelled()).isTrue();
    }

    @Test
    void aCallbackRacingWithCancelRunsExactlyOnce() throws Exception {
        for (int round = 0; round < 2_000; round++) {
            CancellationToken token = CancellationToken.create();
            AtomicInteger calls = new AtomicInteger();
            CountDownLatch go = new CountDownLatch(1);
            Thread canceller = Thread.ofPlatform().start(() -> {
                await(go);
                token.cancel();
            });
            Thread registrar = Thread.ofPlatform().start(() -> {
                await(go);
                token.onCancel(calls::incrementAndGet);
            });
            go.countDown();
            canceller.join();
            registrar.join();
            assertThat(calls).as("round %d", round).hasValue(1);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void noneIsNeverCancelled() {
        CancellationToken.NONE.cancel();
        assertThat(CancellationToken.NONE.isCancelled()).isFalse();
    }
}
