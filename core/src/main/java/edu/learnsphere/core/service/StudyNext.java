package edu.learnsphere.core.service;

/**
 * The "Study next" card.
 *
 * @param courseId         the course, or null for every enrolled course
 * @param dueCount         reviews due now
 * @param newCount         new items available today (within the daily limit)
 * @param estimatedMinutes rough time for a session of these items
 * @param nextReviewAt     when the next review becomes due, or null; useful when caught up
 */
public record StudyNext(String courseId, int dueCount, int newCount, int estimatedMinutes, Long nextReviewAt) {

    /** True when there is nothing to study right now. */
    public boolean caughtUp() {
        return dueCount == 0 && newCount == 0;
    }

    /** Items a session would hold right now. */
    public int total() {
        return dueCount + newCount;
    }
}
