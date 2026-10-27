package edu.learnsphere.core.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.ImportOutcome;
import edu.learnsphere.core.service.ImportResult;
import edu.learnsphere.core.service.JobContext;
import edu.learnsphere.core.sharing.SharingProfile.Made;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Ids;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The import job's handler body, run directly with a hand-made job. */
class CourseImporterRunTest {

    @TempDir
    Path dir;

    private SharingProfile creator;
    private SharingProfile learner;
    private CourseImporter importer;
    private Made made;
    private Path file;

    @BeforeEach
    void setUp() {
        creator = SharingProfile.open(dir.resolve("creator"));
        learner = SharingProfile.open(dir.resolve("learner"));
        importer = new CourseImporter(learner.t.repos, learner.paths, learner.events, learner.t.clock, learner.index,
                learner.embedder);
        made = creator.createCourse(Visibility.SHAREABLE);
        file = creator.export(made.course(), dir.resolve("course.learnsphere"));
    }

    @AfterEach
    void tearDown() {
        learner.close();
        creator.close();
    }

    @Test
    void repeatedRunAfterAnInterruptionFindsTheCourseStoredAndIndexesItAgain() {
        ImportResult first = importer.run(file, null, false, new TestJob(1));
        learner.index.deleteByCourse(first.courseId());

        TestJob rerun = new TestJob(2);
        ImportResult second = importer.run(file, null, false, rerun);

        assertThat(second.outcome()).isEqualTo(ImportOutcome.ALREADY_CURRENT);
        assertThat(second.courseId()).isEqualTo(first.courseId());
        assertThat(learner.t.repos.courses().list()).hasSize(1);
        assertThat(learner.index.chunkIdsOfCourse(first.courseId())).hasSize(3);
        assertThat(rerun.messages).first().isEqualTo("Checking the course…");
        assertThat(rerun.messages).contains("Finishing up…");
        assertThat(rerun.fractions).isSorted();
    }

    @Test
    void cancelledRunStoresNothing() {
        TestJob job = new TestJob(1);
        job.token.cancel();

        assertThatThrownBy(() -> importer.run(file, null, false, job)).isInstanceOf(CancellationException.class);

        assertThat(learner.t.repos.courses().list()).isEmpty();
        assertThat(learner.storedPdfs()).isEmpty();
        assertThat(learner.index.size()).isZero();
    }

    @Test
    void progressNamesThePdfsBeingCopied() {
        TestJob job = new TestJob(1);

        importer.run(file, null, false, job);

        assertThat(job.messages).contains("Copying PDFs… 1 of 2", "Copying PDFs… 2 of 2",
                "Adding the course to your library…");
        assertThat(job.fractions).isSorted().last().isEqualTo(1.0);
    }

    /** A running import job with a given attempt number that records its progress. */
    private static final class TestJob implements JobContext {

        final CancellationToken token = CancellationToken.create();
        final List<String> messages = new ArrayList<>();
        final List<Double> fractions = new ArrayList<>();
        private final Job job;

        TestJob(int attempts) {
            job = new Job(Ids.newId(), JobKind.IMPORT_PACKAGE, "{}", JobStatus.RUNNING, 10, attempts, 0, null, null, null,
                    0, 0);
        }

        @Override
        public Job job() {
            return job;
        }

        @Override
        public <T> T payload(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void progress(double fraction, String message) {
            fractions.add(fraction);
            if (message != null && (messages.isEmpty() || !messages.getLast().equals(message))) {
                messages.add(message);
            }
        }

        @Override
        public void setResult(Object result) {
        }

        @Override
        public CancellationToken token() {
            return token;
        }
    }
}
