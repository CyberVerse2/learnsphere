package edu.learnsphere.core.learning;

import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.Rating;
import java.util.List;
import java.util.Objects;

/**
 * The result of grading one answer.
 *
 * @param correct        whether it counts as correct
 * @param score          0–1
 * @param rating         the scheduling rating
 * @param gradedBy       rule, study assistant or self
 * @param graderFeedback the study assistant's feedback, or null
 * @param missingPoints  key points the answer left out (study assistant only)
 */
public record GradeOutcome(boolean correct, double score, Rating rating, GradedBy gradedBy, String graderFeedback,
                           List<String> missingPoints) {

    public GradeOutcome {
        Objects.requireNonNull(rating, "rating");
        Objects.requireNonNull(gradedBy, "gradedBy");
        if (!(score >= 0 && score <= 1)) {
            throw new IllegalArgumentException("score must be 0-1: " + score);
        }
        missingPoints = missingPoints == null ? List.of() : List.copyOf(missingPoints);
    }
}
