package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.Subscription;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The local AI processes (implemented in the ai module by {@code LlamaServerManager}). {@link #start}
 * returns immediately; progress is reported through {@link #status()} and listeners.
 */
public interface AiRuntime extends AutoCloseable {

    /** Starts (or restarts with another model) every AI process in the background. */
    void start(ModelChoice choice);

    /** Stops every AI process. */
    void stop();

    /** Current state. */
    AiStatus status();

    /** Chat models whose files are present and that this machine can run. */
    Set<ModelChoice> availableModels();

    /** What {@link ModelChoice#AUTO} resolves to on this machine. */
    ModelChoice recommendedModel();

    /** Calls {@code listener} (on a background thread) whenever the status changes. */
    Subscription addListener(Consumer<AiStatus> listener);

    /** Stops every process and releases resources. */
    @Override
    void close();
}
