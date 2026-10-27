package edu.learnsphere.core.spi;

import java.util.Objects;

/**
 * State of the AI runtime as the ai module sees it. {@code AssistantService} turns this into a
 * user-facing {@code AssistantStatus}.
 *
 * @param state  lifecycle state
 * @param model  the chat model in use or starting (never {@link ModelChoice#AUTO}), or null when none
 * @param detail technical detail for logs (never shown to users), or null
 */
public record AiStatus(State state, ModelChoice model, String detail) {

    /** Lifecycle of the AI runtime. */
    public enum State {
        /** Processes are starting or loading models. */
        STARTING,
        /** Every role answers requests. */
        READY,
        /** The runtime files are missing or this machine cannot run them. */
        UNAVAILABLE,
        /** A process crashed and could not be restarted. */
        FAILED
    }

    public AiStatus {
        Objects.requireNonNull(state, "state");
    }

    public boolean isReady() {
        return state == State.READY;
    }
}
