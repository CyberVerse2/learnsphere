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

    /**
     * Stores what the job produced (serialized as JSON when the job finishes successfully), for example
     * the outcome of an import. Read it back with {@link JobQueue#result}.
     */
    void setResult(Object result);

    /** Cancelled when the user cancels the job or the workspace closes. */
    CancellationToken token();

    /** Shorthand for {@code token().isCancelled()}. */
    default boolean isCancelled() {
        return token().isCancelled();
    }

    /**
     * Marks the point after which the job can no longer be undone, typically right before it commits its
     * changes: from then on a cancel request no longer stops it, and it ends as done (with its result) or
     * failed. Returns false, and marks nothing, when the job was already cancelled; the handler then stops.
     *
     * <p>The default only reports whether the job was cancelled, so a queue that does not override it keeps
     * treating a later cancel as stopping the job.
     */
    default boolean markUncancellable() {
        return !isCancelled();
    }
}
