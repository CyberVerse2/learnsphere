package edu.learnsphere.core.service;

import java.util.Objects;

/**
 * How well the learner knows a concept.
 *
 * @param moduleId          its module, or null
 * @param mastery           0–1 (mean retrievability of reviewed items blended with recent accuracy), or null
 *                          before any of its items has been reviewed
 * @param accuracy          share correct over its last 10 attempts, or null without attempts
 * @param itemCount         approved items tagged with it
 * @param reviewedItemCount of those, items reviewed at least once
 * @param attemptCount      attempts on its items
 */
public record ConceptMastery(String conceptId, String name, String moduleId, Double mastery, Double accuracy, int itemCount,
                             int reviewedItemCount, int attemptCount) {

    /** Mastery below this marks a weak concept. */
    public static final double WEAK_THRESHOLD = 0.6;

    public ConceptMastery {
        Objects.requireNonNull(conceptId, "conceptId");
        Objects.requireNonNull(name, "name");
    }

    public boolean hasMastery() {
        return mastery != null;
    }

    /** True when it has mastery below {@link #WEAK_THRESHOLD}. */
    public boolean isWeak() {
        return mastery != null && mastery < WEAK_THRESHOLD;
    }
}
