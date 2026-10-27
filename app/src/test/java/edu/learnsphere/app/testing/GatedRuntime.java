package edu.learnsphere.app.testing;

import edu.learnsphere.core.spi.AiRuntime;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.core.util.Subscription;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * A study assistant runtime that stays "getting ready" after {@link #start} until the test calls
 * {@link #release()}, {@link #unavailable()} or {@link #fail()}, so a test can see each state on screen.
 * Listeners are called on the calling thread, as the real runtime's monitor thread calls them.
 */
public final class GatedRuntime implements AiRuntime {

    private final List<Consumer<AiStatus>> listeners = new CopyOnWriteArrayList<>();
    private final Set<ModelChoice> available;
    private final AtomicInteger starts = new AtomicInteger();
    private final AtomicInteger stops = new AtomicInteger();
    private volatile AiStatus status = new AiStatus(AiStatus.State.UNAVAILABLE, null, "not started");
    private volatile ModelChoice requested = ModelChoice.CHAT_4B;

    /** Both answer sizes available, the quick one recommended. */
    public GatedRuntime() {
        this(EnumSet.of(ModelChoice.CHAT_4B, ModelChoice.CHAT_9B));
    }

    public GatedRuntime(Set<ModelChoice> available) {
        this.available = Set.copyOf(available);
    }

    @Override
    public void start(ModelChoice choice) {
        starts.incrementAndGet();
        requested = choice == null || choice == ModelChoice.AUTO ? recommendedModel() : choice;
        set(new AiStatus(AiStatus.State.STARTING, requested, null));
    }

    /** Finishes getting ready. */
    public void release() {
        set(new AiStatus(AiStatus.State.READY, requested, null));
    }

    /** This computer can't run it. */
    public void unavailable() {
        set(new AiStatus(AiStatus.State.UNAVAILABLE, null, "not enough memory"));
    }

    /** It stopped unexpectedly. */
    public void fail() {
        set(new AiStatus(AiStatus.State.FAILED, requested, "exited"));
    }

    @Override
    public void stop() {
        stops.incrementAndGet();
        set(new AiStatus(AiStatus.State.UNAVAILABLE, null, "stopped"));
    }

    /** How many times it was started (restarts included). */
    public int starts() {
        return starts.get();
    }

    /** How many times it was stopped. */
    public int stops() {
        return stops.get();
    }

    @Override
    public AiStatus status() {
        return status;
    }

    @Override
    public Set<ModelChoice> availableModels() {
        return available;
    }

    @Override
    public ModelChoice recommendedModel() {
        return ModelChoice.CHAT_4B;
    }

    @Override
    public Subscription addListener(Consumer<AiStatus> listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
        return () -> listeners.remove(listener);
    }

    @Override
    public void close() {
        listeners.clear();
    }

    private void set(AiStatus next) {
        status = next;
        for (Consumer<AiStatus> listener : listeners) {
            listener.accept(next);
        }
    }
}
