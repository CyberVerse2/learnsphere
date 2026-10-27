package edu.learnsphere.testbench.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.spi.GradeResult.Verdict;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EvaluationScoringTest {

    private static RetrievalEval.QuestionRow covered(String id, double relevantTop) {
        return new RetrievalEval.QuestionRow(id, "os", List.of(1), true, Map.of(), Math.max(relevantTop, 0), relevantTop);
    }

    private static RetrievalEval.QuestionRow uncovered(String id, double top) {
        return new RetrievalEval.QuestionRow(id, "", List.of(), false, Map.of(), top, -1);
    }

    @Test
    void thresholdSweepCountsKeptAndRefusedQuestions() {
        List<RetrievalEval.QuestionRow> rows = List.of(covered("a", 0.9), covered("b", 0.42), covered("c", -1),
                uncovered("x", 0.05), uncovered("y", 0.3));
        List<RetrievalEval.ThresholdRow> sweep = RetrievalEval.sweep(rows);
        assertThat(sweep).hasSize(20);
        RetrievalEval.ThresholdRow zero = sweep.getFirst();
        assertThat(zero.coveredKept()).isCloseTo(2 / 3.0, within(1e-9));
        assertThat(zero.uncoveredRefused()).isZero();
        RetrievalEval.ThresholdRow at35 = sweep.get(7);
        assertThat(at35.threshold()).isEqualTo(0.35);
        assertThat(at35.coveredKept()).isCloseTo(2 / 3.0, within(1e-9));
        assertThat(at35.uncoveredRefused()).isEqualTo(1.0);
        assertThat(at35.balanced()).isCloseTo(5 / 6.0, within(1e-9));
    }

    @Test
    void recommendationKeepsTheCurrentSettingWhenItIsAmongTheBest() {
        List<RetrievalEval.ThresholdRow> sweep = RetrievalEval.sweep(List.of(covered("a", 0.9), covered("b", 0.5),
                uncovered("x", 0.1), uncovered("y", 0.2)));
        // Thresholds above 0.2 and up to 0.5 keep both covered and refuse both uncovered questions.
        assertThat(RetrievalEval.recommend(sweep, 0.35)).isEqualTo(0.35);
        assertThat(RetrievalEval.recommend(sweep, 0.8)).isEqualTo(0.4);
    }

    @Test
    void pageKeysSpanEveryPageOfAPassage() {
        assertThat(RetrievalEval.pageKeys("os", 4, 6)).containsExactlyInAnyOrder("os:4", "os:5", "os:6");
        assertThat(RetrievalEval.pageKeys("rm", 3, 0)).containsExactly("rm:3");
        assertThat(RetrievalEval.targets("os", List.of(2, 9))).containsExactlyInAnyOrder("os:2", "os:9");
    }

    @Test
    void nearDuplicatesShareMostOfTheirWords() {
        GenerationEval.StoredItem a = item("What does fork() return in the child process?", "zero");
        GenerationEval.StoredItem b = item("What does fork() return in the child?", "zero");
        GenerationEval.StoredItem c = item("Which scheduling algorithm suffers from the convoy effect?", "FCFS");
        List<GenerationEval.DuplicatePair> pairs = GenerationEval.nearDuplicates(List.of(a, b, c));
        assertThat(pairs).singleElement().satisfies(p -> {
            assertThat(p.first()).isEqualTo(a.prompt());
            assertThat(p.second()).isEqualTo(b.prompt());
            assertThat(p.overlap()).isCloseTo(8 / 9.0, within(1e-9));
        });
        assertThat(GenerationEval.jaccard(GenerationEval.words("A b"), GenerationEval.words("c d"))).isZero();
        assertThat(GenerationEval.words("fork() returns 0, in the CHILD")).containsExactlyInAnyOrder("fork", "returns", "0",
                "in", "the", "child");
    }

    @Test
    void generationResultSummarisesStoredItemsAndLabels() {
        GenerationEval.StoredItem ok = item("Q1?", "A1");
        GenerationEval.StoredItem broken = new GenerationEval.StoredItem(ItemType.CLOZE, "____ and ____", "x", List.of(), "",
                "os", 3, SupportStatus.UNSUPPORTED, "", Optional.of("2 blanks"));
        GenerationEval.Result result = new GenerationEval.Result(List.of("Process"), 1000, 4, 5, 2, 1, 9000,
                List.of(ok, broken), List.of(), Map.of(), 40, 1,
                List.of(new GenerationEval.SupportPair("a reference", true, true, "", 10),
                        new GenerationEval.SupportPair("a wrong", false, true, "", 10),
                        new GenerationEval.SupportPair("b reference", true, true, "", 10),
                        new GenerationEval.SupportPair("b wrong", false, false, "", 10)), 0);
        assertThat(result.jsonValidity()).isEqualTo(0.975);
        assertThat(result.ruleViolations()).isEqualTo(1);
        assertThat(result.ruleCompliance()).isEqualTo(0.5);
        assertThat(result.supportAgreement()).isEqualTo(0.75);
        assertThat(result.supportTruePositiveRate()).isEqualTo(1.0);
        assertThat(result.supportTrueNegativeRate()).isEqualTo(0.5);
        assertThat(result.byType()).containsEntry(ItemType.FLASHCARD, 1L).containsEntry(ItemType.CLOZE, 1L);
        assertThat(result.bySupport()).containsEntry(SupportStatus.SUPPORTED, 1L).containsEntry(SupportStatus.UNSUPPORTED, 1L);
        assertThat(result.duplicateRate()).isZero();
    }

    @Test
    void answerResultSeparatesRefusalsFromFalseRefusals() {
        List<AnswerEval.Row> rows = List.of(
                answer("os-01", "covered", AnswerEval.Outcome.ANSWERED, 3, 3, 2, 2, 1.0, 900, 5000),
                answer("os-02", "covered", AnswerEval.Outcome.ANSWERED, 2, 1, 1, 0, 0.5, 1100, 7000),
                answer("scan-01", "scanned", AnswerEval.Outcome.NOT_COVERED, 0, 0, 0, 0, 0, -1, 400),
                answer("none-01", "uncovered", AnswerEval.Outcome.NOT_COVERED, 0, 0, 0, 0, 0, -1, 300),
                answer("none-02", "uncovered", AnswerEval.Outcome.ANSWERED, 1, 1, 1, 0, 0, 1000, 4000));
        AnswerEval.Result result = new AnswerEval.Result(rows, List.of());
        assertThat(result.answerable()).hasSize(3);
        assertThat(result.answered()).hasSize(2);
        assertThat(result.citationValidity()).isCloseTo(5 / 6.0, within(1e-9));
        assertThat(result.markerCount()).isEqualTo(6);
        assertThat(result.citationPrecision()).isCloseTo(2 / 3.0, within(1e-9));
        assertThat(result.groundedShare()).isEqualTo(0.5);
        assertThat(result.refusalRate()).isEqualTo(0.5);
        assertThat(result.falseRefusalRate()).isCloseTo(1 / 3.0, within(1e-9));
        assertThat(result.keyTermCoverage()).isEqualTo(0.75);
        assertThat(result.firstTokenTimes()).containsExactly(900L, 1100L, 1000L);
        assertThat(result.errors()).isZero();
    }

    @Test
    void gradingResultBuildsTheConfusionMatrix() {
        GradingEval.Result result = new GradingEval.Result(List.of(
                grade(Verdict.CORRECT, Verdict.CORRECT, 0.9),
                grade(Verdict.PARTIAL, Verdict.CORRECT, 0.8),
                grade(Verdict.INCORRECT, Verdict.INCORRECT, 0.1),
                grade(Verdict.INCORRECT, Verdict.CORRECT, 0.9)), 4, 0);
        assertThat(result.accuracy()).isEqualTo(0.5);
        assertThat(result.withinOneStep()).isEqualTo(0.75);
        assertThat(result.confusion().get(Verdict.INCORRECT)).containsEntry(Verdict.CORRECT, 1L)
                .containsEntry(Verdict.INCORRECT, 1L).containsEntry(Verdict.PARTIAL, 0L);
        assertThat(result.meanScore().get(Verdict.INCORRECT)).isCloseTo(0.5, within(1e-9));
    }

    private static GenerationEval.StoredItem item(String prompt, String answer) {
        return new GenerationEval.StoredItem(ItemType.FLASHCARD, prompt, answer, List.of(), "", "os", 1,
                SupportStatus.SUPPORTED, "", Optional.empty());
    }

    private static AnswerEval.Row answer(String id, String kind, AnswerEval.Outcome outcome, int markers, int valid,
                                         int citations, int onPages, double keyTerms, long first, long total) {
        return new AnswerEval.Row(id, kind, "?", outcome, outcome == AnswerEval.Outcome.NOT_COVERED ? "retrieval" : "", 6,
                markers, valid, citations, onPages, keyTerms, first, total, "text");
    }

    private static GradingEval.Row grade(Verdict expected, Verdict given, double score) {
        return new GradingEval.Row("sa", "label", expected, given, score, 100, "", true);
    }
}
