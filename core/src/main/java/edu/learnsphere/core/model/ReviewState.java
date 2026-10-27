package edu.learnsphere.core.model;

import java.util.Objects;

/**
 * A row of {@code review_states}: FSRS memory state and due date of one item.
 *
 * @param phase         new, learning, review or relearning
 * @param stability     FSRS stability in days (0 for new items)
 * @param difficulty    FSRS difficulty 1–10 (0 for new items)
 * @param dueAt         when the item is next due (epoch millis)
 * @param lastReviewAt  last review, or null
 * @param reps          successful and failed reviews so far
 * @param lapses        times the item was forgotten after being learned
 * @param scheduledDays interval chosen at the last review, in days
 * @param step          0-based position in the learning steps (phase learning) or relearning steps (phase
 *                      relearning); 0 in the other phases
 */
public record ReviewState(
        String itemId,
        ReviewPhase phase,
        double stability,
        double difficulty,
        long dueAt,
        Long lastReviewAt,
        int reps,
        int lapses,
        int scheduledDays,
        int step,
        long createdAt,
        long updatedAt) {

    public ReviewState {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(phase, "phase");
        if (step < 0) {
            throw new IllegalArgumentException("step must be >= 0: " + step);
        }
    }

    /** The state of an item that has never been reviewed, due immediately. */
    public static ReviewState newItem(String itemId, long now) {
        return new ReviewState(itemId, ReviewPhase.NEW, 0, 0, now, null, 0, 0, 0, 0, now, now);
    }

    /** True when reviewed at least once and due at or before {@code time}. */
    public boolean isDue(long time) {
        return phase != ReviewPhase.NEW && dueAt <= time;
    }
}
