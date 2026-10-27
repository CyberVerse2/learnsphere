package edu.learnsphere.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.db.JobRepository;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.JobProgress;
import edu.learnsphere.core.model.Job;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

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
    void enqueuingAUniqueSpecAgainRaisesThePriorityOfItsQueuedJob() throws InterruptedException {
        JobUpdate resumed = queue.enqueue(
                JobSpec.of(JobKind.INGEST_DOCUMENT, "doc", null).withPriority(JobSpec.BACKGROUND_PRIORITY));
        t.clock.advance(Duration.ofMillis(1));
        queue.enqueue(JobSpec.of(JobKind.INGEST_DOCUMENT, "other", null).withPriority(JobSpec.BACKGROUND_PRIORITY + 1));
        assertThat(queue.active()).extracting(JobUpdate::subjectId).containsExactly("other", "doc");

        JobUpdate again = queue.enqueue(JobSpec.of(JobKind.INGEST_DOCUMENT, "doc", null));
        JobUpdate lower = queue.enqueue(
                JobSpec.of(JobKind.INGEST_DOCUMENT, "doc", null).withPriority(JobSpec.BACKGROUND_PRIORITY));

        assertThat(again.jobId()).isEqualTo(resumed.jobId());
        assertThat(lower.jobId()).isEqualTo(resumed.jobId());
        assertThat(t.repos.jobs().find(resumed.jobId())).get().extracting(Job::priority).isEqualTo(JobSpec.USER_PRIORITY);
        assertThat(queue.active()).extracting(JobUpdate::subjectId).containsExactly("doc", "other");
        events.awaitIdle(Duration.ofSeconds(5));
        assertThat(published).filteredOn(u -> u.jobId().equals(resumed.jobId())).extracting(JobUpdate::status)
                .containsExactly(JobStatus.QUEUED, JobStatus.QUEUED);

        // A raised job makes a running background job of its kind give way, as if it had been queued at once.
        CountDownLatch started = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        queue.register(JobKind.GENERATE_ITEMS, job -> {
            order.add(job.job().subjectId());
            if (job.job().subjectId().equals("slow") && order.size() == 1) {
                started.countDown();
                while (!job.isCancelled()) {
                    Thread.onSpinWait();
                }
                job.token().throwIfCancelled();
            }
        });
        JobUpdate slow = queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "slow", null).withPriority(JobSpec.BACKGROUND_PRIORITY));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        JobUpdate waiting = queue.enqueue(
                JobSpec.of(JobKind.GENERATE_ITEMS, "waiting", null).withPriority(JobSpec.BACKGROUND_PRIORITY));
        assertThat(queue.find(waiting.jobId())).get().extracting(JobUpdate::status).isEqualTo(JobStatus.QUEUED);
        queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "waiting", null));

        awaitStatus(slow.jobId(), JobStatus.DONE);
        awaitStatus(waiting.jobId(), JobStatus.DONE);
        assertThat(order).containsExactly("slow", "waiting", "slow");
    }

    @Test
    void aRunningJobWhosePriorityWasRaisedNoLongerMakesWay() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Boolean> cancelledSeen = new CopyOnWriteArrayList<>();
        queue.register(JobKind.INGEST_DOCUMENT, job -> {
            if (job.job().subjectId().equals("resumed")) {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                cancelledSeen.add(job.isCancelled());
            }
        });
        JobUpdate resumed = queue.enqueue(
                JobSpec.of(JobKind.INGEST_DOCUMENT, "resumed", null).withPriority(JobSpec.BACKGROUND_PRIORITY));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(queue.enqueue(JobSpec.of(JobKind.INGEST_DOCUMENT, "resumed", null)).jobId()).isEqualTo(resumed.jobId());
        JobUpdate imported = queue.enqueue(JobSpec.of(JobKind.INGEST_DOCUMENT, "imported", null));
        release.countDown();

        awaitStatus(resumed.jobId(), JobStatus.DONE);
        awaitStatus(imported.jobId(), JobStatus.DONE);
        assertThat(cancelledSeen).containsExactly(false);
        assertThat(t.repos.jobs().find(resumed.jobId())).get().extracting(Job::attempts).isEqualTo(1);
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

    @Test
    void runsHigherPriorityWorkFirstWithinAKind() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        queue.register(JobKind.GENERATE_ITEMS, job -> {
            order.add(job.job().subjectId());
            if (job.job().subjectId().equals("first")) {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
        });

        queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "first", null));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        t.clock.advance(Duration.ofMillis(1));
        queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "background-1", null).withPriority(JobSpec.BACKGROUND_PRIORITY));
        t.clock.advance(Duration.ofMillis(1));
        queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "user", null));
        t.clock.advance(Duration.ofMillis(1));
        queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "background-2", null).withPriority(JobSpec.BACKGROUND_PRIORITY));

        assertThat(queue.active()).extracting(JobUpdate::subjectId)
                .containsExactly("first", "user", "background-1", "background-2");
        release.countDown();
        await(() -> queue.active().isEmpty());
        assertThat(order).containsExactly("first", "user", "background-1", "background-2");
    }

    @Test
    void backgroundWorkMakesWayForWorkTheUserIsWaitingFor() throws InterruptedException {
        CountDownLatch backgroundStarted = new CountDownLatch(1);
        List<String> order = new CopyOnWriteArrayList<>();
        queue.register(JobKind.INGEST_DOCUMENT, job -> {
            order.add(job.job().subjectId());
            if (job.job().subjectId().equals("resumed") && order.size() == 1) {
                backgroundStarted.countDown();
                while (!job.isCancelled()) {
                    Thread.onSpinWait();
                }
                job.token().throwIfCancelled();
            }
        });

        JobUpdate background = queue.enqueue(
                JobSpec.of(JobKind.INGEST_DOCUMENT, "resumed", null).withPriority(JobSpec.BACKGROUND_PRIORITY));
        assertThat(backgroundStarted.await(5, TimeUnit.SECONDS)).isTrue();
        JobUpdate user = queue.enqueue(JobSpec.of(JobKind.INGEST_DOCUMENT, "imported", null));

        awaitStatus(user.jobId(), JobStatus.DONE);
        awaitStatus(background.jobId(), JobStatus.DONE);
        assertThat(order).containsExactly("resumed", "imported", "resumed");
        assertThat(t.repos.jobs().find(background.jobId())).get().extracting(j -> j.attempts()).isEqualTo(2);
        events.awaitIdle(Duration.ofSeconds(5));
        assertThat(published).filteredOn(u -> u.jobId().equals(background.jobId())).extracting(JobUpdate::status)
                .containsSubsequence(JobStatus.RUNNING, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.DONE)
                .doesNotContain(JobStatus.CANCELLED);
    }

    @Test
    void workTheUserStartedIsNotInterruptedByOtherWork() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<Boolean> cancelledSeen = new CopyOnWriteArrayList<>();
        queue.register(JobKind.GENERATE_ITEMS, job -> {
            if (job.job().subjectId().equals("first")) {
                started.countDown();
                release.await(5, TimeUnit.SECONDS);
                cancelledSeen.add(job.isCancelled());
            }
        });
        JobUpdate first = queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "first", null));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "second", null).withPriority(JobSpec.USER_PRIORITY + 5));
        release.countDown();

        awaitStatus(first.jobId(), JobStatus.DONE);
        assertThat(cancelledSeen).containsExactly(false);
    }

    @Test
    void triesAgainAfterAPassingFailureWithGrowingDelays() {
        JobQueueImpl retrying = new JobQueueImpl(t.repos.jobs(), events, t.clock,
                new JobQueueImpl.RetryPolicy(3, Duration.ofMillis(20), 2, Duration.ofMillis(50)));
        try {
            List<Long> startedAt = new CopyOnWriteArrayList<>();
            retrying.register(JobKind.GENERATE_ITEMS, job -> {
                startedAt.add(System.nanoTime());
                if (startedAt.size() < 3) {
                    throw new UnavailableException(UserMessage.ASSISTANT_STARTING, "chat role still loading");
                }
                job.setResult(new Outcome(startedAt.size()));
            });

            JobUpdate job = retrying.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "course", null));
            awaitStatus(retrying, job.jobId(), JobStatus.DONE);

            assertThat(retrying.result(job.jobId(), Outcome.class)).contains(new Outcome(3));
            assertThat(t.repos.jobs().find(job.jobId())).get().extracting(j -> j.attempts()).isEqualTo(3);
            assertThat(TimeUnit.NANOSECONDS.toMillis(startedAt.get(1) - startedAt.get(0))).isGreaterThanOrEqualTo(15);
            assertThat(TimeUnit.NANOSECONDS.toMillis(startedAt.get(2) - startedAt.get(1))).isGreaterThanOrEqualTo(35);
            events.awaitIdle(Duration.ofSeconds(5));
            assertThat(published).filteredOn(u -> u.jobId().equals(job.jobId())).extracting(JobUpdate::status)
                    .containsSubsequence(JobStatus.RUNNING, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.QUEUED,
                            JobStatus.RUNNING, JobStatus.DONE)
                    .doesNotContain(JobStatus.FAILED);
        } finally {
            retrying.close();
        }
    }

    @Test
    void failsWithTheUserMessageWhenRetriesRunOut() {
        JobQueueImpl retrying = new JobQueueImpl(t.repos.jobs(), events, t.clock,
                new JobQueueImpl.RetryPolicy(2, Duration.ofMillis(5), 1, Duration.ofMillis(5)));
        try {
            retrying.register(JobKind.EXTRACT_CONCEPTS, job -> {
                throw new UnavailableException(UserMessage.ASSISTANT_STARTING, "still loading");
            });
            JobUpdate job = retrying.enqueue(JobSpec.of(JobKind.EXTRACT_CONCEPTS, "module", null));

            assertThat(awaitStatus(retrying, job.jobId(), JobStatus.FAILED).failure())
                    .isEqualTo(UserMessage.ASSISTANT_STARTING);
            assertThat(t.repos.jobs().find(job.jobId())).get().extracting(j -> j.attempts()).isEqualTo(3);
        } finally {
            retrying.close();
        }
    }

    @Test
    void doesNotRetryOrdinaryFailures() {
        JobQueueImpl retrying = new JobQueueImpl(t.repos.jobs(), events, t.clock,
                new JobQueueImpl.RetryPolicy(3, Duration.ofMillis(5), 1, Duration.ofMillis(5)));
        try {
            retrying.register(JobKind.EXTRACT_CONCEPTS, job -> {
                throw new InvalidInputException(UserMessage.of("This module has no PDFs yet. Add one first."), "empty");
            });
            JobUpdate job = retrying.enqueue(JobSpec.of(JobKind.EXTRACT_CONCEPTS, "module", null));

            assertThat(awaitStatus(retrying, job.jobId(), JobStatus.FAILED).message())
                    .isEqualTo("This module has no PDFs yet. Add one first.");
            assertThat(t.repos.jobs().find(job.jobId())).get().extracting(j -> j.attempts()).isEqualTo(1);
        } finally {
            retrying.close();
        }
    }

    @Test
    void cancellingAJobWaitingToBeTriedAgainStopsIt() {
        JobQueueImpl retrying = new JobQueueImpl(t.repos.jobs(), events, t.clock,
                new JobQueueImpl.RetryPolicy(3, Duration.ofMinutes(5), 1, Duration.ofMinutes(5)));
        try {
            List<String> runs = new CopyOnWriteArrayList<>();
            retrying.register(JobKind.GENERATE_ITEMS, job -> {
                runs.add(job.job().id());
                throw new UnavailableException(UserMessage.ASSISTANT_STARTING, "still loading");
            });
            JobUpdate job = retrying.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "course", null));
            await(() -> retrying.isWaitingToRetry(job.jobId()));
            assertThat(retrying.find(job.jobId())).get().extracting(JobUpdate::status).isEqualTo(JobStatus.QUEUED);

            retrying.cancel(job.jobId());

            assertThat(retrying.find(job.jobId())).get().extracting(JobUpdate::status).isEqualTo(JobStatus.CANCELLED);
            assertThat(retrying.isWaitingToRetry(job.jobId())).isFalse();
            assertThat(runs).hasSize(1);
        } finally {
            retrying.close();
        }
    }

    @Test
    void aJobWaitingToBeTriedAgainStaysQueuedForTheNextStart() {
        JobQueueImpl retrying = new JobQueueImpl(t.repos.jobs(), events, t.clock,
                new JobQueueImpl.RetryPolicy(3, Duration.ofMinutes(5), 1, Duration.ofMinutes(5)));
        retrying.register(JobKind.GENERATE_ITEMS, job -> {
            throw new UnavailableException(UserMessage.ASSISTANT_STARTING, "still loading");
        });
        JobUpdate job = retrying.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "course", null));
        await(() -> retrying.isWaitingToRetry(job.jobId()));
        retrying.close();

        assertThat(t.repos.jobs().find(job.jobId())).get().extracting(j -> j.status()).isEqualTo(JobStatus.QUEUED);
        JobQueueImpl next = new JobQueueImpl(t.repos.jobs(), events, t.clock);
        try {
            assertThat(next.resumeUnfinished()).isEqualTo(1);
            next.register(JobKind.GENERATE_ITEMS, context -> context.setResult(new Outcome(2)));
            awaitStatus(next, job.jobId(), JobStatus.DONE);
        } finally {
            next.close();
        }
    }

    @Test
    void resumingRequeuesOnlyJobsNoWorkerIsRunningAndAnnouncesThem() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        queue.register(JobKind.GENERATE_ITEMS, job -> {
            started.countDown();
            release.await(5, TimeUnit.SECONDS);
        });
        JobUpdate mine = queue.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "running here", null));
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Job leftOver = Job.queued(JobKind.EXTRACT_CONCEPTS, "left over", "{}", JobSpec.USER_PRIORITY, t.now());
        t.repos.jobs().insert(leftOver);
        t.repos.jobs().claimNext(JobKind.EXTRACT_CONCEPTS);

        assertThat(queue.resumeUnfinished()).isEqualTo(1);

        assertThat(queue.find(mine.jobId())).get().extracting(JobUpdate::status).isEqualTo(JobStatus.RUNNING);
        assertThat(queue.find(leftOver.id())).get().extracting(JobUpdate::status).isEqualTo(JobStatus.QUEUED);
        events.awaitIdle(Duration.ofSeconds(5));
        assertThat(published).filteredOn(u -> u.jobId().equals(leftOver.id())).extracting(JobUpdate::status)
                .containsExactly(JobStatus.QUEUED);
        release.countDown();
        awaitStatus(mine.jobId(), JobStatus.DONE);
    }

    @Test
    void aHandlerThatThrowsAnErrorFailsItsJobAndTheWorkerCarriesOn() {
        queue.register(JobKind.EXTRACT_CONCEPTS, job -> {
            if (job.job().subjectId().equals("broken")) {
                throw new AssertionError("handler bug");
            }
        });
        JobUpdate broken = queue.enqueue(JobSpec.of(JobKind.EXTRACT_CONCEPTS, "broken", null));
        t.clock.advance(Duration.ofMillis(1));
        JobUpdate fine = queue.enqueue(JobSpec.of(JobKind.EXTRACT_CONCEPTS, "fine", null));

        assertThat(awaitStatus(broken.jobId(), JobStatus.FAILED).failure()).isEqualTo(UserMessage.SOMETHING_WENT_WRONG);
        awaitStatus(fine.jobId(), JobStatus.DONE);
    }

    @Test
    void aResultThatCannotBeStoredFailsTheJob() {
        queue.register(JobKind.IMPORT_PACKAGE, job -> job.setResult(new SelfReferencing()));
        JobUpdate job = queue.enqueue(JobSpec.of(JobKind.IMPORT_PACKAGE, null, null));

        assertThat(awaitStatus(job.jobId(), JobStatus.FAILED).failure()).isEqualTo(UserMessage.SOMETHING_WENT_WRONG);
    }

    @Test
    void aJobWhoseOutcomeCouldNotBeRecordedGoesBackToTheQueue() {
        JobRepository flakyJobs = Mockito.spy(t.repos.jobs());
        AtomicInteger doneCalls = new AtomicInteger();
        Mockito.doAnswer(call -> {
            if (doneCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("database is locked");
            }
            return call.callRealMethod();
        }).when(flakyJobs).markDone(Mockito.anyString(), Mockito.any());
        JobQueueImpl flaky = new JobQueueImpl(flakyJobs, events, t.clock, JobQueueImpl.RetryPolicy.NONE,
                Duration.ofMillis(20));
        try {
            List<String> runs = new CopyOnWriteArrayList<>();
            flaky.register(JobKind.GENERATE_ITEMS, job -> runs.add(job.job().id()));

            JobUpdate job = flaky.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "course", null));

            awaitStatus(flaky, job.jobId(), JobStatus.DONE);
            assertThat(runs).hasSize(2);
            events.awaitIdle(Duration.ofSeconds(5));
            assertThat(published).filteredOn(u -> u.jobId().equals(job.jobId())).extracting(JobUpdate::status)
                    .containsSubsequence(JobStatus.RUNNING, JobStatus.QUEUED, JobStatus.RUNNING, JobStatus.DONE);
        } finally {
            flaky.close();
        }
    }

    @Test
    void aJobLeftMarkedRunningByAFailedWorkerIsPutBackWhenTheQueueLooksAgain() {
        JobRepository flakyJobs = Mockito.spy(t.repos.jobs());
        AtomicInteger doneCalls = new AtomicInteger();
        AtomicInteger requeueCalls = new AtomicInteger();
        Mockito.doAnswer(call -> {
            if (doneCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("database is locked");
            }
            return call.callRealMethod();
        }).when(flakyJobs).markDone(Mockito.anyString(), Mockito.any());
        Mockito.doAnswer(call -> {
            if (requeueCalls.getAndIncrement() == 0) {
                throw new IllegalStateException("database is locked");
            }
            return call.callRealMethod();
        }).when(flakyJobs).requeue(Mockito.anyString());
        JobQueueImpl flaky = new JobQueueImpl(flakyJobs, events, t.clock, JobQueueImpl.RetryPolicy.NONE,
                Duration.ofMillis(20));
        try {
            List<String> runs = new CopyOnWriteArrayList<>();
            flaky.register(JobKind.GENERATE_ITEMS, job -> runs.add(job.job().id()));

            JobUpdate job = flaky.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "course", null));

            awaitStatus(flaky, job.jobId(), JobStatus.DONE);
            assertThat(runs).hasSize(2);
            assertThat(requeueCalls).hasValue(2);
        } finally {
            flaky.close();
        }
    }

    @Test
    void aJobWhoseOutcomeKeepsFailingToBeRecordedIsMarkedFailed() {
        JobRepository flakyJobs = Mockito.spy(t.repos.jobs());
        Mockito.doThrow(new IllegalStateException("disk I/O error"))
                .when(flakyJobs).markDone(Mockito.anyString(), Mockito.any());
        JobQueueImpl flaky = new JobQueueImpl(flakyJobs, events, t.clock, JobQueueImpl.RetryPolicy.NONE,
                Duration.ofMillis(20));
        try {
            List<String> runs = new CopyOnWriteArrayList<>();
            flaky.register(JobKind.GENERATE_ITEMS, job -> runs.add(job.job().id()));
            JobUpdate job = flaky.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "course", null));

            assertThat(awaitStatus(flaky, job.jobId(), JobStatus.FAILED).failure())
                    .isEqualTo(UserMessage.SOMETHING_WENT_WRONG);
            assertThat(runs).hasSize(JobQueueImpl.MAX_WORKER_FAILURES);

            // The worker carries on with other work.
            Mockito.doCallRealMethod().when(flakyJobs).markDone(Mockito.anyString(), Mockito.any());
            JobUpdate next = flaky.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, "next", null));
            awaitStatus(flaky, next.jobId(), JobStatus.DONE);
        } finally {
            flaky.close();
        }
    }

    @Test
    void retryDelaysGrowUpToTheLimit() {
        JobQueueImpl.RetryPolicy policy = JobQueueImpl.RetryPolicy.DEFAULT;
        assertThat(policy.delayBefore(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(policy.delayBefore(2)).isEqualTo(Duration.ofSeconds(15));
        assertThat(policy.delayBefore(3)).isEqualTo(Duration.ofSeconds(45));
        assertThat(policy.delayBefore(4)).isEqualTo(Duration.ofMinutes(2));
        assertThat(JobQueueImpl.isRetryable(new LearnSphereException(UserMessage.SOMETHING_WENT_WRONG, "x",
                new UnavailableException(UserMessage.ASSISTANT_STARTING, "y")))).isTrue();
        assertThat(JobQueueImpl.isRetryable(new IllegalStateException("x"))).isFalse();
    }

    /** A result Jackson cannot serialize. */
    public static final class SelfReferencing {
        public SelfReferencing getSelf() {
            return this;
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
