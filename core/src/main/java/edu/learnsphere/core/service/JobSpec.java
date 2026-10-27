package edu.learnsphere.core.service;

import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.util.Json;
import java.util.Objects;

/**
 * A job to enqueue.
 *
 * @param subjectId   the course, module, document or item it works on, or null
 * @param payloadJson kind-specific parameters as JSON
 * @param priority    higher runs first ({@link #USER_PRIORITY}, {@link #BACKGROUND_PRIORITY})
 * @param unique      when true and an active job of the same kind and subject exists, that job is returned
 *                    instead of queuing another
 */
public record JobSpec(JobKind kind, String subjectId, String payloadJson, int priority, boolean unique) {

    /** Work the user started and is waiting for. */
    public static final int USER_PRIORITY = 10;
    /** Work nobody is waiting for. */
    public static final int BACKGROUND_PRIORITY = 0;

    public JobSpec {
        Objects.requireNonNull(kind, "kind");
        payloadJson = Objects.requireNonNullElse(payloadJson, "{}");
    }

    /** A unique, user-priority job whose payload is {@code payload} serialized as JSON (null for none). */
    public static JobSpec of(JobKind kind, String subjectId, Object payload) {
        return new JobSpec(kind, subjectId, payload == null ? "{}" : Json.write(payload), USER_PRIORITY, true);
    }

    public JobSpec withPriority(int newPriority) {
        return new JobSpec(kind, subjectId, payloadJson, newPriority, unique);
    }

    public JobSpec allowingDuplicates() {
        return new JobSpec(kind, subjectId, payloadJson, priority, false);
    }
}
