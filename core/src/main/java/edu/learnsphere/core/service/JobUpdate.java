package edu.learnsphere.core.service;

import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import java.util.Objects;

/**
 * A job's state as the interface shows it.
 *
 * @param progress 0–1
 * @param message  while running, the latest progress line ("Writing questions… 12 of 30"); when failed,
 *                 the {@link edu.learnsphere.core.error.UserMessage} text to show; otherwise null. Always
 *                 follows the in-app copy rules and never holds exception text
 */
public record JobUpdate(String jobId, JobKind kind, String subjectId, JobStatus status, double progress, String message,
                        long createdAt, long updatedAt) {

    public JobUpdate {
        Objects.requireNonNull(jobId, "jobId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
    }

    /** The view of a stored job. */
    public static JobUpdate of(Job job) {
        return new JobUpdate(job.id(), job.kind(), job.subjectId(), job.status(), job.progress(), job.message(),
                job.createdAt(), job.updatedAt());
    }

    /** The sentence to show for a failed job, or null for any other status. */
    public UserMessage failure() {
        return status == JobStatus.FAILED && message != null ? UserMessage.of(message) : null;
    }

    /** True while queued or running. */
    public boolean isActive() {
        return status == JobStatus.QUEUED || status == JobStatus.RUNNING;
    }
}
