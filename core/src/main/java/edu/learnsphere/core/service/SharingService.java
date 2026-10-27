package edu.learnsphere.core.service;

import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Sharing courses, progress and feedback as files. Methods taking an id throw {@link NotFoundException}
 * when it does not exist; unreadable files throw {@link InvalidInputException}.
 */
public interface SharingService {

    /** Extension of course packages. */
    String COURSE_EXTENSION = "learnsphere";
    /** Extension of a learner's progress export. */
    String PROGRESS_EXTENSION = "lsprogress";
    /** Extension of a learner's feedback export. */
    String FEEDBACK_EXTENSION = "lsfeedback";

    /**
     * Writes the latest published version of a course to {@code target}.
     *
     * @throws ConflictException     when the course is private or has never been published
     * @throws InvalidInputException when a protected course is exported without a code
     */
    PackageInfo exportCourse(String courseId, Path target, ExportOptions options);

    /**
     * Reads a package's details without importing it. {@link PackageInfo#createdHere()} tells the UI before
     * importing that the package is a course made in this profile, which {@link #importCourse} rejects.
     */
    PackageInfo inspect(Path packageFile);

    /**
     * Imports a package as a new course, or updates the matching course in place (items keep their ids,
     * new ones are added, missing ones retired, progress kept). The learner is enrolled.
     *
     * <p>The package and the code are checked before this returns; copying the PDFs, storing passages and
     * indexing them then run as a {@link edu.learnsphere.core.model.JobKind#IMPORT_PACKAGE} job with
     * progress and cancellation (follow {@link edu.learnsphere.core.events.JobProgress}). When the job is
     * done, {@link #importResult} returns what it did. The code is kept in memory only, so a protected import
     * interrupted by quitting LearnSphere before the course was stored ends as failed and is started again by
     * the learner.
     *
     * @param code the code for a protected package, or null (the service wipes the array after use)
     * @return the queued job
     * @throws InvalidInputException when the file is not a course package, or the code is missing or wrong
     * @throws ConflictException     when the package's {@code package_id} belongs to a course created in this
     *                               profile (origin local): importing it here would clash with that
     *                               course's own items
     */
    JobUpdate importCourse(Path packageFile, char[] code);

    /**
     * {@link #importCourse(Path, char[])}, optionally allowing a package whose version is older than the
     * copy in the library ({@link PackageInfo#isOlderThanInstalled()}): without that, such a package is
     * refused with a {@link ConflictException} so a learner never goes back a version by accident.
     *
     * <p>The default ignores {@code allowOlderVersion}: an implementation that cannot go back to an older
     * version refuses one exactly as {@link #importCourse(Path, char[])} does.
     *
     * @param allowOlderVersion true when the learner confirmed going back to the package's version
     */
    default JobUpdate importCourse(Path packageFile, char[] code, boolean allowOlderVersion) {
        return importCourse(packageFile, code);
    }

    /**
     * What a finished {@link #importCourse} job did; empty while it runs, or when it failed or was
     * cancelled.
     */
    Optional<ImportResult> importResult(String jobId);

    /** Writes this learner's progress in a course under {@code learnerLabel}, for the creator. */
    void exportProgress(String courseId, String learnerLabel, Path target);

    /** Imports learners' progress files into a course created here; a learner label imported again replaces the old one. */
    ClassInsightSummary importClassProgress(String courseId, List<Path> files);

    /** Class insights for a course created here. */
    ClassInsightSummary classInsights(String courseId);

    /** Removes one learner's imported progress. */
    void removeClassInsight(String insightId);

    /** Writes the learner's flags on a course's items, for its creator. */
    void exportFeedback(String courseId, Path target);

    /** Adds a learner's flags to the matching course created here. */
    ImportResult importFeedback(Path file);
}
