package edu.learnsphere.core.service;

import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Visibility;
import java.util.Objects;

/**
 * Everything a course card, sidebar entry or library row shows, in one object.
 *
 * @param createdHere               true for courses made in this profile (editable in the Studio)
 * @param enrolled                  true while the learner studies it
 * @param versionNumber             latest published version, 0 when never published
 * @param itemCount                 approved items (what learners study)
 * @param draftItemCount            items waiting for review
 * @param newItemCount              approved items never studied
 * @param dueCount                  items due for review now
 * @param documentsProcessing       PDFs still being imported
 * @param documentsNeedingAttention PDFs waiting for page review or that failed
 * @param openFlagCount             unresolved flags on its items
 * @param progress                  share of approved items studied at least once, 0–1 (drives the progress ring)
 * @param lastOpenedAt              when the learner last opened it, or null
 */
public record CourseSummary(
        String id,
        String title,
        String subtitle,
        String authorName,
        int coverHue,
        CourseOrigin origin,
        CourseStatus status,
        Visibility visibility,
        int versionNumber,
        boolean createdHere,
        boolean enrolled,
        int moduleCount,
        int documentCount,
        int documentsProcessing,
        int documentsNeedingAttention,
        int itemCount,
        int draftItemCount,
        int newItemCount,
        int dueCount,
        int openFlagCount,
        double progress,
        Long lastOpenedAt,
        long updatedAt) {

    public CourseSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        subtitle = Objects.requireNonNullElse(subtitle, "");
        authorName = Objects.requireNonNullElse(authorName, "");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(visibility, "visibility");
    }

    /** True when archived. */
    public boolean isArchived() {
        return status == CourseStatus.ARCHIVED;
    }

    /** True when there is something to study right now. */
    public boolean hasWorkToday() {
        return dueCount > 0 || newItemCount > 0;
    }
}
