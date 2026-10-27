package edu.learnsphere.core.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.service.Response;
import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.core.spi.ShortAnswerGrader;
import edu.learnsphere.core.util.CancellationToken;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Answer checking, the rating mapping and the grader that combines them. */
class GradingTest {

    private static LearningItem item(ItemType type, String answer, List<String> distractors) {
        return LearningItem.draft("course", null, type, "prompt", answer, "because", distractors, Difficulty.MEDIUM,
                null, null, ItemOrigin.MANUAL, 0);
    }

    // ---- answer checking ----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "mitochondria | Mitochondria | true",
        "mitochondria | '  the  MITOCHONDRIA. ' | true",
        "café | cafe | true",
        "allele | alleles | true",
        "class | classes | true",
        "3.5 | 3.50 | true",
        "1,000 | 1000 | true",
        "1000 | 1,000 | true",
        "1984 | 1985 | false",
        "photosynthesis | photosynthsis | true",
        "photosynthesis | photosynthesiss | true",
        "photosynthesis | photosytnhesis | true",
        "afferent | efferent | false",
        "absorption | adsorption | false",
        "endocytosis | exocytosis | false",
        "mitosis | meiosis | false",
        "cell | cel | false",
        "ATP synthase | atp-synthase | true",
        "carbon dioxide | carbon  dioxide! | true",
        "mitochondria | '' | false",
        "a | A | true",
    })
    void clozeAnswersAreMatchedTolerantly(String expected, String given, boolean correct) {
        assertThat(AnswerChecker.clozeMatches(expected, given)).isEqualTo(correct);
    }

    @Test
    void multipleChoiceComparesTheChosenOption() {
        LearningItem mcq = item(ItemType.MCQ, "The nucleus", List.of("The ribosome", "The membrane"));
        assertThat(AnswerChecker.isCorrect(mcq, "the  nucleus")).isTrue();
        assertThat(AnswerChecker.isCorrect(mcq, "The ribosome")).isFalse();
        assertThat(AnswerChecker.isCorrect(mcq, "")).isFalse();
        assertThat(AnswerChecker.isCorrect(mcq, null)).isFalse();
    }

    @Test
    void trueFalseAcceptsShortForms() {
        LearningItem statement = item(ItemType.TRUE_FALSE, "true", List.of());
        assertThat(AnswerChecker.isCorrect(statement, "true")).isTrue();
        assertThat(AnswerChecker.isCorrect(statement, "True")).isTrue();
        assertThat(AnswerChecker.isCorrect(statement, "yes")).isTrue();
        assertThat(AnswerChecker.isCorrect(statement, "false")).isFalse();
        assertThat(AnswerChecker.isCorrect(statement, "maybe")).isFalse();
        assertThatThrownBy(() -> AnswerChecker.isCorrect(item(ItemType.FLASHCARD, "x", List.of()), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- rating mapping -----------------------------------------------------------------------

    @ParameterizedTest
    @CsvSource({
        "false, CERTAIN, false, AGAIN",
        "false, GUESSING, true, AGAIN",
        "true, GUESSING, false, HARD",
        "true, FAIRLY_SURE, false, GOOD",
        "true, CERTAIN, false, EASY",
        "true, CERTAIN, true, HARD",
        "true, FAIRLY_SURE, true, HARD",
        "true, , false, GOOD",
    })
    void ruleGradedAnswersMapToRatings(boolean correct, Confidence confidence, boolean hint, Rating expected) {
        assertThat(RatingRules.ruleGraded(correct, confidence, hint)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "0.0, CERTAIN, false, AGAIN",
        "0.49, CERTAIN, false, AGAIN",
        "0.5, CERTAIN, false, HARD",
        "0.74, CERTAIN, false, HARD",
        "0.75, GUESSING, false, GOOD",
        "0.9, FAIRLY_SURE, false, GOOD",
        "0.9, CERTAIN, false, EASY",
        "1.0, CERTAIN, true, HARD",
        "1.0, , false, GOOD",
    })
    void shortAnswerScoresMapToRatings(double score, Confidence confidence, boolean hint, Rating expected) {
        assertThat(RatingRules.shortAnswer(score, confidence, hint)).isEqualTo(expected);
    }

    // ---- the grader ---------------------------------------------------------------------------

    @Test
    void gradesEachTypeWithItsGrader() {
        AtomicInteger calls = new AtomicInteger();
        ShortAnswerGrader ai = (q, ref, answer, context, token) -> {
            calls.incrementAndGet();
            return new GradeResult(0.8, GradeResult.Verdict.CORRECT, "Nicely put.", List.of("energy"));
        };
        AnswerGrader grader = new AnswerGrader(ai);

        GradeOutcome cloze = grader.grade(item(ItemType.CLOZE, "nucleus", List.of()),
                Response.answer("x", "Nucleus", Confidence.CERTAIN, false, 1000), false, null, CancellationToken.NONE);
        assertThat(cloze).isEqualTo(new GradeOutcome(true, 1, Rating.EASY, GradedBy.RULE, null, List.of()));

        GradeOutcome card = grader.grade(item(ItemType.FLASHCARD, "back", List.of()),
                Response.flashcard("x", Rating.HARD, 1000), false, null, CancellationToken.NONE);
        assertThat(card).isEqualTo(new GradeOutcome(true, 1, Rating.HARD, GradedBy.SELF, null, List.of()));

        GradeOutcome shortAnswer = grader.grade(item(ItemType.SHORT_ANSWER, "Cells make energy", List.of()),
                Response.answer("x", "Cells make power", Confidence.CERTAIN, false, 1000), false, null,
                CancellationToken.NONE);
        assertThat(shortAnswer).isEqualTo(new GradeOutcome(true, 0.8, Rating.EASY, GradedBy.AI, "Nicely put.",
                List.of("energy")));

        GradeOutcome blank = grader.grade(item(ItemType.SHORT_ANSWER, "Cells make energy", List.of()),
                Response.answer("x", "  ", Confidence.CERTAIN, false, 1000), false, null, CancellationToken.NONE);
        assertThat(blank.rating()).isEqualTo(Rating.AGAIN);
        assertThat(blank.correct()).isFalse();
        assertThat(calls).as("a blank answer is not sent to the study assistant").hasValue(1);
    }

    @Test
    void aFlashcardNeedsARating() {
        AnswerGrader grader = new AnswerGrader((q, r, a, c, t) -> {
            throw new AssertionError("not called");
        });
        assertThatThrownBy(() -> grader.grade(item(ItemType.FLASHCARD, "back", List.of()),
                Response.answer("x", "", null, false, 0), false, null, CancellationToken.NONE))
                .isInstanceOf(InvalidInputException.class)
                .extracting(e -> ((InvalidInputException) e).userMessage())
                .isEqualTo(StudyMessages.RATE_THE_CARD);
    }

    @Test
    void graderFailuresBecomeAUserMessage() {
        AnswerGrader broken = new AnswerGrader((q, r, a, c, t) -> {
            throw new IllegalStateException("connection refused on port 51234");
        });
        assertThatThrownBy(() -> broken.grade(item(ItemType.SHORT_ANSWER, "ref", List.of()),
                Response.answer("x", "mine", null, false, 0), false, null, CancellationToken.NONE))
                .isInstanceOf(UnavailableException.class)
                .extracting(e -> ((UnavailableException) e).userMessage())
                .isEqualTo(StudyMessages.GRADING_UNAVAILABLE);

        AnswerGrader starting = new AnswerGrader((q, r, a, c, t) -> {
            throw new UnavailableException(UserMessage.ASSISTANT_STARTING, "starting");
        });
        assertThatThrownBy(() -> starting.grade(item(ItemType.SHORT_ANSWER, "ref", List.of()),
                Response.answer("x", "mine", null, false, 0), false, null, CancellationToken.NONE))
                .isInstanceOf(UnavailableException.class)
                .extracting(e -> ((UnavailableException) e).userMessage())
                .isEqualTo(UserMessage.ASSISTANT_STARTING);
    }

    @Test
    void aScoreThatIsNotANumberCannotBeUsed() {
        AnswerGrader confused = new AnswerGrader((q, r, a, c, t) ->
                new GradeResult(Double.NaN, GradeResult.Verdict.PARTIAL, "Hmm.", List.of()));
        assertThatThrownBy(() -> confused.grade(item(ItemType.SHORT_ANSWER, "ref", List.of()),
                Response.answer("x", "mine", null, false, 0), false, null, CancellationToken.NONE))
                .isInstanceOf(UnavailableException.class)
                .extracting(e -> ((UnavailableException) e).userMessage())
                .isEqualTo(StudyMessages.GRADING_UNAVAILABLE);
    }
}
