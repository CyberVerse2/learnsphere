package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.Objects;

/**
 * A row of {@code modules}: an ordered part of a course. (Named {@code CourseModule} to stay clear of
 * {@link java.lang.Module}.)
 *
 * @param summary  short description, may be empty
 * @param position 0-based order within the course
 */
public record CourseModule(String id, String courseId, String title, String summary, int position, long createdAt, long updatedAt) {

    public CourseModule {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(title, "title");
        summary = Objects.requireNonNullElse(summary, "");
    }

    /** A new module with a fresh id. */
    public static CourseModule create(String courseId, String title, int position, long now) {
        return new CourseModule(Ids.newId(), courseId, title, "", position, now, now);
    }
}
