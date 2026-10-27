package edu.learnsphere.core.spi;

import java.util.List;
import java.util.Objects;

/**
 * The AI grade of a short answer.
 *
 * @param score         0–1
 * @param verdict       correct, partly correct or incorrect
 * @param feedback      one or two encouraging sentences for the learner
 * @param missingPoints key points of the reference answer the learner left out
 */
public record GradeResult(double score, Verdict verdict, String feedback, List<String> missingPoints) {

    /** Overall verdict. */
    public enum Verdict {
        CORRECT, PARTIAL, INCORRECT
    }

    public GradeResult {
        Objects.requireNonNull(verdict, "verdict");
        feedback = Objects.requireNonNullElse(feedback, "");
        missingPoints = missingPoints == null ? List.of() : List.copyOf(missingPoints);
        if (score < 0 || score > 1) {
            throw new IllegalArgumentException("score must be 0-1: " + score);
        }
    }

    /** The verdict for a score: below 0.5 incorrect, below 0.75 partial, otherwise correct. */
    public static Verdict verdictFor(double score) {
        return score < 0.5 ? Verdict.INCORRECT : score < 0.75 ? Verdict.PARTIAL : Verdict.CORRECT;
    }
}
