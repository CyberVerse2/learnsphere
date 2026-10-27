package edu.learnsphere.core.model;

import java.util.Objects;

/**
 * A row of {@code attempts}: one answer to one item in a session.
 *
 * @param response   the learner's answer text (chosen option text, typed answer, or the rating for flashcards)
 * @param correct    whether it counted as correct
 * @param score      0–1 (1 or 0 for rule-graded items)
 * @param rating     scheduling rating derived from the result
 * @param confidence learner's confidence, or null (flashcards)
 * @param hintUsed   whether a hint was shown
 * @param durationMs time from showing the item to answering
 * @param gradedBy   rule, AI or self
 * @param feedback   grader feedback (AI-graded short answers), or null
 * @param disputed   true once the learner disputed an AI grade
 */
public record Attempt(
        String id,
        String itemId,
        String sessionId,
        String response,
        boolean correct,
        double score,
        Rating rating,
        Confidence confidence,
        boolean hintUsed,
        long durationMs,
        GradedBy gradedBy,
        String feedback,
        boolean disputed,
        long createdAt,
        long updatedAt) {

    public Attempt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(sessionId, "sessionId");
        response = Objects.requireNonNullElse(response, "");
        Objects.requireNonNull(rating, "rating");
        Objects.requireNonNull(gradedBy, "gradedBy");
        if (score < 0 || score > 1) {
            throw new IllegalArgumentException("score must be 0-1: " + score);
        }
    }
}
