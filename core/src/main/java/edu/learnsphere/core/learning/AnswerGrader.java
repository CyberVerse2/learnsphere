package edu.learnsphere.core.learning;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.ShortAnswerGrader;
import edu.learnsphere.core.util.CancellationToken;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/**
 * Grades an answer by item type and maps the result to a {@link Rating} with {@link RatingRules}: multiple
 * choice, true/false and cloze by {@link AnswerChecker}; short answers by the study assistant
 * ({@link ShortAnswerGrader}, which serves them at interactive priority); flashcards by the learner. Thread-safe;
 * call it outside database transactions, since grading a short answer waits for the study assistant.
 */
public final class AnswerGrader {

    private final ShortAnswerGrader shortAnswers;

    public AnswerGrader(ShortAnswerGrader shortAnswers) {
        this.shortAnswers = Objects.requireNonNull(shortAnswers, "shortAnswers");
    }

    /**
     * Grades {@code response} to {@code item}.
     *
     * @param hintUsed whether a hint was shown for this answer (caps the rating at Hard)
     * @param context  the item's source passage for the study assistant, or null
     * @throws InvalidInputException when a flashcard has no rating
     * @throws UnavailableException  when the study assistant cannot grade a short answer right now
     */
    public GradeOutcome grade(LearningItem item, Response response, boolean hintUsed, Passage context,
                              CancellationToken token) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(response, "response");
        return switch (item.type()) {
            case MCQ, TRUE_FALSE, CLOZE -> {
                boolean correct = AnswerChecker.isCorrect(item, response.answer());
                yield new GradeOutcome(correct, correct ? 1 : 0,
                        RatingRules.ruleGraded(correct, response.confidence(), hintUsed), GradedBy.RULE, null, List.of());
            }
            case FLASHCARD -> {
                if (response.selfRating() == null) {
                    throw new InvalidInputException(StudyMessages.RATE_THE_CARD, "flashcard " + item.id() + " without rating");
                }
                Rating rating = RatingRules.selfRated(response.selfRating());
                yield new GradeOutcome(rating.isPass(), rating.isPass() ? 1 : 0, rating, GradedBy.SELF, null, List.of());
            }
            case SHORT_ANSWER -> gradeShortAnswer(item, response, hintUsed, context, token);
        };
    }

    private GradeOutcome gradeShortAnswer(LearningItem item, Response response, boolean hintUsed, Passage context,
                                          CancellationToken token) {
        if (response.answer().isBlank()) {
            // Nothing to grade: wrong without asking the study assistant.
            return new GradeOutcome(false, 0, Rating.AGAIN, GradedBy.RULE, null, List.of());
        }
        GradeResult result;
        try {
            result = shortAnswers.grade(item.prompt(), item.answer(), response.answer(), context,
                    token == null ? CancellationToken.NONE : token);
        } catch (UnavailableException e) {
            throw e;
        } catch (CancellationException e) {
            throw new UnavailableException(StudyMessages.GRADING_UNAVAILABLE, "grading cancelled for item " + item.id(), e);
        } catch (LearnSphereException e) {
            throw new UnavailableException(e.userMessage(), "grading failed for item " + item.id(), e);
        } catch (RuntimeException e) {
            throw new UnavailableException(StudyMessages.GRADING_UNAVAILABLE, "grading failed for item " + item.id(), e);
        }
        if (result == null) {
            throw new UnavailableException(StudyMessages.GRADING_UNAVAILABLE, "grader returned nothing for item " + item.id());
        }
        double score = result.score();
        return new GradeOutcome(RatingRules.shortAnswerCorrect(score), score,
                RatingRules.shortAnswer(score, response.confidence(), hintUsed), GradedBy.AI,
                result.feedback().isBlank() ? null : result.feedback(), result.missingPoints());
    }
}
