package edu.learnsphere.core.service;

import java.util.Objects;

/**
 * The result of importing a course package or a feedback file.
 *
 * @param added   new items (or flags, for feedback)
 * @param updated items whose content changed
 * @param retired items no longer in the package, taken out of study
 */
public record ImportResult(ImportOutcome outcome, String courseId, String courseTitle, int versionNumber, int added,
                           int updated, int retired) {

    public ImportResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(courseId, "courseId");
        courseTitle = Objects.requireNonNullElse(courseTitle, "");
    }
}
