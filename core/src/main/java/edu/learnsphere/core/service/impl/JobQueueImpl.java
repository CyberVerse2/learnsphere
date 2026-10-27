package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.JobRepository;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.JobProgress;
import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.service.JobContext;
import edu.learnsphere.core.service.JobHandler;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Json;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link JobQueue} over the {@code jobs} table. Each kind with queued work gets one platform worker thread
 * that claims and runs that kind's jobs in priority order until none is left, so kinds run side by side and
 * jobs of one kind run one at a time.
 *
 * <p>Closing cancels the running jobs' tokens and waits for their handlers to return (threads are never
 * interrupted: an interrupt would close Lucene and NIO channels under the handler). Jobs stopped by the
 * close are put back in the queue rather than marked cancelled, so they run again next time.
 */
public final class JobQueueImpl implements JobQueue, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JobQueueImpl.class);

    /** How long {@link #close()} waits for running handlers. */
    public static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    private final JobRepository jobs;
    private final EventBus events;
    private final Clock clock;
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().name("learnsphere-job-", 0).daemon(true).factory());
    private final Object lock = new Object();
    private final Map<JobKind, JobHandler> handlers = new EnumMap<>(JobKind.class);
    private final Set<JobKind> busy = EnumSet.noneOf(JobKind.class);
    private final Map<String, CancellationToken> running = new HashMap<>();
    private volatile boolean closing;

    public JobQueueImpl(JobRepository jobs, EventBus events, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void register(JobKind kind, JobHandler handler) {
        synchronized (lock) {
            handlers.put(Objects.requireNonNull(kind, "kind"), Objects.requireNonNull(handler, "handler"));
        }
        pump();
    }

    @Override
    public JobUpdate enqueue(JobSpec spec) {
        Objects.requireNonNull(spec, "spec");
        JobUpdate update;
        synchronized (lock) {
            if (spec.unique()) {
                Optional<Job> existing = jobs.findActive(spec.kind(), spec.subjectId());
                if (existing.isPresent()) {
                    return JobUpdate.of(existing.get());
                }
            }
            Job job = Job.queued(spec.kind(), spec.subjectId(), spec.payloadJson(), spec.priority(), clock.millis());
            jobs.insert(job);
            update = JobUpdate.of(job);
        }
        events.publish(new JobProgress(update));
        pump();
        return update;
    }

    @Override
    public void cancel(String jobId) {
        boolean cancelledQueued;
        synchronized (lock) {
            CancellationToken token = running.get(jobId);
            if (token != null) {
                token.cancel();
                return;
            }
            cancelledQueued = jobs.markCancelled(jobId);
        }
        if (cancelledQueued) {
            publish(jobId);
        }
    }

    @Override
    public Optional<JobUpdate> find(String jobId) {
        return jobs.find(jobId).map(JobUpdate::of);
    }

    @Override
    public List<JobUpdate> active() {
        return jobs.listActive().stream().map(JobUpdate::of).toList();
    }

    @Override
    public int resumeUnfinished() {
        int waiting;
        synchronized (lock) {
            jobs.requeueRunning();
            waiting = jobs.listByStatus(Set.of(JobStatus.QUEUED)).size();
        }
        pump();
        return waiting;
    }

    @Override
    public <T> Optional<T> result(String jobId, Class<T> type) {
        return jobs.find(jobId)
                .filter(job -> job.status() == JobStatus.DONE && job.resultJson() != null)
                .map(job -> Json.read(job.resultJson(), type));
    }

    /** Starts a worker for every kind that has queued jobs, a handler and no worker yet. */
    private void pump() {
        if (closing) {
            return;
        }
        Set<JobKind> queued = jobs.queuedKinds();
        synchronized (lock) {
            for (JobKind kind : queued) {
                if (closing || busy.contains(kind) || !handlers.containsKey(kind)) {
                    continue;
                }
                busy.add(kind);
                try {
                    workers.execute(() -> drain(kind));
                } catch (RejectedExecutionException e) {
                    busy.remove(kind);
                }
            }
        }
    }

    private void drain(JobKind kind) {
        try {
            while (!closing) {
                Job job;
                CancellationToken token = CancellationToken.create();
                JobHandler handler;
                synchronized (lock) {
                    Optional<Job> next = jobs.claimNext(kind);
                    if (next.isEmpty()) {
                        busy.remove(kind);
                        return;
                    }
                    job = next.get();
                    handler = handlers.get(kind);
                    running.put(job.id(), token);
                }
                try {
                    publish(job.id());
                    run(job, handler, token);
                } finally {
                    synchronized (lock) {
                        running.remove(job.id());
                    }
                }
            }
        } catch (RuntimeException e) {
            LOG.error("Job worker for {} stopped", kind, e);
        } finally {
            synchronized (lock) {
                busy.remove(kind);
            }
        }
        // A job queued while this worker was finishing still gets a worker.
        pump();
    }

    private void run(Job job, JobHandler handler, CancellationToken token) {
        Context context = new Context(job, token);
        try {
            handler.run(context);
            if (token.isCancelled()) {
                stopped(job);
            } else {
                jobs.markDone(job.id(), context.result == null ? null : Json.write(context.result));
            }
        } catch (CancellationException e) {
            stopped(job);
        } catch (Exception e) {
            if (token.isCancelled()) {
                stopped(job);
            } else {
                LOG.warn("Job {} ({}) failed", job.id(), job.kind().code(), e);
                jobs.markFailed(job.id(), LearnSphereException.userMessageOf(e));
            }
        }
        publish(job.id());
    }

    /** A job whose token was cancelled: cancelled by the user, or left running for the next start when closing. */
    private void stopped(Job job) {
        if (!closing) {
            jobs.markCancelled(job.id());
        }
    }

    private void publish(String jobId) {
        jobs.find(jobId).map(JobUpdate::of).ifPresent(update -> events.publish(new JobProgress(update)));
    }

    /**
     * Stops starting jobs, cancels running ones and waits up to {@link #CLOSE_TIMEOUT} for their handlers to
     * return; jobs that were running go back to the queue. Idempotent.
     */
    @Override
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        synchronized (lock) {
            running.values().forEach(CancellationToken::cancel);
        }
        workers.shutdown();
        try {
            if (!workers.awaitTermination(CLOSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.warn("Background jobs did not stop within {}", CLOSE_TIMEOUT);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        jobs.requeueRunning();
    }

    private final class Context implements JobContext {

        private final Job job;
        private final CancellationToken token;
        private volatile Object result;

        Context(Job job, CancellationToken token) {
            this.job = job;
            this.token = token;
        }

        @Override
        public Job job() {
            return job;
        }

        @Override
        public <T> T payload(Class<T> type) {
            return Json.read(job.payloadJson(), type);
        }

        @Override
        public void progress(double fraction, String message) {
            jobs.updateProgress(job.id(), fraction, message);
            publish(job.id());
        }

        @Override
        public void setResult(Object value) {
            this.result = value;
        }

        @Override
        public CancellationToken token() {
            return token;
        }
    }
}
