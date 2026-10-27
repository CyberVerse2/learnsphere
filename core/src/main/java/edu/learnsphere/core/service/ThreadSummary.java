package edu.learnsphere.core.service;

import java.util.Objects;

/**
 * A conversation in the Ask screen's list.
 *
 * @param lastMessage   the latest message's text (first line, trimmed), or empty
 * @param lastMessageAt when the latest message was written, or null for an empty thread
 */
public record ThreadSummary(String id, String courseId, String title, int messageCount, String lastMessage,
                            Long lastMessageAt, long updatedAt) {

    public ThreadSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        title = Objects.requireNonNullElse(title, "");
        lastMessage = Objects.requireNonNullElse(lastMessage, "");
    }
}
