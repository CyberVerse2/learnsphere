package edu.learnsphere.ai.client;

import java.time.Duration;
import java.util.Objects;

/**
 * The finished reply of a chat request.
 *
 * @param text             everything generated
 * @param finishReason     {@code stop}, {@code length} (cut off at the token limit), or another server reason
 * @param promptTokens     tokens read (0 when the server did not say)
 * @param completionTokens tokens generated (0 when the server did not say)
 * @param firstToken       time until the first generated text arrived
 * @param elapsed          time for the whole request
 */
public record ChatResult(String text, String finishReason, int promptTokens, int completionTokens, Duration firstToken,
                         Duration elapsed) {

    public ChatResult {
        Objects.requireNonNull(text, "text");
        finishReason = Objects.requireNonNullElse(finishReason, "stop");
        Objects.requireNonNull(firstToken, "firstToken");
        Objects.requireNonNull(elapsed, "elapsed");
    }

    /** True when generation stopped at the token limit rather than finishing. */
    public boolean truncated() {
        return "length".equals(finishReason);
    }

    /** Generated tokens per second after the first token, or 0 when unknown. */
    public double tokensPerSecond() {
        long millis = elapsed.minus(firstToken).toMillis();
        return completionTokens <= 1 || millis <= 0 ? 0 : (completionTokens - 1) * 1000.0 / millis;
    }
}
