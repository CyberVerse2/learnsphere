package edu.learnsphere.core.model;

import java.util.Objects;

/**
 * A row of {@code class_insights}: one learner's imported progress summary for a course.
 *
 * @param learnerLabel the name the learner chose when exporting
 * @param payloadJson  per-item attempt summaries as JSON (format owned by {@code SharingService})
 * @param importedAt   when it was imported
 */
public record ClassInsight(String id, String courseId, String learnerLabel, String payloadJson, long importedAt, long createdAt) {

    public ClassInsight {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(learnerLabel, "learnerLabel");
        Objects.requireNonNull(payloadJson, "payloadJson");
    }
}
