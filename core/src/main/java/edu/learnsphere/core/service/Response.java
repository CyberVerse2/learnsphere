package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.Rating;
import java.util.Objects;

/**
 * The learner's answer to one session item.
 *
 * @param answer     the chosen option's text, {@code "true"}/{@code "false"}, typed text, or empty for flashcards
 * @param selfRating the learner's own rating, for flashcards only
 * @param confidence how sure they were, or null (flashcards)
 * @param hintUsed   whether they looked at a hint
 * @param durationMs time from showing the item to answering
 */
public record Response(String itemId, String answer, Rating selfRating, Confidence confidence, boolean hintUsed,
                       long durationMs) {

    public Response {
        Objects.requireNonNull(itemId, "itemId");
        answer = Objects.requireNonNullElse(answer, "");
        if (durationMs < 0) {
            throw new IllegalArgumentException("durationMs must be >= 0");
        }
    }

    /** An answer to a multiple-choice, true/false, cloze or short-answer item. */
    public static Response answer(String itemId, String answer, Confidence confidence, boolean hintUsed, long durationMs) {
        return new Response(itemId, answer, null, confidence, hintUsed, durationMs);
    }

    /** A self-rated flashcard. */
    public static Response flashcard(String itemId, Rating rating, long durationMs) {
        return new Response(itemId, "", Objects.requireNonNull(rating, "rating"), null, false, durationMs);
    }
}
