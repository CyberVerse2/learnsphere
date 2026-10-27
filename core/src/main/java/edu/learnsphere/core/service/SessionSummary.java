package edu.learnsphere.core.service;

import edu.learnsphere.core.model.SessionKind;
import java.util.List;
import java.util.Objects;

/**
 * The end-of-session screen.
 *
 * @param itemCount       items in the session
 * @param answered        items answered
 * @param correct         items answered correctly
 * @param accuracy        correct / answered, 0–1 (0 when nothing was answered)
 * @param durationMs      time from start to finish
 * @param newItemsLearned items seen for the first time
 * @param improved        concepts whose mastery went up, biggest gain first
 * @param nextReviewAt    when the next review is due, or null when nothing is scheduled
 * @param streakDays      consecutive days with at least one review, including today
 */
public record SessionSummary(String sessionId, SessionKind kind, String courseId, int itemCount, int answered, int correct,
                             double accuracy, long durationMs, int newItemsLearned, List<ConceptChange> improved,
                             Long nextReviewAt, int streakDays) {

    public SessionSummary {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(kind, "kind");
        improved = improved == null ? List.of() : List.copyOf(improved);
    }
}
