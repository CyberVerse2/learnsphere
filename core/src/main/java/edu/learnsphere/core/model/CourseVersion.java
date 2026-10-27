package edu.learnsphere.core.model;

import java.util.Objects;

/**
 * A row of {@code course_versions}: a published, frozen set of approved items.
 *
 * @param number      1, 2, 3… per course
 * @param notes       what changed, written by the creator; may be empty
 * @param publishedAt when it was published
 */
public record CourseVersion(String id, String courseId, int number, String notes, long publishedAt, long createdAt) {

    public CourseVersion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        notes = Objects.requireNonNullElse(notes, "");
        if (number < 1) {
            throw new IllegalArgumentException("version number must be >= 1: " + number);
        }
    }
}
