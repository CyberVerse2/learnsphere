package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.ShortAnswerGrader;
import edu.learnsphere.core.util.CancellationToken;
import java.util.List;
import java.util.Set;

/**
 * Scores a short answer by the fraction of the reference answer's content words it contains; the missing
 * words (up to three) are the missing points.
 */
public final class FakeShortAnswerGrader implements ShortAnswerGrader {

    @Override
    public GradeResult grade(String question, String referenceAnswer, String learnerAnswer, Passage context,
                             CancellationToken token) {
        token.throwIfCancelled();
        Set<String> expected = FakeText.contentWords(referenceAnswer);
        Set<String> given = FakeText.contentWords(learnerAnswer);
        if (expected.isEmpty()) {
            boolean answered = !given.isEmpty();
            return new GradeResult(answered ? 1 : 0, answered ? GradeResult.Verdict.CORRECT : GradeResult.Verdict.INCORRECT,
                    answered ? "That matches the answer." : "Write an answer to get feedback.", List.of());
        }
        List<String> missing = expected.stream().filter(w -> !given.contains(w)).toList();
        double score = (double) (expected.size() - missing.size()) / expected.size();
        GradeResult.Verdict verdict = GradeResult.verdictFor(score);
        String feedback = switch (verdict) {
            case CORRECT -> "That covers the key points.";
            case PARTIAL -> "You're on the right track, but some key points are missing.";
            case INCORRECT -> "That doesn't match the material. Compare it with the answer below.";
        };
        return new GradeResult(score, verdict, feedback, missing.stream().limit(3).toList());
    }
}
