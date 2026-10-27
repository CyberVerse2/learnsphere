package edu.learnsphere.testbench.eval;

import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.testbench.metrics.Stats;
import edu.learnsphere.testbench.truth.EvaluationSet;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Grades the labelled student answers with the app's short-answer grader, given the question, the model answer
 * and the passage from the answering page (as the app does for a generated short-answer item), and compares the
 * verdicts with the teacher's.
 */
public final class GradingEval {

    private static final Logger LOG = LoggerFactory.getLogger(GradingEval.class);
    static final String PHASE = "grading";

    /** One graded response. */
    public record Row(String id, String label, GradeResult.Verdict expected, GradeResult.Verdict verdict, double score,
                      long millis, String feedback, boolean withPassage) {

        public boolean agrees() {
            return expected == verdict;
        }
    }

    /** Everything the grading evaluation measured. */
    public record Result(List<Row> rows, int structuredReplies, int invalidReplies) {

        public Result {
            rows = List.copyOf(rows);
        }

        /** Share of responses given the teacher's verdict. */
        public double accuracy() {
            return Stats.share(rows.stream().filter(Row::agrees).count(), rows.size());
        }

        /** Share of responses whose verdict is at most one step from the teacher's (correct/partial/incorrect). */
        public double withinOneStep() {
            return Stats.share(rows.stream().filter(r -> Math.abs(r.expected().ordinal() - r.verdict().ordinal()) <= 1)
                    .count(), rows.size());
        }

        /** Counts of verdicts given (columns) per teacher verdict (rows). */
        public Map<GradeResult.Verdict, Map<GradeResult.Verdict, Long>> confusion() {
            Map<GradeResult.Verdict, Map<GradeResult.Verdict, Long>> matrix = new EnumMap<>(GradeResult.Verdict.class);
            for (GradeResult.Verdict expected : GradeResult.Verdict.values()) {
                Map<GradeResult.Verdict, Long> row = new EnumMap<>(GradeResult.Verdict.class);
                for (GradeResult.Verdict given : GradeResult.Verdict.values()) {
                    row.put(given, rows.stream().filter(r -> r.expected() == expected && r.verdict() == given).count());
                }
                matrix.put(expected, row);
            }
            return matrix;
        }

        /** Mean score given per teacher verdict. */
        public Map<GradeResult.Verdict, Double> meanScore() {
            Map<GradeResult.Verdict, Double> means = new EnumMap<>(GradeResult.Verdict.class);
            for (GradeResult.Verdict expected : GradeResult.Verdict.values()) {
                means.put(expected, Stats.mean(rows.stream().filter(r -> r.expected() == expected).map(Row::score).toList()));
            }
            return means;
        }
    }

    private GradingEval() {
    }

    /** Grades every labelled response. */
    public static Result run(Session session) {
        session.phase(PHASE);
        List<Row> rows = new ArrayList<>();
        for (EvaluationSet.ShortAnswer question : session.truth().shortAnswers()) {
            Optional<Passage> passage = RetrievalEval.bestPassage(session, session.courseOf(question.document()),
                    question.question(), question.document(), List.of(question.page()));
            for (EvaluationSet.Response response : question.responses()) {
                long start = System.nanoTime();
                GradeResult result = session.capabilities().grader().grade(question.question(), question.modelAnswer(),
                        response.text(), passage.orElse(null), CancellationToken.NONE);
                long millis = (System.nanoTime() - start) / 1_000_000;
                rows.add(new Row(question.id(), response.label(), response.expected(), result.verdict(), result.score(),
                        millis, result.feedback(), passage.isPresent()));
            }
        }
        Result result = new Result(rows, session.chatLog().completions(PHASE).size(),
                session.chatLog().invalidJson(PHASE).size());
        LOG.info("Grading: {} of {} verdicts agree ({})", rows.stream().filter(Row::agrees).count(), rows.size(),
                String.format(Locale.ROOT, "%.3f", result.accuracy()));
        return result;
    }
}
