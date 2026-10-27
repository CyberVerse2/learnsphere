package edu.learnsphere.core.events;

import java.util.Objects;

/** A course, its modules, versions or enrolment changed. Reload course cards and headers. */
public record CourseChanged(String courseId, ChangeType type) implements Event {

    public CourseChanged {
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(type, "type");
    }
}
