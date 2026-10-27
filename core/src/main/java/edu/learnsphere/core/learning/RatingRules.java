package edu.learnsphere.core.learning;

import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.Rating;
import java.util.Objects;

/**
 * How a graded answer becomes the {@link Rating} that schedules the item (see "Practice and scheduling" in
 * {@code docs/ARCHITECTURE.md}). Stateless.
 *
 * <ul>
 *   <li>Rule-graded items (multiple choice, true/false, cloze): wrong is Again; right with a hint or while
 *       guessing is Hard; right and fairly sure is Good; right and certain is Easy. Without a confidence,
 *       right counts as fairly sure.</li>
 *   <li>Short answers (graded by the study assistant): a score below 0.5 is Again, below 0.75 Hard; from
 *       0.75 the learner's confidence decides: certain is Easy, otherwise Good.</li>
 *   <li>Flashcards: the learner's own rating.</li>
 *   <li>A hint caps every rating at Hard.</li>
 *   <li>A disputed AI grade is re-rated Good.</li>
 * </ul>
 */
public final class RatingRules {

    /** Short-answer score below which the answer counts as wrong (Again). */
    public static final double SHORT_ANSWER_PASS = 0.5;
    /** Short-answer score from which the answer counts as fully right (Good or Easy). */
    public static final double SHORT_ANSWER_FULL = 0.75;
    /** The rating a disputed AI grade is changed to. */
    public static final Rating DISPUTED = Rating.GOOD;

    private RatingRules() {
    }

    /** The rating of a rule-graded answer. */
    public static Rating ruleGraded(boolean correct, Confidence confidence, boolean hintUsed) {
        if (!correct) {
            return Rating.AGAIN;
        }
        Rating rating = switch (confidence == null ? Confidence.FAIRLY_SURE : confidence) {
            case GUESSING -> Rating.HARD;
            case FAIRLY_SURE -> Rating.GOOD;
            case CERTAIN -> Rating.EASY;
        };
        return capForHint(rating, hintUsed);
    }

    /** The rating of a short answer the study assistant scored {@code score} (0–1). */
    public static Rating shortAnswer(double score, Confidence confidence, boolean hintUsed) {
        if (score < SHORT_ANSWER_PASS) {
            return Rating.AGAIN;
        }
        if (score < SHORT_ANSWER_FULL) {
            return Rating.HARD;
        }
        Rating rating = confidence == Confidence.CERTAIN ? Rating.EASY : Rating.GOOD;
        return capForHint(rating, hintUsed);
    }

    /** The rating of a self-rated flashcard. */
    public static Rating selfRated(Rating rating) {
        return Objects.requireNonNull(rating, "rating");
    }

    /** True when a short-answer score counts as correct (it passes). */
    public static boolean shortAnswerCorrect(double score) {
        return score >= SHORT_ANSWER_PASS;
    }

    private static Rating capForHint(Rating rating, boolean hintUsed) {
        return hintUsed && rating.value() > Rating.HARD.value() ? Rating.HARD : rating;
    }
}
