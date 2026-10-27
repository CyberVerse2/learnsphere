package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;

class JobRepositoryTest extends RepositoryTestBase {

    private Job queue(JobKind kind, String subject, int priority) {
        Job job = Job.queued(kind, subject, "{\"documentId\":\"" + subject + "\"}", priority, t.now());
        repos.jobs().insert(job);
        t.clock.advance(Duration.ofMillis(10));
        return job;
    }

    @Test
    void roundTripsAndFindsActiveJobs() {
        Job job = queue(JobKind.INGEST_DOCUMENT, "doc-1", 0);
        assertThat(repos.jobs().find(job.id())).contains(job);
        assertThat(repos.jobs().findActive(JobKind.INGEST_DOCUMENT, "doc-1")).contains(job);
        assertThat(repos.jobs().findActive(JobKind.GENERATE_ITEMS, "doc-1")).isEmpty();
        assertThat(repos.jobs().listActive()).containsExactly(job);
        assertThat(repos.jobs().listBySubject("doc-1", 5)).containsExactly(job);
    }

    @Test
    void claimsHighestPriorityThenOldestPerKind() {
        Job background = queue(JobKind.GENERATE_ITEMS, "m0", 0);
        Job userOld = queue(JobKind.GENERATE_ITEMS, "m1", 10);
        Job userNew = queue(JobKind.GENERATE_ITEMS, "m2", 10);
        Job rebuild = queue(JobKind.REBUILD_INDEX, null, 0);
        assertThat(repos.jobs().queuedKinds()).containsExactlyInAnyOrder(JobKind.GENERATE_ITEMS, JobKind.REBUILD_INDEX);

        Job claimed = repos.jobs().claimNext(JobKind.GENERATE_ITEMS).orElseThrow();
        assertThat(claimed.id()).isEqualTo(userOld.id());
        assertThat(claimed.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(repos.jobs().claimNext(JobKind.GENERATE_ITEMS).orElseThrow().id()).isEqualTo(userNew.id());
        assertThat(repos.jobs().claimNext(JobKind.GENERATE_ITEMS).orElseThrow().id()).isEqualTo(background.id());
        assertThat(repos.jobs().claimNext(JobKind.GENERATE_ITEMS)).isEmpty();
        assertThat(repos.jobs().claimNext(JobKind.REBUILD_INDEX).orElseThrow().id()).isEqualTo(rebuild.id());

        assertThat(repos.jobs().requeueRunning()).isEqualTo(4);
        assertThat(repos.jobs().listByStatus(Set.of(JobStatus.QUEUED))).hasSize(4);
    }

    @Test
    void tracksProgressResultsAndCleansUp() {
        Job job = queue(JobKind.INGEST_DOCUMENT, "doc", 0);
        repos.jobs().updateProgress(job.id(), 1.7, "Reading your PDF… page 14 of 120");
        assertThat(repos.jobs().find(job.id()).orElseThrow().progress()).isEqualTo(1.0);
        repos.jobs().updateProgress(job.id(), 0.25, "Reading your PDF… page 30 of 120");
        repos.jobs().updateProgress(job.id(), 0.3, null);
        assertThat(repos.jobs().find(job.id()).orElseThrow().message()).isEqualTo("Reading your PDF… page 30 of 120");

        repos.jobs().markDone(job.id(), "{\"added\":3}");
        Job done = repos.jobs().find(job.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.progress()).isEqualTo(1.0);
        assertThat(done.message()).isNull();
        assertThat(done.resultJson()).isEqualTo("{\"added\":3}");
        assertThat(repos.jobs().listActive()).isEmpty();
        assertThat(repos.jobs().markCancelled(job.id())).as("finished jobs stay finished").isFalse();

        Job failed = queue(JobKind.INGEST_DOCUMENT, "doc2", 0);
        repos.jobs().markFailed(failed.id(), UserMessage.SOMETHING_WENT_WRONG);
        assertThat(repos.jobs().find(failed.id()).orElseThrow())
                .satisfies(j -> assertThat(j.status()).isEqualTo(JobStatus.FAILED))
                .satisfies(j -> assertThat(j.message()).isEqualTo(UserMessage.SOMETHING_WENT_WRONG.text()));
        Job cancelled = queue(JobKind.INGEST_DOCUMENT, "doc3", 0);
        assertThat(repos.jobs().markCancelled(cancelled.id())).isTrue();
        Job queued = queue(JobKind.INGEST_DOCUMENT, "doc4", 0);
        assertThat(repos.jobs().deleteFinishedBefore(t.now() + 1)).isEqualTo(3);
        assertThat(repos.jobs().find(queued.id())).isPresent();
    }
}
