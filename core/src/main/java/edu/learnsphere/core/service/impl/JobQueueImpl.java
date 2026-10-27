package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.JobRepository;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UnavailableException;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * {@link JobQueue} over the {@code jobs} table. Each kind with queued work gets one platform worker thread
 * that claims and runs that kind's jobs until none is left, so kinds run side by side and jobs of one kind
 * run one at a time.
 *
 * <p><b>Order.</b> Within a kind the highest priority runs first, then the oldest. Work nobody is waiting
 * for ({@link JobSpec#BACKGROUND_PRIORITY}, anything below {@link JobSpec#USER_PRIORITY}) also makes way:
 * when a job of the same kind with a higher priority is queued while a background job runs, the background
 * job's token is cancelled and, once its handler returns, it goes back to the queue (handlers are idempotent,
 * so it continues where it stopped after the waiting work is done). Work the user started is never
 * interrupted this way. Enqueuing a {@linkplain JobSpec#unique() unique} spec whose job is already active
 * with a lower priority raises that job's priority, with the same effect as queuing it at the new one.
 *
 * <p><b>Retries.</b> A handler failure caused by an {@link UnavailableException} (something needed is not
 * ready yet, such as the study assistant while it starts) or by a busy database is tried again after a
 * growing delay ({@link RetryPolicy}); meanwhile the job is queued and skipped. After the last retry it is
 * marked failed with the exception's user message. Any other failure marks the job failed at once.
 *
 * <p><b>Events.</b> Every state change (queued, running, progress, waiting to try again, done, failed,
 * cancelled, put back in the queue) publishes {@link JobProgress}.
 *
 * <p><b>Closing</b> cancels the running jobs' tokens and waits for their handlers to return (threads are
 * never interrupted: an interrupt would close Lucene and NIO channels under the handler). Jobs stopped by
 * the close, and jobs waiting to be tried again, stay in the queue, so they run again next time.
 *
 * <p><b>Worker failures.</b> When recording a job's outcome fails (for example the database stays busy), the
 * job is put back in the queue rather than left marked running with no worker, and the queue looks for work
 * again after {@link #WORKER_RESTART_DELAY} (one second), first putting back any job still marked running
 * that no worker runs. A job whose outcome could not be recorded {@link #MAX_WORKER_FAILURES} times is
 * marked failed.
 *
 * <p>Thread-safe.
 */
public final class JobQueueImpl implements JobQueue, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JobQueueImpl.class);

    /** How long {@link #close()} waits for running handlers. */
    public static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(10);

    /** How long the queue waits after a worker failed before it looks for work again. */
    static final Duration WORKER_RESTART_DELAY = Duration.ofSeconds(1);

    /** Times a job's outcome may fail to be recorded before the job is marked failed. */
    static final int MAX_WORKER_FAILURES = 3;

    /**
     * How failures marked retryable are tried again.
     *
     * @param maxRetries   retries after the first failure (0 = never retry)
     * @param initialDelay delay before the first retry
     * @param multiplier   factor applied to the delay for each further retry (at least 1)
     * @param maxDelay     longest delay
     */
    public record RetryPolicy(int maxRetries, Duration initialDelay, double multiplier, Duration maxDelay) {

        /** Four retries after 5 s, 15 s, 45 s and 2 min: enough for the study assistant to finish starting. */
        public static final RetryPolicy DEFAULT = new RetryPolicy(4, Duration.ofSeconds(5), 3, Duration.ofMinutes(2));

        /** Fails at once. */
        public static final RetryPolicy NONE = new RetryPolicy(0, Duration.ZERO, 1, Duration.ZERO);

        public RetryPolicy {
            if (maxRetries < 0) {
                throw new IllegalArgumentException("maxRetries must be >= 0: " + maxRetries);
            }
            Objects.requireNonNull(initialDelay, "initialDelay");
            Objects.requireNonNull(maxDelay, "maxDelay");
            if (initialDelay.isNegative() || maxDelay.isNegative() || multiplier < 1) {
                throw new IllegalArgumentException("Invalid retry delays");
            }
        }

        /** The delay before retry number {@code retry} (1 for the first). */
        public Duration delayBefore(int retry) {
            double millis = initialDelay.toMillis() * Math.pow(multiplier, Math.max(0, retry - 1));
            return Duration.ofMillis((long) Math.min(millis, maxDelay.toMillis()));
        }
    }

    private final JobRepository jobs;
    private final EventBus events;
    private final Clock clock;
    private final RetryPolicy retryPolicy;
    private final Duration workerRestartDelay;
    private final ExecutorService workers = Executors.newThreadPerTaskExecutor(
            Thread.ofPlatform().name("learnsphere-job-", 0).daemon(true).factory());
    private final ScheduledExecutorService timer;
    private final Object lock = new Object();
    // Everything below is guarded by lock.
    private final Map<JobKind, JobHandler> handlers = new EnumMap<>(JobKind.class);
    private final Set<JobKind> busy = EnumSet.noneOf(JobKind.class);
    private final Map<String, Run> running = new HashMap<>();
    private final Map<String, ScheduledFuture<?>> waitingToRetry = new HashMap<>();
    private final Map<String, Integer> retries = new HashMap<>();
    private final Map<String, Integer> workerFailures = new HashMap<>();
    private volatile boolean closing;

    /** A queue with the {@link RetryPolicy#DEFAULT default retries}. */
    public JobQueueImpl(JobRepository jobs, EventBus events, Clock clock) {
        this(jobs, events, clock, RetryPolicy.DEFAULT);
    }

    /** A queue with its own retry policy (tests use short delays). */
    public JobQueueImpl(JobRepository jobs, EventBus events, Clock clock, RetryPolicy retryPolicy) {
        this(jobs, events, clock, retryPolicy, WORKER_RESTART_DELAY);
    }

    /** As the public constructors, with another delay after a worker failed (tests use a short one). */
    JobQueueImpl(JobRepository jobs, EventBus events, Clock clock, RetryPolicy retryPolicy, Duration workerRestartDelay) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.events = Objects.requireNonNull(events, "events");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.workerRestartDelay = Objects.requireNonNull(workerRestartDelay, "workerRestartDelay");
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
                Thread.ofPlatform().name("learnsphere-job-timer").daemon(true).factory());
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        this.timer = scheduler;
    }

    @Override
    public void register(JobKind kind, JobHandler handler) {
        synchronized (lock) {
            handlers.put(Objects.requireNonNull(kind, "kind"), Objects.requireNonNull(handler, "handler"));
        }
        pump();
    }

    /**
     * Queues a job. For a unique spec whose job is already queued or running, returns that job instead,
     * after raising its priority to the spec's when it was lower.
     */
    @Override
    public JobUpdate enqueue(JobSpec spec) {
        Objects.requireNonNull(spec, "spec");
        JobUpdate update;
        synchronized (lock) {
            Optional<Job> existing = spec.unique() ? jobs.findActive(spec.kind(), spec.subjectId()) : Optional.empty();
            Job job;
            if (existing.isPresent()) {
                Job active = existing.get();
                if (active.priority() >= spec.priority() || !jobs.raisePriority(active.id(), spec.priority())) {
                    return JobUpdate.of(active);
                }
                LOG.debug("Job {} raised from priority {} to {}", active.id(), active.priority(), spec.priority());
                job = jobs.find(active.id()).orElse(active);
                Run run = running.get(job.id());
                if (run != null) {
                    run.priority = spec.priority();
                }
            } else {
                job = Job.queued(spec.kind(), spec.subjectId(), spec.payloadJson(), spec.priority(), clock.millis());
                jobs.insert(job);
            }
            update = JobUpdate.of(job);
            makeWayFor(job.id(), job.kind(), spec.priority());
        }
        events.publish(new JobProgress(update));
        pump();
        return update;
    }

    /**
     * Stops running background jobs of {@code kind} with a priority below {@code priority}, so the job
     * {@code jobId} runs next.
     */
    private void makeWayFor(String jobId, JobKind kind, int priority) {
        for (Run run : running.values()) {
            Job current = run.job;
            if (current.kind() == kind && !current.id().equals(jobId) && run.priority < JobSpec.USER_PRIORITY
                    && run.priority < priority && !run.token.isCancelled()) {
                LOG.debug("Job {} makes way for {}", current.id(), jobId);
                run.preempted = true;
                run.token.cancel();
            }
        }
    }

    @Override
    public void cancel(String jobId) {
        boolean cancelledQueued;
        synchronized (lock) {
            Run run = running.get(jobId);
            if (run != null) {
                run.cancelledByUser = true;
                run.token.cancel();
                return;
            }
            ScheduledFuture<?> retry = waitingToRetry.remove(jobId);
            if (retry != null) {
                retry.cancel(false);
            }
            retries.remove(jobId);
            workerFailures.remove(jobId);
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

    /** Running jobs first, then queued ones in the order they will run. */
    @Override
    public List<JobUpdate> active() {
        List<Job> active = new ArrayList<>(jobs.listActive());
        active.sort(Comparator.comparing((Job job) -> job.status() != JobStatus.RUNNING));
        return active.stream().map(JobUpdate::of).toList();
    }

    @Override
    public int resumeUnfinished() {
        List<String> requeued;
        int waiting;
        synchronized (lock) {
            requeued = requeueOrphans();
            waiting = jobs.listByStatus(Set.of(JobStatus.QUEUED)).size();
        }
        requeued.forEach(this::publish);
        pump();
        return waiting;
    }

    /**
     * Puts the jobs marked running that no worker of this queue runs back in the queue (left by an earlier
     * run of the app, or by a worker that failed); returns their ids. Call while holding {@code lock}, which
     * claiming a job also holds, so a job being claimed is never taken for one left behind.
     */
    private List<String> requeueOrphans() {
        List<String> requeued = new ArrayList<>();
        for (Job job : jobs.listByStatus(Set.of(JobStatus.RUNNING))) {
            if (!running.containsKey(job.id()) && jobs.requeue(job.id())) {
                requeued.add(job.id());
            }
        }
        return requeued;
    }

    @Override
    public <T> Optional<T> result(String jobId, Class<T> type) {
        return jobs.find(jobId)
                .filter(job -> job.status() == JobStatus.DONE && job.resultJson() != null)
                .map(job -> Json.read(job.resultJson(), type));
    }

    /** True while the job waits before it is tried again. */
    public boolean isWaitingToRetry(String jobId) {
        synchronized (lock) {
            return waitingToRetry.containsKey(jobId);
        }
    }

    /** Starts a worker for every kind that has a job ready to run, a handler and no worker yet. */
    private void pump() {
        if (closing) {
            return;
        }
        List<Job> queued = jobs.listByStatus(Set.of(JobStatus.QUEUED));
        synchronized (lock) {
            Set<JobKind> ready = new LinkedHashSet<>();
            for (Job job : queued) {
                if (!waitingToRetry.containsKey(job.id())) {
                    ready.add(job.kind());
                }
            }
            for (JobKind kind : ready) {
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
        boolean crashed = false;
        try {
            while (true) {
                Run run;
                JobHandler handler;
                synchronized (lock) {
                    // Checked under the lock close() holds while it cancels the running jobs: a job claimed
                    // here is either cancelled by close() or never claimed.
                    if (closing) {
                        return;
                    }
                    Optional<Job> next = jobs.claimNext(kind, waitingToRetry.keySet());
                    if (next.isEmpty()) {
                        // Released under the lock that enqueue also takes, so no new job is missed.
                        busy.remove(kind);
                        return;
                    }
                    run = new Run(next.get(), CancellationToken.create());
                    handler = handlers.get(kind);
                    running.put(run.job.id(), run);
                }
                try {
                    publish(run.job.id());
                    execute(run, handler);
                } catch (RuntimeException e) {
                    putBackAfterWorkerFailure(run, e);
                    throw e;
                } finally {
                    synchronized (lock) {
                        running.remove(run.job.id());
                    }
                }
                publish(run.job.id());
            }
        } catch (RuntimeException e) {
            crashed = true;
            LOG.error("Job worker for {} stopped", kind, e);
        } finally {
            synchronized (lock) {
                busy.remove(kind);
            }
        }
        if (crashed) {
            // Look again a little later rather than spinning on a database that keeps failing.
            schedule(this::restartWorkers, workerRestartDelay);
        } else {
            pump();
        }
    }

    /**
     * The worker running {@code run} failed while recording its outcome: puts the job back in the queue so it
     * is not left marked running with no worker (a job the user cancelled is marked cancelled, and one that
     * failed this way {@link #MAX_WORKER_FAILURES} times is marked failed). Best effort: when the database
     * still fails, {@link #restartWorkers} puts the job back later. While closing, {@link #close()} does it.
     */
    private void putBackAfterWorkerFailure(Run run, RuntimeException error) {
        if (closing) {
            return;
        }
        String id = run.job.id();
        try {
            if (run.cancelledByUser) {
                jobs.markCancelled(id);
                forget(id);
            } else {
                int failures;
                synchronized (lock) {
                    failures = workerFailures.merge(id, 1, Integer::sum);
                }
                if (failures >= MAX_WORKER_FAILURES) {
                    LOG.warn("Job {} ({}) is marked failed: its outcome could not be recorded {} times", id,
                            run.job.kind().code(), failures);
                    jobs.markFailed(id, LearnSphereException.userMessageOf(error));
                    forget(id);
                } else {
                    jobs.requeue(id);
                }
            }
        } catch (RuntimeException e) {
            LOG.warn("Could not put job {} back in the queue after its worker failed", id, e);
            return;
        }
        publish(id);
    }

    /** After a worker failed: puts back jobs left marked running with no worker, then starts workers. */
    private void restartWorkers() {
        if (closing) {
            return;
        }
        List<String> requeued;
        synchronized (lock) {
            requeued = requeueOrphans();
        }
        requeued.forEach(this::publish);
        pump();
    }

    /**
     * Runs the handler, then records the outcome. Only the handler's own failures fail the job; a failure to
     * record the outcome (the database) propagates to {@link #drain}, which puts the job back.
     */
    private void execute(Run run, JobHandler handler) {
        Job job = run.job;
        Context context = new Context(job, run.token);
        Throwable failure = null;
        try {
            handler.run(context);
        } catch (Throwable e) {
            failure = e;
        }
        if (run.token.isCancelled() || failure instanceof CancellationException) {
            stopped(run);
        } else if (failure == null) {
            succeeded(job, context.result);
        } else if (!(isRetryable(failure) && retryLater(job, failure))) {
            failed(job, failure);
        }
    }

    private void succeeded(Job job, Object result) {
        String resultJson;
        try {
            resultJson = result == null ? null : Json.write(result);
        } catch (RuntimeException e) {
            failed(job, e);
            return;
        }
        jobs.markDone(job.id(), resultJson);
        forget(job.id());
    }

    private void failed(Job job, Throwable error) {
        LOG.warn("Job {} ({}) failed", job.id(), job.kind().code(), error);
        jobs.markFailed(job.id(), LearnSphereException.userMessageOf(error));
        forget(job.id());
    }

    /**
     * A job whose token was cancelled: cancelled by the user, stopped to make way for other work (back to
     * the queue), or stopped by the close (left running here; {@link #close()} puts it back in the queue).
     */
    private void stopped(Run run) {
        String id = run.job.id();
        if (run.cancelledByUser) {
            jobs.markCancelled(id);
            forget(id);
        } else if (closing) {
            return;
        } else if (run.preempted) {
            jobs.requeue(id);
        } else {
            jobs.markCancelled(id);
            forget(id);
        }
    }

    /**
     * Puts a job that failed for a passing reason back in the queue after a delay; false when out of retries.
     * While closing, the job goes straight back to the queue for the next start.
     */
    private boolean retryLater(Job job, Throwable error) {
        String id = job.id();
        synchronized (lock) {
            if (closing) {
                LOG.info("Job {} ({}) stays queued for the next start: {}", id, job.kind().code(), error.toString());
                jobs.requeue(id);
                return true;
            }
            int retry = retries.merge(id, 1, Integer::sum);
            if (retry > retryPolicy.maxRetries()) {
                retries.remove(id);
                return false;
            }
            Duration delay = retryPolicy.delayBefore(retry);
            LOG.info("Job {} ({}) will be tried again in {} ms (retry {} of {}): {}", id, job.kind().code(),
                    delay.toMillis(), retry, retryPolicy.maxRetries(), error.toString());
            if (!jobs.requeue(id)) {
                return false;
            }
            // Scheduled while holding the lock, so the timer cannot remove the entry before it is added.
            ScheduledFuture<?> timerTask = schedule(() -> {
                synchronized (lock) {
                    waitingToRetry.remove(id);
                }
                pump();
            }, delay);
            if (timerTask != null) {
                waitingToRetry.put(id, timerTask);
            }
            return true;
        }
    }

    /** Forgets the retry and worker-failure counts of a job that finished. */
    private void forget(String jobId) {
        synchronized (lock) {
            retries.remove(jobId);
            workerFailures.remove(jobId);
        }
    }

    private ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        try {
            return timer.schedule(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    LOG.error("Scheduled job queue task failed", e);
                }
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            return null;
        }
    }

    /**
     * True for failures that may pass by themselves: an {@link UnavailableException} anywhere in the causes,
     * or a busy or locked database.
     */
    static boolean isRetryable(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof UnavailableException) {
                return true;
            }
            if (t instanceof SQLiteException e && e.getResultCode() != null) {
                String code = e.getResultCode().name();
                if (code.startsWith(SQLiteErrorCode.SQLITE_BUSY.name()) || code.startsWith(SQLiteErrorCode.SQLITE_LOCKED.name())) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private void publish(String jobId) {
        try {
            jobs.find(jobId).map(JobUpdate::of).ifPresent(update -> events.publish(new JobProgress(update)));
        } catch (RuntimeException e) {
            LOG.warn("Could not read job {} to announce it", jobId, e);
        }
    }

    /**
     * Stops starting jobs, cancels running ones and waits up to {@link #CLOSE_TIMEOUT} for their handlers to
     * return; jobs that were running go back to the queue, and jobs waiting to be tried again stay there.
     * Idempotent.
     */
    @Override
    public void close() {
        if (closing) {
            return;
        }
        closing = true;
        synchronized (lock) {
            waitingToRetry.values().forEach(task -> task.cancel(false));
            waitingToRetry.clear();
            running.values().forEach(run -> run.token.cancel());
        }
        timer.shutdownNow();
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

    /** A job being run by a worker. */
    private static final class Run {

        final Job job;
        final CancellationToken token;
        /** Its current priority: the job's, unless it was raised while running. */
        volatile int priority;
        /** The user cancelled it. */
        volatile boolean cancelledByUser;
        /** It was stopped to make way for a job with a higher priority. */
        volatile boolean preempted;

        Run(Job job, CancellationToken token) {
            this.job = job;
            this.token = token;
            this.priority = job.priority();
        }
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
