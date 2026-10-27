package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.Objects;

/** A row of {@code chat_threads}: a conversation about one course. */
public record ChatThread(String id, String courseId, String title, long createdAt, long updatedAt) {

    public ChatThread {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(title, "title");
    }

    /** A new thread with a fresh id. */
    public static ChatThread create(String courseId, String title, long now) {
        return new ChatThread(Ids.newId(), courseId, title, now, now);
    }
}
