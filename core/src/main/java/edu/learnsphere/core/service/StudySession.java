package edu.learnsphere.core.service;

import edu.learnsphere.core.model.SessionKind;
import java.util.List;
import java.util.Objects;

/**
 * A started study session with its items in order.
 *
 * @param courseId    the course, or null for a session across courses
 * @param courseTitle the course's title, or null
 * @param moduleTitle the module's title for a module session, or null
 * @param items       items in the order to present them; empty when there is nothing to study
 */
public record StudySession(String id, SessionKind kind, String courseId, String courseTitle, String moduleId,
                           String moduleTitle, List<SessionItem> items, long startedAt) {

    public StudySession {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        items = List.copyOf(items);
    }

    public int size() {
        return items.size();
    }

    /** True when there was nothing to study. */
    public boolean isEmpty() {
        return items.isEmpty();
    }
}
