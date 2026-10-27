package edu.learnsphere.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * A chat completion request. Thinking is always switched off ({@code enable_thinking=false}) and the
 * response is always streamed from the server, so cancelling aborts generation promptly.
 *
 * @param messages       the conversation
 * @param maxTokens      longest reply
 * @param temperature    sampling temperature (0 for the most likely output)
 * @param jsonSchema     when present, the reply is constrained to JSON matching this schema
 * @param schemaName     name sent with the schema
 * @param firstTokenWait longest wait for the first token (prompt reading, including images)
 * @param tokenGap       longest silence between tokens once generation runs
 * @param total          longest time for the whole request
 */
public record ChatRequest(List<ChatMessage> messages, int maxTokens, double temperature, JsonNode jsonSchema,
                          String schemaName, Duration firstTokenWait, Duration tokenGap, Duration total) {

    /** Default limits: first token within 3 minutes, no 60-second gap, 10 minutes in all. */
    public static final Duration FIRST_TOKEN_WAIT = Duration.ofMinutes(3);
    public static final Duration TOKEN_GAP = Duration.ofSeconds(60);
    public static final Duration TOTAL = Duration.ofMinutes(10);

    public ChatRequest {
        messages = List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("A chat request needs messages");
        }
        if (maxTokens < 1) {
            throw new IllegalArgumentException("maxTokens must be >= 1: " + maxTokens);
        }
        if (temperature < 0 || temperature > 2) {
            throw new IllegalArgumentException("temperature must be 0-2: " + temperature);
        }
        if (jsonSchema != null && (schemaName == null || schemaName.isBlank())) {
            schemaName = "response";
        }
        Objects.requireNonNull(firstTokenWait, "firstTokenWait");
        Objects.requireNonNull(tokenGap, "tokenGap");
        Objects.requireNonNull(total, "total");
    }

    /** A plain-text request with the default time limits. */
    public static ChatRequest text(List<ChatMessage> messages, int maxTokens, double temperature) {
        return new ChatRequest(messages, maxTokens, temperature, null, null, FIRST_TOKEN_WAIT, TOKEN_GAP, TOTAL);
    }

    /** A request whose reply must match {@code schema}, with the default time limits. */
    public static ChatRequest json(List<ChatMessage> messages, int maxTokens, double temperature, String name,
                                   JsonNode schema) {
        Objects.requireNonNull(schema, "schema");
        return new ChatRequest(messages, maxTokens, temperature, schema, name, FIRST_TOKEN_WAIT, TOKEN_GAP, TOTAL);
    }

    /** This request with other time limits. */
    public ChatRequest withTimeouts(Duration firstToken, Duration gap, Duration all) {
        return new ChatRequest(messages, maxTokens, temperature, jsonSchema, schemaName, firstToken, gap, all);
    }

    /** True when any message carries an image. */
    public boolean hasImages() {
        return messages.stream().anyMatch(ChatMessage::hasImage);
    }
}
