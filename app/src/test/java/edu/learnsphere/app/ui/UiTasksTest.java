package edu.learnsphere.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.util.Cancellable;
import edu.learnsphere.core.util.Subscription;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javafx.application.Platform;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;
import org.testfx.util.WaitForAsyncUtils;

class UiTasksTest {

    @BeforeAll
    static void startFx() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @Test
    void handsTheResultToTheFxThread() throws Exception {
        CompletableFuture<Boolean> onFx = new CompletableFuture<>();
        CompletableFuture<Integer> result = new CompletableFuture<>();

        UiTasks.run(() -> 6 * 7, value -> {
            onFx.complete(Platform.isFxApplicationThread());
            result.complete(value);
        }, message -> result.completeExceptionally(new AssertionError(message.text())));

        assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(42);
        assertThat(onFx.get()).isTrue();
    }

    @Test
    void reportsFailuresAsTheirUserMessageOnly() throws Exception {
        CompletableFuture<UserMessage> known = new CompletableFuture<>();
        CompletableFuture<UserMessage> unexpected = new CompletableFuture<>();

        UiTasks.run(() -> {
            throw new InvalidInputException(UserMessage.WRONG_PASSWORD, "hash mismatch");
        }, value -> known.complete(null), known::complete);
        UiTasks.run(() -> {
            throw new IllegalStateException("/Users/someone/secret.db is locked");
        }, value -> unexpected.complete(null), unexpected::complete);

        assertThat(known.get(5, TimeUnit.SECONDS)).isEqualTo(UserMessage.WRONG_PASSWORD);
        assertThat(unexpected.get(5, TimeUnit.SECONDS)).isEqualTo(UserMessage.SOMETHING_WENT_WRONG);
    }

    @Test
    void dropsTheOutcomeOfCancelledWork() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        List<String> outcomes = new CopyOnWriteArrayList<>();

        Cancellable work = UiTasks.run(() -> {
            release.await(5, TimeUnit.SECONDS);
            return "late";
        }, outcomes::add, message -> outcomes.add(message.text()));
        work.cancel();
        release.countDown();

        assertThat(UiExecutors.awaitIdle(Duration.ofSeconds(5))).isTrue();
        WaitForAsyncUtils.waitForFxEvents();
        assertThat(work.isCancelled()).isTrue();
        assertThat(outcomes).isEmpty();
    }

    @Test
    void subscriptionsDeliverOnTheFxThreadUntilClosed() throws Exception {
        try (EventBus bus = new EventBus()) {
            List<String> received = new CopyOnWriteArrayList<>();
            CompletableFuture<Boolean> onFx = new CompletableFuture<>();
            Subscription subscription = Fx.subscribe(bus, CourseChanged.class, event -> {
                onFx.complete(Platform.isFxApplicationThread());
                received.add(event.courseId());
            });

            bus.publish(new CourseChanged("a", ChangeType.UPDATED));
            assertThat(onFx.get(5, TimeUnit.SECONDS)).isTrue();

            subscription.close();
            bus.publish(new CourseChanged("b", ChangeType.UPDATED));
            bus.awaitIdle(Duration.ofSeconds(5));
            WaitForAsyncUtils.waitForFxEvents();

            assertThat(received).containsExactly("a");
            assertThat(bus.subscriberCount()).isZero();
        }
    }
}
