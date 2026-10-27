package edu.learnsphere.core.service;

import java.util.Objects;

/** One learner's imported progress. */
public record LearnerInsight(String insightId, String label, long importedAt, int attempts, Double accuracy) {

    public LearnerInsight {
        Objects.requireNonNull(insightId, "insightId");
        Objects.requireNonNull(label, "label");
    }
}
