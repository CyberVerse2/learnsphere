package edu.learnsphere.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.JobProgress;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.testing.TestDb;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JobQueueImplTest {

    @TempDir
    Path dir;

    private TestDb t;
    private EventBus events;
    private JobQueueImpl queue;
    private final List<JobUpdate> published = new CopyOnWriteArrayList<>();

    record Outcome(int pages) {
    }

    @BeforeEach
    void setUp() {
        t = TestDb.open(dir);
        events = new EventBus();
        events.subscribe(JobProgress.class, e -> published.add(e.job()));
        queue = new JobQueueImpl(t.repos.jobs(), events, t.clock);
    }

    @AfterEach
    void tearDown() {
        queue.close();
        events.close();
        t.close();
    }

    @Test
    void runsAJobReportsProgressAndKeepsItsResult() {
        queue.register(JobKind.IMPORT_PACKAGE, job -> {
            job.progress(0.5, "Copying PDFs… 1 of 2");
            job.setResult(new Outcome(job.payload(Outcome.class).pages() * 2));
        });

        JobUpdate queued = queue.enqueue(JobSpec.of(JobKind.IMPORT_PACKAGE, null, new Outcome(21)));
        awaitStatus(queued.jobId(), JobStatus.DONE);

        assertThat(queue.result(queued.jobId(), Outcome.class)).contains(new Outcome(42));
        assertThat(queue.active()).isEmpty();
        events.awaitIdle(Duration.ofSeconds(5));
        assertThat(published).extracting(JobUpdate::status)
                .containsSubsequence(JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.DONE);
        assertThat(published).extracting(JobUpdate::message).contains("Copying PDFs… 1 of 2");
    }

    @Test
    void storesOnlyTheUserMessageOfAFailure() {
        queue.register(JobKind.EXTRACT_CONCEPTS, job -> {
            throw new IllegalStateException("socket closed at 127.0.0.1:8081");
        });
        queue.register(JobKind.GENERATE_ITEMS, job -> {
            throw new LearnSphereException(UserMessage.ASSISTANT_UNAVAILABLE, "HTTP 503 from chat role");
        });

        JobUpdate unexpected = queue.enqueue(JobSpec.of(JobKind.EXTRACT_CONCEPTS, "module-1", null));
        JobUpdate known = queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "course-1", null));

        assertThat(awaitStatus(unexpected.jobId(), JobStatus.FAILED).failure()).isEqualTo(UserMessage.SOMETHING_WENT_WRONG);
        assertThat(awaitStatus(known.jobId(), JobStatus.FAILED).failure()).isEqualTo(UserMessage.ASSISTANT_UNAVAILABLE);
        assertThat(queue.result(known.jobId(), Object.class)).isEmpty();
    }

    @Test
    void returnsTheActiveJobForAUniqueSpec() {
        JobUpdate first = queue.enqueue(JobSpec.of(JobKind.REBUILD_INDEX, null, null));
        JobUpdate again = queue.enqueue(JobSpec.of(JobKind.REBUILD_INDEX, null, null));
        JobUpdate duplicate = queue.enqueue(JobSpec.of(JobKind.REBUILD_INDEX, null, null).allowingDuplicates());

        assertThat(again.jobId()).isEqualTo(first.jobId());
        assertThat(duplicate.jobId()).isNotEqualTo(first.jobId());
        assertThat(queue.active()).hasSize(2);
    }

    @Test
    void cancelsQueuedAndRunningJobs() throws InterruptedException {
        JobUpdate waiting = queue.enqueue(JobSpec.of(JobKind.EXTRACT_CONCEPTS, "m", null));
        queue.cancel(waiting.jobId());
        assertThat(queue.find(waiting.jobId())).get().extracting(JobUpdate::status).isEqualTo(JobStatus.CANCELLED);

        CountDownLatch started = new CountDownLatch(1);
        queue.register(JobKind.GENERATE_ITEMS, job -> {
            started.countDown();
            while (!job.isCancelled()) {
                Thread.onSpinWait();
            }
            job.token().throwIfCancelled();
        });
        JobUpdate running = queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "c", null));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        queue.cancel(running.jobId());

        awaitStatus(running.jobId(), JobStatus.CANCELLED);
    }

    @Test
    void runsDifferentKindsSideBySideAndOneKindAtATime() throws InterruptedException {
        CountDownLatch bothRunning = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        queue.register(JobKind.EXTRACT_CONCEPTS, job -> {
            order.add(job.job().subjectId());
            bothRunning.countDown();
            release.await(5, TimeUnit.SECONDS);
        });
        queue.register(JobKind.GENERATE_ITEMS, job -> {
            bothRunning.countDown();
            release.await(5, TimeUnit.SECONDS);
        });

        JobUpdate a = queue.enqueue(JobSpec.of(JobKind.EXTRACT_CONCEPTS, "a", null));
        t.clock.advance(Duration.ofMillis(1));
        JobUpdate b = queue.enqueue(JobSpec.of(JobKind.EXTRACT_CONCEPTS, "b", null));
        JobUpdate c = queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "c", null));

        assertThat(bothRunning.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(queue.find(b.jobId())).get().extracting(JobUpdate::status).isEqualTo(JobStatus.QUEUED);
        release.countDown();
        awaitStatus(a.jobId(), JobStatus.DONE);
        awaitStatus(b.jobId(), JobStatus.DONE);
        awaitStatus(c.jobId(), JobStatus.DONE);
        assertThat(order).containsExactly("a", "b");
    }

    @Test
    void closingPutsRunningJobsBackInTheQueueForTheNextStart() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        queue.register(JobKind.INGEST_DOCUMENT, job -> {
            started.countDown();
            while (!job.isCancelled()) {
                Thread.onSpinWait();
            }
        });
        JobUpdate job = queue.enqueue(JobSpec.of(JobKind.INGEST_DOCUMENT, "doc", null));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        queue.close();

        assertThat(t.repos.jobs().find(job.jobId())).get().extracting(j -> j.status()).isEqualTo(JobStatus.QUEUED);
        JobQueueImpl next = new JobQueueImpl(t.repos.jobs(), events, t.clock);
        try {
            assertThat(next.resumeUnfinished()).isEqualTo(1);
            next.register(JobKind.INGEST_DOCUMENT, context -> context.setResult(new Outcome(1)));
            awaitStatus(next, job.jobId(), JobStatus.DONE);
            assertThat(t.repos.jobs().find(job.jobId())).get().extracting(j -> j.attempts()).isEqualTo(2);
        } finally {
            next.close();
        }
    }

    private JobUpdate awaitStatus(String jobId, JobStatus status) {
        return awaitStatus(queue, jobId, status);
    }

    private static JobUpdate awaitStatus(JobQueueImpl queue, String jobId, JobStatus status) {
        await(() -> queue.find(jobId).map(j -> j.status() == status).orElse(false));
        return queue.find(jobId).orElseThrow();
    }

    static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met within 10 s");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
