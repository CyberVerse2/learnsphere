package edu.learnsphere.core.service;

import edu.learnsphere.core.model.JobKind;
import java.util.List;
import java.util.Optional;

/**
 * Persistent background work: jobs survive restarts and run one at a time per kind in priority order.
 * Every state change publishes {@link edu.learnsphere.core.events.JobProgress}.
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
}
