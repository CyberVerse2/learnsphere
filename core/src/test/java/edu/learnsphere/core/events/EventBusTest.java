package edu.learnsphere.core.events;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.util.Subscription;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EventBusTest {

    private final EventBus bus = new EventBus();

    @AfterEach
    void close() {
        bus.close();
    }

    @Test
    void deliversEventsByTypeInPublishOrder() {
        List<Event> courses = new CopyOnWriteArrayList<>();
        List<Event> all = new CopyOnWriteArrayList<>();
        bus.subscribe(CourseChanged.class, courses::add);
        bus.subscribeAll(all::add);

        CourseChanged first = new CourseChanged("c1", ChangeType.CREATED);
        SettingChanged setting = new SettingChanged("theme");
        CourseChanged second = new CourseChanged("c1", ChangeType.UPDATED);
        bus.publish(first);
        bus.publish(setting);
        bus.publish(second);

        assertThat(bus.awaitIdle(Duration.ofSeconds(5))).isTrue();
        assertThat(courses).containsExactly(first, second);
        assertThat(all).containsExactly(first, setting, second);
    }

    @Test
    void deliversOnTheEventThread() {
        List<String> threads = new CopyOnWriteArrayList<>();
        bus.subscribeAll(event -> threads.add(Thread.currentThread().getName()));
        bus.publish(new ProgressChanged(null));
        bus.awaitIdle(Duration.ofSeconds(5));
        assertThat(threads).containsExactly("learnsphere-events");
    }

    @Test
    void closingASubscriptionStopsDelivery() {
        List<Event> seen = new CopyOnWriteArrayList<>();
        Subscription subscription = bus.subscribe(SettingChanged.class, seen::add);
        assertThat(bus.subscriberCount()).isEqualTo(1);
        subscription.close();
        assertThat(bus.subscriberCount()).isZero();

        bus.publish(new SettingChanged("theme"));
        bus.awaitIdle(Duration.ofSeconds(5));
        assertThat(seen).isEmpty();
    }

    @Test
    void aFailingListenerDoesNotAffectOthers() {
        List<Event> seen = new CopyOnWriteArrayList<>();
        bus.subscribeAll(event -> {
            throw new IllegalStateException("boom");
        });
        bus.subscribeAll(seen::add);

        bus.publish(new SettingChanged("a"));
        bus.publish(new SettingChanged("b"));
        bus.awaitIdle(Duration.ofSeconds(5));
        assertThat(seen).hasSize(2);
    }

    @Test
    void dropsEventsAfterClose() {
        List<Event> seen = new CopyOnWriteArrayList<>();
        bus.subscribeAll(seen::add);
        bus.close();
        bus.publish(new SettingChanged("late"));
        assertThat(bus.awaitIdle(Duration.ofMillis(100))).isTrue();
        assertThat(seen).isEmpty();
    }
}
