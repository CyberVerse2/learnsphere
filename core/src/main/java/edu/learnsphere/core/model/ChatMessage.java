package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.List;
import java.util.Objects;

/**
 * A row of {@code chat_messages}.
 *
 * @param content   the question, or the answer text with {@code [n]} markers
 * @param status      complete, insufficient, error, cancelled or guidance
 * @param citations   sources for the markers in {@code content}; empty for questions
 * @param suggestions topics offered with a guidance or insufficient-evidence reply; empty otherwise
 */
public record ChatMessage(String id, String threadId, ChatRole role, String content, MessageStatus status,
                          List<Citation> citations, List<String> suggestions, long createdAt) {

    public ChatMessage {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(threadId, "threadId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(status, "status");
        citations = citations == null ? List.of() : List.copyOf(citations);
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
    }

    /** A message without suggestions. */
    public ChatMessage(String id, String threadId, ChatRole role, String content, MessageStatus status,
                       List<Citation> citations, long createdAt) {
        this(id, threadId, role, content, status, citations, List.of(), createdAt);
    }

    /** A learner's question. */
    public static ChatMessage question(String threadId, String text, long now) {
        return new ChatMessage(Ids.newId(), threadId, ChatRole.USER, text, MessageStatus.COMPLETE, List.of(), now);
    }

    /** An answer or an insufficient-evidence reply. */
    public static ChatMessage answer(String threadId, String text, MessageStatus status, List<Citation> citations, long now) {
        return new ChatMessage(Ids.newId(), threadId, ChatRole.ASSISTANT, text, status, citations, now);
    }

    /** A guidance or insufficient-evidence reply offering {@code suggestions}. */
    public static ChatMessage reply(String threadId, String text, MessageStatus status, List<String> suggestions, long now) {
        return new ChatMessage(Ids.newId(), threadId, ChatRole.ASSISTANT, text, status, List.of(), suggestions, now);
    }
}
