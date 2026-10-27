package edu.learnsphere.core.model;

import java.util.Objects;

/** A row of {@code enrolments}: the learner studying a course (at most one row per course). */
public record Enrolment(String id, String courseId, long enrolledAt, EnrolmentStatus status, long createdAt, long updatedAt) {

    public Enrolment {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(status, "status");
    }

    /** True while the learner is studying the course. */
    public boolean isActive() {
        return status == EnrolmentStatus.ACTIVE;
    }
}
