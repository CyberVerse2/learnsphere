package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.AiRuntime;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.core.util.Subscription;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * An AI runtime that is always ready with the 4B chat model (both chat models are offered). Tests can
 * force any state with {@link #setStatus}. Listeners are called synchronously on the calling thread.
 */
public final class FakeAiRuntime implements AiRuntime {

    private final List<Consumer<AiStatus>> listeners = new CopyOnWriteArrayList<>();
    private final Set<ModelChoice> available;
    private volatile AiStatus status;

    public FakeAiRuntime() {
        this(EnumSet.of(ModelChoice.CHAT_4B, ModelChoice.CHAT_9B));
    }

    public FakeAiRuntime(Set<ModelChoice> available) {
        this.available = Set.copyOf(available);
        this.status = new AiStatus(AiStatus.State.READY, ModelChoice.CHAT_4B, null);
    }

    @Override
    public void start(ModelChoice choice) {
        ModelChoice model = choice == null || choice == ModelChoice.AUTO ? recommendedModel() : choice;
        setStatus(new AiStatus(AiStatus.State.READY, model, null));
    }

    @Override
    public void stop() {
        setStatus(new AiStatus(AiStatus.State.UNAVAILABLE, null, "stopped"));
    }

    @Override
    public AiStatus status() {
        return status;
    }

    /** Forces a status and notifies listeners. */
    public void setStatus(AiStatus newStatus) {
        status = Objects.requireNonNull(newStatus, "newStatus");
        for (Consumer<AiStatus> listener : listeners) {
            listener.accept(newStatus);
        }
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
}
