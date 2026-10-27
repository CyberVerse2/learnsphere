package edu.learnsphere.core.service;

import edu.learnsphere.core.model.JobKind;
import java.util.List;
import java.util.Optional;

/**
 * Persistent background work: jobs survive restarts and run one at a time per kind in priority order
 * (jobs of different kinds run side by side). Every state change publishes
 * {@link edu.learnsphere.core.events.JobProgress}.
 *
 * <p>Outcomes: a handler that returns marks the job done and stores what it passed to
 * {@link JobContext#setResult}; one that throws {@link java.util.concurrent.CancellationException} (or
 * stops after its token was cancelled) marks it cancelled; any other exception is logged with its stack
 * trace and marks the job failed with {@code LearnSphereException.userMessageOf(error).text()} as its
 * message. Exception text ({@code getMessage()}) is never stored in a job or shown.
 */
public interface JobQueue {

    /** Queues a job (or returns the active duplicate for a unique spec). */
    JobUpdate enqueue(JobSpec spec);

    /** Cancels a queued or running job; a running job stops at its next cancellation check. */
    void cancel(String jobId);

    Optional<JobUpdate> find(String jobId);

    /** Queued and running jobs, in run order. */
    List<JobUpdate> active();

    /** Requeues jobs left running by an interrupted run and starts processing; returns how many were waiting. */
    int resumeUnfinished();

    /** Registers the handler for a kind (services register theirs when the workspace opens). */
    void register(JobKind kind, JobHandler handler);

    /**
     * The result a finished job stored with {@link JobContext#setResult}, read as {@code type}; empty while
     * it is queued or running, when it failed or was cancelled, or when it stored none.
     */
    <T> Optional<T> result(String jobId, Class<T> type);
}
