package edu.learnsphere.testbench.report;

import edu.learnsphere.testbench.eval.AnswerEval;
import edu.learnsphere.testbench.eval.GenerationEval;
import edu.learnsphere.testbench.eval.GradingEval;
import edu.learnsphere.testbench.eval.IngestionEval;
import edu.learnsphere.testbench.eval.ModelRun;
import edu.learnsphere.testbench.eval.RetrievalEval;
import edu.learnsphere.testbench.metrics.Stats;
import edu.learnsphere.testbench.probe.MemorySampler;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * The pass thresholds and how each is measured. The thresholds come from what a learner needs, not from what
 * the current models reach:
 *
 * <ul>
 *   <li>Import: a 300-page text book is ready within about ten minutes (2 s per page); scanned pages are
 *       transcribed well enough that a reviewer fixes a few characters per paragraph (95% characters right).</li>
 *   <li>Retrieval: the answering page is among the five passages the answerer reads for nine questions in ten,
 *       usually near the top.</li>
 *   <li>Answers: citations always point at a passage that was shown, and mostly at the right page; questions the
 *       material does not cover get the insufficient-evidence card at least 8 times in 10, while covered ones
 *       are refused at most 1 time in 10; the first words appear within 5 s.</li>
 *   <li>Generation: replies are valid JSON, stored items keep their type's rules, near-duplicates are rare, and
 *       the support check agrees with a person at least 85% of the time.</li>
 *   <li>Grading: 4 verdicts in 5 match the teacher's.</li>
 *   <li>Resources: the servers together stay within 8 GiB, so the app runs on a 16 GB laptop next to other
 *       work, and are ready within a minute of opening a profile.</li>
 * </ul>
 */
public final class Targets {

    /** One measured value against its target. {@code met} is null when the value was not measured. */
    public record Check(String area, String measure, String target, String value, Boolean met) {
    }

    /** One threshold: what it is called, its target, and how to read it from a model's results. */
    private record Rule(String area, String measure, String target, Function<ModelRun.Result, Check> check) {
    }

    static final double TEXT_SECONDS_PER_PAGE = 2.0;
    static final double MIN_CHUNKS_PER_PAGE = 0.5;
    static final double MAX_CHUNKS_PER_PAGE = 4.0;
    static final double SCAN_CHARACTER_ACCURACY = 0.95;
    static final double SCAN_SECONDS_PER_PAGE = 60;
    static final double RECALL_AT_5 = 0.90;
    static final double MRR = 0.75;
    static final double CITATION_VALIDITY = 0.98;
    static final double CITATION_PRECISION = 0.80;
    static final double REFUSAL_RATE = 0.80;
    static final double FALSE_REFUSAL_RATE = 0.10;
    static final double FIRST_TOKEN_MEDIAN_MS = 5_000;
    static final double ANSWER_MEDIAN_MS = 30_000;
    static final int MIN_ITEMS = 30;
    static final double JSON_VALIDITY = 0.98;
    static final double RULE_COMPLIANCE = 0.98;
    static final double SUPPORT_AGREEMENT = 0.85;
    static final double DUPLICATE_RATE = 0.05;
    static final double GRADING_ACCURACY = 0.80;
    static final double PEAK_MEMORY_GIB = 8;
    static final double READY_MS = 60_000;

    private static final List<Rule> RULES = List.of(
            new Rule("Import", "Time per text page", "at most " + TEXT_SECONDS_PER_PAGE + " s",
                    r -> ingestion(r, i -> Markdown.number(i.textSecondsPerPage(), 2) + " s",
                            i -> i.textSecondsPerPage() <= TEXT_SECONDS_PER_PAGE)),
            new Rule("Import", "Passages per text page", MIN_CHUNKS_PER_PAGE + " to " + MAX_CHUNKS_PER_PAGE,
                    r -> ingestion(r, i -> Markdown.number(i.textChunksPerPage(), 2),
                            i -> i.textChunksPerPage() >= MIN_CHUNKS_PER_PAGE && i.textChunksPerPage() <= MAX_CHUNKS_PER_PAGE)),
            new Rule("Import", "Scanned pages: characters right", "at least " + Markdown.percent(SCAN_CHARACTER_ACCURACY),
                    r -> ingestion(r, i -> Markdown.percent(i.scannedCharacterAccuracy()),
                            i -> !i.scanned().isEmpty() && i.scannedCharacterAccuracy() >= SCAN_CHARACTER_ACCURACY)),
            new Rule("Import", "Scanned pages: reading time per page (median)", "at most " + (int) SCAN_SECONDS_PER_PAGE + " s",
                    r -> ingestion(r, i -> Markdown.duration(scanMedianMs(i)),
                            i -> !i.scanned().isEmpty() && scanMedianMs(i) <= SCAN_SECONDS_PER_PAGE * 1000)),
            new Rule("Retrieval", "Recall@5, hybrid + reranker", "at least " + RECALL_AT_5,
                    r -> retrieval(r, s -> Markdown.number(s.main(RetrievalEval.Mode.HYBRID_RERANKED).recallAt5(), 3),
                            s -> s.main(RetrievalEval.Mode.HYBRID_RERANKED).recallAt5() >= RECALL_AT_5)),
            new Rule("Retrieval", "MRR, hybrid + reranker", "at least " + MRR,
                    r -> retrieval(r, s -> Markdown.number(s.main(RetrievalEval.Mode.HYBRID_RERANKED).mrr(), 3),
                            s -> s.main(RetrievalEval.Mode.HYBRID_RERANKED).mrr() >= MRR)),
            new Rule("Answers", "Citation validity", "at least " + Markdown.percent(CITATION_VALIDITY),
                    r -> answers(r, a -> Markdown.percent(a.citationValidity()) + " of " + a.markerCount() + " markers",
                            a -> a.markerCount() > 0 && a.citationValidity() >= CITATION_VALIDITY)),
            new Rule("Answers", "Citation precision (right page)", "at least " + Markdown.percent(CITATION_PRECISION),
                    r -> answers(r, a -> Markdown.percent(a.citationPrecision()),
                            a -> a.citationPrecision() >= CITATION_PRECISION)),
            new Rule("Answers", "Refusal rate, uncovered questions", "at least " + Markdown.percent(REFUSAL_RATE),
                    r -> answers(r, a -> Markdown.percent(a.refusalRate()), a -> a.refusalRate() >= REFUSAL_RATE)),
            new Rule("Answers", "False-refusal rate, covered questions", "at most " + Markdown.percent(FALSE_REFUSAL_RATE),
                    r -> answers(r, a -> Markdown.percent(a.falseRefusalRate()), a -> a.falseRefusalRate() <= FALSE_REFUSAL_RATE)),
            new Rule("Answers", "Time to first words (median)", "at most " + Markdown.duration(FIRST_TOKEN_MEDIAN_MS),
                    r -> answers(r, a -> Markdown.duration(Stats.median(a.firstTokenTimes())),
                            a -> !a.firstTokenTimes().isEmpty() && Stats.median(a.firstTokenTimes()) <= FIRST_TOKEN_MEDIAN_MS)),
            new Rule("Answers", "Time to full answer (median)", "at most " + Markdown.duration(ANSWER_MEDIAN_MS),
                    r -> answers(r, a -> Markdown.duration(Stats.median(a.totalTimes())),
                            a -> Stats.median(a.totalTimes()) <= ANSWER_MEDIAN_MS)),
            new Rule("Generation", "Items stored", "at least " + MIN_ITEMS,
                    r -> generation(r, g -> g.stored() + " of " + g.requested() + " asked for", g -> g.stored() >= MIN_ITEMS)),
            new Rule("Generation", "Valid JSON replies", "at least " + Markdown.percent(JSON_VALIDITY),
                    r -> generation(r, g -> Markdown.percent(g.jsonValidity()) + " of " + g.structuredReplies(),
                            g -> g.structuredReplies() > 0 && g.jsonValidity() >= JSON_VALIDITY)),
            new Rule("Generation", "Stored items keeping their type's rules", "at least " + Markdown.percent(RULE_COMPLIANCE),
                    r -> generation(r, g -> Markdown.percent(g.ruleCompliance()), g -> g.ruleCompliance() >= RULE_COMPLIANCE)),
            new Rule("Generation", "Support check agrees with labels", "at least " + Markdown.percent(SUPPORT_AGREEMENT),
                    r -> generation(r, g -> Markdown.percent(g.supportAgreement()) + " of " + g.supportPairs().size(),
                            g -> !g.supportPairs().isEmpty() && g.supportAgreement() >= SUPPORT_AGREEMENT)),
            new Rule("Generation", "Near-duplicate items", "at most " + Markdown.percent(DUPLICATE_RATE),
                    r -> generation(r, g -> Markdown.percent(g.duplicateRate()), g -> g.duplicateRate() <= DUPLICATE_RATE)),
            new Rule("Grading", "Verdicts matching the teacher", "at least " + Markdown.percent(GRADING_ACCURACY),
                    r -> grading(r, g -> Markdown.percent(g.accuracy()) + " of " + g.rows().size(),
                            g -> !g.rows().isEmpty() && g.accuracy() >= GRADING_ACCURACY)),
            new Rule("Resources", "Peak memory, all servers", "at most " + (int) PEAK_MEMORY_GIB + " GiB",
                    r -> memory(r)),
            new Rule("Resources", "Servers ready after start", "at most " + Markdown.duration(READY_MS),
                    r -> r.load() == null ? missing() : check(Markdown.duration(r.load().allMs()), r.load().allMs() <= READY_MS)));

    private Targets() {
    }

    /** Every check for one model, in report order. */
    public static List<Check> checks(ModelRun.Result result) {
        List<Check> checks = new ArrayList<>();
        for (Rule rule : RULES) {
            Check measured = rule.check().apply(result);
            checks.add(new Check(rule.area(), rule.measure(), rule.target(), measured.value(), measured.met()));
        }
        return checks;
    }

    /** The checks' names and targets without values (for a report without a model run). */
    public static List<Check> unmeasured() {
        return RULES.stream().map(r -> new Check(r.area(), r.measure(), r.target(), "not measured", null)).toList();
    }

    static double scanMedianMs(IngestionEval.Result ingestion) {
        return Stats.median(ingestion.scanned().stream().map(IngestionEval.ScannedPage::readMs).toList());
    }

    private static Check check(String value, boolean met) {
        return new Check("", "", "", value, met);
    }

    private static Check missing() {
        return new Check("", "", "", "not measured", null);
    }

    private static Check ingestion(ModelRun.Result r, Function<IngestionEval.Result, String> value,
                                   java.util.function.Predicate<IngestionEval.Result> met) {
        return r.ingestion() == null ? missing() : check(value.apply(r.ingestion()), met.test(r.ingestion()));
    }

    private static Check retrieval(ModelRun.Result r, Function<RetrievalEval.Result, String> value,
                                   java.util.function.Predicate<RetrievalEval.Result> met) {
        return r.retrieval() == null ? missing() : check(value.apply(r.retrieval()), met.test(r.retrieval()));
    }

    private static Check answers(ModelRun.Result r, Function<AnswerEval.Result, String> value,
                                 java.util.function.Predicate<AnswerEval.Result> met) {
        return r.answers() == null ? missing() : check(value.apply(r.answers()), met.test(r.answers()));
    }

    private static Check generation(ModelRun.Result r, Function<GenerationEval.Result, String> value,
                                    java.util.function.Predicate<GenerationEval.Result> met) {
        return r.generation() == null ? missing() : check(value.apply(r.generation()), met.test(r.generation()));
    }

    private static Check grading(ModelRun.Result r, Function<GradingEval.Result, String> value,
                                 java.util.function.Predicate<GradingEval.Result> met) {
        return r.grading() == null ? missing() : check(value.apply(r.grading()), met.test(r.grading()));
    }

    private static Check memory(ModelRun.Result r) {
        MemorySampler.Peaks peaks = r.memory();
        if (peaks == null || peaks.samples() == 0) {
            return missing();
        }
        long peak = peaks.hasFootprint() ? peaks.totalFootprint() : peaks.totalRss();
        return check(Markdown.bytes(peak) + (peaks.hasFootprint() ? " (footprint)" : " (resident)"),
                peak <= PEAK_MEMORY_GIB * 1024 * 1024 * 1024);
    }
}
