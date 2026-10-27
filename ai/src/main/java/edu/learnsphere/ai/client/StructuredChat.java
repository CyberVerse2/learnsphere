package edu.learnsphere.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import edu.learnsphere.ai.AiMessages;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Json;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a chat request whose reply is constrained to a JSON schema and parses it. A reply that does not
 * parse (cut off at the token limit) is retried once with a larger limit.
 */
final class StructuredChat {

    private static final Logger LOG = LoggerFactory.getLogger(StructuredChat.class);

    /** Structured replies received since start-up, and how many of them did not parse (diagnostics). */
    static final AtomicLong REPLIES = new AtomicLong();
    static final AtomicLong INVALID_REPLIES = new AtomicLong();

    private StructuredChat() {
    }

    static JsonNode complete(LocalChat chat, ChatPriority priority, List<ChatMessage> messages, String name,
                             JsonNode schema, int maxTokens, double temperature, CancellationToken token) {
        int limit = maxTokens;
        for (int attempt = 0; attempt < 2; attempt++) {
            ChatRequest request = ChatRequest.json(messages, limit, temperature, name, schema);
            ChatResult result = chat.complete(priority, request, TokenSink.NONE, token);
            REPLIES.incrementAndGet();
            JsonNode parsed = parse(result.text());
            if (parsed != null) {
                return parsed;
            }
            INVALID_REPLIES.incrementAndGet();
            LOG.warn("The {} reply was not complete JSON ({}, {} tokens); {}", name, result.finishReason(),
                    result.completionTokens(), attempt == 0 ? "trying again" : "giving up");
            limit = limit * 2;
        }
        throw new LearnSphereException(AiMessages.REQUEST_FAILED, "No valid " + name + " JSON after two attempts");
    }

    /** The parsed object, or null when {@code text} is not one complete JSON object. */
    static JsonNode parse(String text) {
        String body = text == null ? "" : text.strip();
        if (body.startsWith("```")) {
            body = body.replaceFirst("^```[a-zA-Z]*\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            JsonNode node = Json.mapper().readTree(body);
            return node != null && node.isObject() ? node : null;
        } catch (IOException e) {
            return null;
        }
    }
}
