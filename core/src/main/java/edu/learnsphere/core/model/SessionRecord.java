package edu.learnsphere.core.model;

import java.util.Objects;

/**
 * A row of {@code study_sessions}. (The service-level, view-ready session is
 * {@code edu.learnsphere.core.service.StudySession}.)
 *
 * @param courseId course studied, or null for a session across courses
 * @param moduleId module quizzed, or null
 * @param endedAt  when the session was finished, or null while open
 */
public record SessionRecord(String id, String courseId, String moduleId, SessionKind kind, long startedAt, Long endedAt,
                            long createdAt, long updatedAt) {

    public SessionRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
    }

    /** True once finished. */
    public boolean isFinished() {
        return endedAt != null;
    }
}
