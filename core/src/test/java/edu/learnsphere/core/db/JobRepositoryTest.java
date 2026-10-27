package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

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
    void claimsHighestPriorityThenOldest() {
        Job background = queue(JobKind.REBUILD_INDEX, null, 0);
        Job userOld = queue(JobKind.GENERATE_ITEMS, "m1", 10);
        Job userNew = queue(JobKind.GENERATE_ITEMS, "m2", 10);

        Job claimed = repos.jobs().claimNext().orElseThrow();
        assertThat(claimed.id()).isEqualTo(userOld.id());
        assertThat(claimed.status()).isEqualTo(JobStatus.RUNNING);
        assertThat(claimed.attempts()).isEqualTo(1);
        assertThat(repos.jobs().claimNext().orElseThrow().id()).isEqualTo(userNew.id());
        assertThat(repos.jobs().claimNext().orElseThrow().id()).isEqualTo(background.id());
        assertThat(repos.jobs().claimNext()).isEmpty();

        assertThat(repos.jobs().requeueRunning()).isEqualTo(3);
        assertThat(repos.jobs().listByStatus(Set.of(JobStatus.QUEUED))).hasSize(3);
    }

    @Test
    void tracksProgressAndCleansUp() {
        Job job = queue(JobKind.INGEST_DOCUMENT, "doc", 0);
        repos.jobs().updateProgress(job.id(), 1.7, "14/120");
        assertThat(repos.jobs().find(job.id()).orElseThrow().progress()).isEqualTo(1.0);
        repos.jobs().updateProgress(job.id(), 0.25, "30/120");
        assertThat(repos.jobs().find(job.id()).orElseThrow().message()).isEqualTo("30/120");

        repos.jobs().updateStatus(job.id(), JobStatus.DONE, null);
        Job done = repos.jobs().find(job.id()).orElseThrow();
        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.progress()).isEqualTo(1.0);
        assertThat(repos.jobs().listActive()).isEmpty();

        Job failed = queue(JobKind.INGEST_DOCUMENT, "doc2", 0);
        repos.jobs().updateStatus(failed.id(), JobStatus.FAILED, "damaged");
        Job queued = queue(JobKind.INGEST_DOCUMENT, "doc3", 0);
        assertThat(repos.jobs().deleteFinishedBefore(t.now() + 1)).isEqualTo(2);
        assertThat(repos.jobs().find(queued.id())).isPresent();
    }
}
