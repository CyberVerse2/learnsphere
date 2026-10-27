package edu.learnsphere.core.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CancellationException;
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
    void noneIsNeverCancelled() {
        CancellationToken.NONE.cancel();
        assertThat(CancellationToken.NONE.isCancelled()).isFalse();
    }
}
