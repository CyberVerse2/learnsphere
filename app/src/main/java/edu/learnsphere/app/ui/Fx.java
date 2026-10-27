package edu.learnsphere.app.ui;

import edu.learnsphere.core.events.Event;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.util.Subscription;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javafx.application.Platform;

/** FX-thread helpers. */
public final class Fx {

    private Fx() {
    }

    /** Runs {@code action} on the FX thread: now when already on it, otherwise as soon as possible. */
    public static void run(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
        } else {
            Platform.runLater(action);
        }
    }

    /** Fails fast when called off the FX thread. */
    public static void requireFxThread() {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Not on the FX application thread: " + Thread.currentThread().getName());
        }
    }

    /**
     * Calls {@code onFx} on the FX thread for every event of {@code type} published on {@code bus}, until the
     * returned subscription is closed (add it to the view's {@link Disposer}). Nothing is delivered after
     * closing, even events already on their way.
     *
     * <p>The event bus delivers every event on one thread, so the bus-side work here only hands the event to
     * the FX thread. {@code onFx} must not block either: to reload data, start a {@link UiTasks#run}.
     */
    public static <E extends Event> Subscription subscribe(EventBus bus, Class<E> type, Consumer<? super E> onFx) {
        Objects.requireNonNull(bus, "bus");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(onFx, "onFx");
        AtomicBoolean active = new AtomicBoolean(true);
        Subscription inner = bus.subscribe(type, event -> Platform.runLater(() -> {
            if (active.get()) {
                onFx.accept(event);
            }
        }));
        return () -> {
            active.set(false);
            inner.close();
        };
    }
}
