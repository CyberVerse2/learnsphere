package edu.learnsphere.core.events;

import edu.learnsphere.core.service.AssistantStatus;
import java.util.Objects;

/** The study assistant's readiness changed. */
public record AssistantStatusChanged(AssistantStatus status) implements Event {

    public AssistantStatusChanged {
        Objects.requireNonNull(status, "status");
    }
}
