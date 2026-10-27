package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.security.PasswordHasher;
import edu.learnsphere.core.service.ClassInsightSummary;
import edu.learnsphere.core.service.ExportOptions;
import edu.learnsphere.core.service.ImportResult;
import edu.learnsphere.core.service.JobHandler;
import edu.learnsphere.core.service.JobQueue;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.PackageInfo;
import edu.learnsphere.core.service.SharingService;
import edu.learnsphere.core.sharing.CourseExporter;
import edu.learnsphere.core.sharing.CourseImporter;
import edu.learnsphere.core.sharing.FeedbackSharing;
import edu.learnsphere.core.sharing.ImportJob;
import edu.learnsphere.core.sharing.PackageKey;
import edu.learnsphere.core.sharing.PendingKeys;
import edu.learnsphere.core.sharing.ProgressSharing;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.util.Json;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link SharingService} over the sharing package: course packages ({@link CourseExporter},
 * {@link CourseImporter}), progress and class insights ({@link ProgressSharing}) and feedback
 * ({@link FeedbackSharing}).
 *
 * <p>Importing checks the package and the code on the caller's thread, then queues an
 * {@link JobKind#IMPORT_PACKAGE} job run by {@link #importHandler()}, which the workspace registers. The key
 * of a protected package stays in memory ({@link PendingKeys}) and is wiped when the job takes it, when
 * the job ends without running, or on {@link #close()}. Thread-safe.
 */
public final class SharingServiceImpl implements SharingService, AutoCloseable {

    private final JobQueue jobs;
    private final CourseExporter exporter;
    private final CourseImporter importer;
    private final ProgressSharing progress;
    private final FeedbackSharing feedback;
    private final PendingKeys keys = new PendingKeys();

    public SharingServiceImpl(Repositories repos, AppPaths.ProfilePaths paths, EventBus events, Clock clock, JobQueue jobs,
                              SearchIndex index, Embedder embedder) {
        this.jobs = Objects.requireNonNull(jobs, "jobs");
        this.exporter = new CourseExporter(repos, paths, clock);
        this.importer = new CourseImporter(repos, paths, events, clock, index, embedder);
        this.progress = new ProgressSharing(repos, events, clock);
        this.feedback = new FeedbackSharing(repos, events, clock);
    }

    /** The handler of {@link JobKind#IMPORT_PACKAGE} jobs; register it with the job queue. */
    public JobHandler importHandler() {
        return job -> {
            ImportJob payload = job.payload(ImportJob.class);
            // Null after a restart (keys live in memory only): the importer then finishes a run that had
            // already stored the course, and otherwise fails, asking the learner to import again.
            PackageKey key = keys.take(payload.keyRef());
            try {
                job.setResult(importer.run(Path.of(payload.file()), key, payload.allowOlder(), job));
            } finally {
                if (key != null) {
                    key.close();
                }
            }
        };
    }

    @Override
    public PackageInfo exportCourse(String courseId, Path target, ExportOptions options) {
        return exporter.export(courseId, target, options);
    }

    @Override
    public PackageInfo inspect(Path packageFile) {
        return importer.inspect(Objects.requireNonNull(packageFile, "packageFile").toAbsolutePath());
    }

    @Override
    public JobUpdate importCourse(Path packageFile, char[] code) {
        return importCourse(packageFile, code, false);
    }

    @Override
    public JobUpdate importCourse(Path packageFile, char[] code, boolean allowOlderVersion) {
        try {
            Path file = Objects.requireNonNull(packageFile, "packageFile").toAbsolutePath();
            CourseImporter.Checked checked = importer.check(file, code, allowOlderVersion);
            keys.purge(this::isActive);
            String ref = checked.key() == null ? null : keys.put(checked.key());
            try {
                JobUpdate update = jobs.enqueue(new JobSpec(JobKind.IMPORT_PACKAGE, checked.info().existingCourseId(),
                        Json.write(new ImportJob(file.toString(), ref, allowOlderVersion)), JobSpec.USER_PRIORITY, false));
                if (ref != null) {
                    keys.attach(ref, update.jobId());
                }
                return update;
            } catch (RuntimeException e) {
                keys.discard(ref);
                throw e;
            }
        } finally {
            PasswordHasher.wipe(code);
        }
    }

    private boolean isActive(String jobId) {
        return jobs.find(jobId)
                .map(u -> u.status() == JobStatus.QUEUED || u.status() == JobStatus.RUNNING)
                .orElse(false);
    }

    @Override
    public Optional<ImportResult> importResult(String jobId) {
        return jobs.result(jobId, ImportResult.class);
    }

    @Override
    public void exportProgress(String courseId, String learnerLabel, Path target) {
        progress.exportProgress(courseId, learnerLabel, target);
    }

    @Override
    public ClassInsightSummary importClassProgress(String courseId, List<Path> files) {
        return progress.importClassProgress(courseId, files);
    }

    @Override
    public ClassInsightSummary classInsights(String courseId) {
        return progress.classInsights(courseId);
    }

    @Override
    public void removeClassInsight(String insightId) {
        progress.removeClassInsight(insightId);
    }

    @Override
    public void exportFeedback(String courseId, Path target) {
        feedback.exportFeedback(courseId, target);
    }

    @Override
    public ImportResult importFeedback(Path file) {
        return feedback.importFeedback(file);
    }

    /** Wipes the keys of protected packages still waiting for their import. */
    @Override
    public void close() {
        keys.close();
    }
}
