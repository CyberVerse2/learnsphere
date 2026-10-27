package edu.learnsphere.core.service;

/**
 * Runs jobs of one {@link edu.learnsphere.core.model.JobKind}. Must be idempotent: a job interrupted by a
 * restart runs again from the start of its handler and should skip work already done.
 */
@FunctionalInterface
public interface JobHandler {

    /**
     * Does the work. Returning marks the job done; throwing marks it failed (a
     * {@link java.util.concurrent.CancellationException} marks it cancelled).
     */
    void run(JobContext context) throws Exception;
}
