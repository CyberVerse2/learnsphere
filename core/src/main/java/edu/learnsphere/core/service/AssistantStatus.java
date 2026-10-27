package edu.learnsphere.core.service;

import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.spi.ModelChoice;
import java.util.Objects;

/**
 * The study assistant's readiness as the interface shows it.
 *
 * @param message     one short sentence for the status line
 * @param activeModel the chat model in use, or null when none
 */
public record AssistantStatus(AssistantState state, UserMessage message, ModelChoice activeModel) {

    public AssistantStatus {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(message, "message");
    }

    public static AssistantStatus starting(ModelChoice model) {
        return new AssistantStatus(AssistantState.STARTING, UserMessage.of("Getting your study assistant ready"), model);
    }

    public static AssistantStatus ready(ModelChoice model) {
        return new AssistantStatus(AssistantState.READY, UserMessage.of("Your study assistant is ready"), model);
    }

    public static AssistantStatus unavailable() {
        return new AssistantStatus(AssistantState.UNAVAILABLE,
                UserMessage.of("Your study assistant can't run on this computer."), null);
    }

    public static AssistantStatus failed(ModelChoice model) {
        return new AssistantStatus(AssistantState.FAILED,
                UserMessage.of("Your study assistant stopped. Restart it to keep going."), model);
    }

    public boolean isReady() {
        return state == AssistantState.READY;
    }

    /** True when {@link AssistantService#restart()} can help. */
    public boolean canRestart() {
        return state == AssistantState.FAILED || state == AssistantState.READY;
    }
}
