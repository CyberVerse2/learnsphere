package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.Objects;

/**
 * A row of {@code concepts}: a key idea of a module that items are tagged with and mastery is measured on.
 *
 * @param moduleId    module it belongs to, or null for course-wide concepts
 * @param description one or two sentences, may be empty
 */
public record Concept(String id, String courseId, String moduleId, String name, String description, long createdAt, long updatedAt) {

    public Concept {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(name, "name");
        description = Objects.requireNonNullElse(description, "");
    }

    /** A new concept with a fresh id. */
    public static Concept create(String courseId, String moduleId, String name, String description, long now) {
        return new Concept(Ids.newId(), courseId, moduleId, name, description, now, now);
    }
}
