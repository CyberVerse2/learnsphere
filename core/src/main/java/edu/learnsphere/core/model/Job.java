package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.Objects;

/**
 * A row of {@code jobs}: persistent background work that survives restarts.
 *
 * @param payloadJson kind-specific parameters as JSON ({@code "{}"} when none)
 * @param priority    higher runs first
 * @param attempts    how many times it has been started
 * @param progress    0–1
 * @param message     short user-facing progress line, or null
 * @param subjectId   the course, module, document or item the job works on, or null
 */
public record Job(
        String id,
        JobKind kind,
        String payloadJson,
        JobStatus status,
        int priority,
        int attempts,
        double progress,
        String message,
        String subjectId,
        long createdAt,
        long updatedAt) {

    public Job {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        payloadJson = Objects.requireNonNullElse(payloadJson, "{}");
        Objects.requireNonNull(status, "status");
    }

    /** A new queued job. */
    public static Job queued(JobKind kind, String subjectId, String payloadJson, int priority, long now) {
        return new Job(Ids.newId(), kind, payloadJson, JobStatus.QUEUED, priority, 0, 0, null, subjectId, now, now);
    }

    /** True while queued or running. */
    public boolean isActive() {
        return status == JobStatus.QUEUED || status == JobStatus.RUNNING;
    }
}
