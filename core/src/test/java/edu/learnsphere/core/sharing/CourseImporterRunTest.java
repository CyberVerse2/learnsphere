package edu.learnsphere.core.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.ExportOptions;
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
    void rerunOfAJobThatStoredTheCourseReturnsWhatItRecordedAndIndexesItAgain() {
        TestJob first = new TestJob(1);
        ImportResult created = importer.run(file, null, false, first);
        learner.index.deleteByCourse(created.courseId());

        TestJob rerun = new TestJob(2, first.job.id());
        ImportResult again = importer.run(file, null, false, rerun);

        assertThat(again).isEqualTo(created);
        assertThat(again.outcome()).isEqualTo(ImportOutcome.CREATED);
        assertThat(learner.t.repos.courses().list()).hasSize(1);
        assertThat(learner.index.chunkIdsOfCourse(created.courseId())).hasSize(3);
        assertThat(rerun.fractions).isSorted().last().isEqualTo(1.0);
    }

    @Test
    void protectedRerunFinishesWithoutTheCodeOnceTheCourseIsStored() {
        Path protectedFile = exportProtected(creator.createCourse(Visibility.PROTECTED), "protected-v1.learnsphere");
        TestJob first = new TestJob(1);
        ImportResult created;
        try (PackageKey key = unlock(protectedFile)) {
            created = importer.run(protectedFile, key, false, first);
        }
        learner.index.deleteByCourse(created.courseId());

        ImportResult resumed = importer.run(protectedFile, null, false, new TestJob(2, first.job.id()));

        assertThat(resumed).isEqualTo(created);
        assertThat(learner.index.chunkIdsOfCourse(created.courseId())).hasSize(3);

        // Another job whose key was lost before it stored anything: the course already has this version.
        learner.index.deleteByCourse(created.courseId());
        ImportResult current = importer.run(protectedFile, null, false, new TestJob(2));

        assertThat(current.outcome()).isEqualTo(ImportOutcome.ALREADY_CURRENT);
        assertThat(current.courseId()).isEqualTo(created.courseId());
        assertThat(current.added() + current.updated() + current.retired()).isZero();
        assertThat(learner.index.chunkIdsOfCourse(created.courseId())).hasSize(3);
    }

    @Test
    void protectedRerunOfAnotherVersionWithoutTheCodeAsksToImportAgain() {
        Made made = creator.createCourse(Visibility.PROTECTED);
        Path v1 = exportProtected(made, "protected-v1.learnsphere");
        ImportResult created;
        try (PackageKey key = unlock(v1)) {
            created = importer.run(v1, key, false, new TestJob(1));
        }
        creator.publish(made.course(), "Second release", made.mcq(), made.shortAnswer());
        Path v2 = exportProtected(made, "protected-v2.learnsphere");

        assertThatThrownBy(() -> importer.run(v2, null, false, new TestJob(2)))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(((LearnSphereException) e).userMessage())
                        .isEqualTo(SharingMessages.IMPORT_INTERRUPTED));
        assertThat(learner.t.repos.courses().get(created.courseId()).versionNumber()).isEqualTo(1);
    }

    @Test
    void runBecomesUncancellableRightBeforeStoringTheCourse() {
        TestJob job = new TestJob(1);
        job.cancelOn("Finishing up…");

        ImportResult result = importer.run(file, null, false, job);

        assertThat(job.uncancellableAt).isEqualTo("Adding the course to your library…");
        assertThat(job.coursesWhenUncancellable).isZero();
        assertThat(job.token.isCancelled()).as("the cancel came too late").isFalse();
        assertThat(result.outcome()).isEqualTo(ImportOutcome.CREATED);
        assertThat(learner.index.chunkIdsOfCourse(result.courseId())).hasSize(3);
    }

    @Test
    void cancelJustBeforeStoringStopsTheRun() {
        TestJob job = new TestJob(1);
        job.cancelOn("Adding the course to your library…");

        assertThatThrownBy(() -> importer.run(file, null, false, job)).isInstanceOf(CancellationException.class);

        assertThat(job.uncancellableAt).isNull();
        assertThat(learner.t.repos.courses().list()).isEmpty();
        assertThat(learner.storedPdfs()).isEmpty();
        assertThat(learner.index.size()).isZero();
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

    private Path exportProtected(Made made, String name) {
        Path target = dir.resolve(name);
        creator.sharing.exportCourse(made.course().id(), target, ExportOptions.protectedWith("orchid".toCharArray()));
        return target;
    }

    private static PackageKey unlock(Path file) {
        try (PackageReader reader = PackageReader.open(file)) {
            return reader.unlock("orchid".toCharArray());
        }
    }

    /**
     * A running import job with a given attempt number that records its progress. Like the job queue, it
     * ignores a cancel once the handler marked it uncancellable.
     */
    private final class TestJob implements JobContext {

        final CancellationToken token = CancellationToken.create();
        final List<String> messages = new ArrayList<>();
        final List<Double> fractions = new ArrayList<>();
        final Job job;
        private String cancelOn;
        String uncancellableAt;
        int coursesWhenUncancellable = -1;

        TestJob(int attempts) {
            this(attempts, Ids.newId());
        }

        TestJob(int attempts, String jobId) {
            job = new Job(jobId, JobKind.IMPORT_PACKAGE, "{}", JobStatus.RUNNING, 10, attempts, 0, null, null, null, 0, 0);
        }

        /** Requests a cancel when the run first reports {@code message}. */
        void cancelOn(String message) {
            cancelOn = message;
        }

        @Override
        public boolean markUncancellable() {
            if (token.isCancelled()) {
                return false;
            }
            uncancellableAt = messages.isEmpty() ? null : messages.getLast();
            coursesWhenUncancellable = learner.t.repos.courses().list().size();
            return true;
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
            if (message != null && message.equals(cancelOn) && uncancellableAt == null) {
                token.cancel();
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
