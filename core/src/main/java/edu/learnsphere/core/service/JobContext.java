package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.util.CancellationToken;

/** What a running {@link JobHandler} can use: its job, progress reporting and cancellation. */
public interface JobContext {

    /** The job being run (status running). */
    Job job();

    /** The payload parsed from {@code payloadJson}. */
    <T> T payload(Class<T> type);

    /**
     * Reports progress; stored and published as {@link edu.learnsphere.core.events.JobProgress}.
     *
     * @param fraction 0–1
     * @param message  short user-facing line, e.g. "Writing questions… 12 of 30", or null to keep the last one
     */
    void progress(double fraction, String message);

    /** Cancelled when the user cancels the job or the workspace closes. */
    CancellationToken token();

    /** Shorthand for {@code token().isCancelled()}. */
    default boolean isCancelled() {
        return token().isCancelled();
    }
}
