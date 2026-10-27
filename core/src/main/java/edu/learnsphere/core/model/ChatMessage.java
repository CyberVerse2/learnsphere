package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.List;
import java.util.Objects;

/**
 * A row of {@code chat_messages}.
 *
 * @param content   the question, or the answer text with {@code [n]} markers
 * @param status    complete, insufficient, error or cancelled
 * @param citations sources for the markers in {@code content}; empty for questions
 */
public record ChatMessage(String id, String threadId, ChatRole role, String content, MessageStatus status,
                          List<Citation> citations, long createdAt) {

    public ChatMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(status, "status");
        citations = citations == null ? List.of() : List.copyOf(citations);
    }

    /** A learner's question. */
    public static ChatMessage question(String threadId, String text, long now) {
        return new ChatMessage(Ids.newId(), threadId, ChatRole.USER, text, MessageStatus.COMPLETE, List.of(), now);
    }

    /** An answer or an insufficient-evidence reply. */
    public static ChatMessage answer(String threadId, String text, MessageStatus status, List<Citation> citations, long now) {
        return new ChatMessage(Ids.newId(), threadId, ChatRole.ASSISTANT, text, status, citations, now);
    }
}
