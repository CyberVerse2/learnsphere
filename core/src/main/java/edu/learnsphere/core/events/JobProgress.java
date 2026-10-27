package edu.learnsphere.core.events;

import edu.learnsphere.core.service.JobUpdate;
import java.util.Objects;

/** A background job was queued, made progress, finished, failed or was cancelled. */
public record JobProgress(JobUpdate job) implements Event {

    public JobProgress {
        Objects.requireNonNull(job, "job");
    }
}
