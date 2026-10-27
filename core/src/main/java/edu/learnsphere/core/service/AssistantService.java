package edu.learnsphere.core.service;

import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.core.util.Subscription;
import java.util.List;
import java.util.function.Consumer;

/**
 * The local study assistant's readiness. Status changes also publish
 * {@link edu.learnsphere.core.events.AssistantStatusChanged}.
 */
public interface AssistantService {

    /** Current readiness. */
    AssistantStatus status();

    /** Restarts the assistant in the background. */
    void restart();

    /** The model setting (may be {@link ModelChoice#AUTO}). */
    ModelChoice modelChoice();

    /** Stores the model setting and restarts with it when it changes the model in use. */
    void setModelChoice(ModelChoice choice);

    /** Options for the Settings screen: automatic, then each chat model. */
    List<ModelOption> modelOptions();

    /** Calls {@code listener} on a background thread whenever the status changes. */
    Subscription addListener(Consumer<AssistantStatus> listener);
}
