package edu.learnsphere.core.service;

import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.Rating;
import java.util.List;
import java.util.Objects;

/**
 * What the study screen shows after an answer.
 *
 * @param score          0–1
 * @param rating         the scheduling rating it produced
 * @param gradedBy       rule, AI ("Graded by your study assistant") or self
 * @param correctAnswer  the right answer (option text, "true"/"false", the missing word, or the reference answer)
 * @param explanation    why, may be empty
 * @param graderFeedback feedback from the AI grader, or null
 * @param missingPoints  key points the learner left out (AI-graded only)
 * @param source         "From your material" link, or null
 * @param nextDueAt      when the item comes back
 * @param intervalDays   days until it comes back (0 = less than a day away, such as a few minutes after a wrong
 *                       answer), for "You'll see this again in 4 days"
 * @param disputable     true for an AI grade that can be disputed
 * @param disputed       true once disputed
 */
public record Feedback(String attemptId, String itemId, boolean correct, double score, Rating rating, GradedBy gradedBy,
                       String correctAnswer, String explanation, String graderFeedback, List<String> missingPoints,
                       SourceRef source, long nextDueAt, int intervalDays, boolean disputable, boolean disputed) {

    public Feedback {
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(rating, "rating");
        Objects.requireNonNull(gradedBy, "gradedBy");
        correctAnswer = Objects.requireNonNullElse(correctAnswer, "");
        explanation = Objects.requireNonNullElse(explanation, "");
        missingPoints = missingPoints == null ? List.of() : List.copyOf(missingPoints);
    }
}
