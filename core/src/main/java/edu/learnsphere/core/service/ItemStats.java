package edu.learnsphere.core.service;

import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.ReviewPhase;
import java.util.Objects;

/**
 * How an item performs, for creators: this profile's attempts plus imported class insights.
 *
 * @param accuracy          correct / attempts, or null without attempts
 * @param averageScore      mean score, or null
 * @param averageDurationMs mean answering time, or null
 * @param lastAttemptAt     latest attempt, or null
 * @param phase             scheduling phase in this profile, or null when never seen
 * @param dueAt             next due time, or null
 * @param stability         FSRS stability in days, or null
 * @param classLearners     learners in imported class insights who attempted it
 * @param classAccuracy     their accuracy, or null
 */
public record ItemStats(String itemId, ItemType type, String prompt, ItemStatus status, int attempts, int correct,
                        Double accuracy, Double averageScore, Long averageDurationMs, Long lastAttemptAt,
                        ReviewPhase phase, Long dueAt, Double stability, int lapses, int openFlags, int classLearners,
                        Double classAccuracy) {

    public ItemStats {
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(prompt, "prompt");
        Objects.requireNonNull(status, "status");
    }
}
