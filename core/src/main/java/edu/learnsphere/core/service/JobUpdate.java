package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import java.util.Objects;

/**
 * A job's state as the interface shows it.
 *
 * @param progress 0–1
 * @param message  short user-facing progress line (follows the in-app copy rules), or null
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

    /** True while queued or running. */
    public boolean isActive() {
        return status == JobStatus.QUEUED || status == JobStatus.RUNNING;
    }
}
