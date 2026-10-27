package edu.learnsphere.core.service;

import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import java.nio.file.Path;
import java.util.List;

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

    /** Reads a package's details without importing it. */
    PackageInfo inspect(Path packageFile);

    /**
     * Imports a package as a new course, or updates the matching course in place (items keep their ids,
     * new ones are added, missing ones retired, progress kept). The learner is enrolled.
     *
     * @param code the code for a protected package, or null
     * @throws InvalidInputException when the code is missing or wrong
     */
    ImportResult importCourse(Path packageFile, char[] code);

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
