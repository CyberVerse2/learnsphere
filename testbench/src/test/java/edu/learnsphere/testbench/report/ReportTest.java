package edu.learnsphere.testbench.report;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.runtime.ServerRole;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.spi.GradeResult.Verdict;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.testbench.eval.AnswerEval;
import edu.learnsphere.testbench.eval.GenerationEval;
import edu.learnsphere.testbench.eval.GradingEval;
import edu.learnsphere.testbench.eval.IngestionEval;
import edu.learnsphere.testbench.eval.ModelRun;
import edu.learnsphere.testbench.eval.RetrievalEval;
import edu.learnsphere.testbench.eval.RetrievalEval.Mode;
import edu.learnsphere.testbench.probe.ChatLog;
import edu.learnsphere.testbench.probe.ServerLogs;
import edu.learnsphere.testbench.material.Material;
import edu.learnsphere.testbench.probe.MemorySampler;
import edu.learnsphere.testbench.truth.EvaluationSet;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReportTest {

    private static final EvaluationSet TRUTH = EvaluationSet.load();
    private static final Environment ENVIRONMENT = new Environment("Test CPU", 8, 16L << 30, "TestOS 1.0", "Java 25",
            Optional.empty(), "", List.of(), "default settings");

    @TempDir
    static Path dir;
    private static Material material;

    @BeforeAll
    static void generate() throws Exception {
        material = Material.generate(dir, TRUTH.scan().pages());
    }

    @Test
    void tablesEscapeCellsAndAlignNumbers() {
        String table = new Markdown.Table("Name", ">Count").row("a | b\nc", 3).row("only one cell").render();
        assertThat(table).isEqualTo("""
                | Name | Count |
                |---|---:|
                | a \\| b c | 3 |
                | only one cell |  |

                """);
    }

    @Test
    void numbersAreFormattedForReading() {
        assertThat(Markdown.percent(0.9333)).isEqualTo("93.3%");
        assertThat(Markdown.duration(840)).isEqualTo("840 ms");
        assertThat(Markdown.duration(12_345)).isEqualTo("12.35 s");
        assertThat(Markdown.duration(600_000)).isEqualTo("10.0 min");
        assertThat(Markdown.bytes(512L << 20)).isEqualTo("512 MiB");
        assertThat(Markdown.bytes(3L << 30)).isEqualTo("3.00 GiB");
        assertThat(Markdown.countOf(28, 30)).isEqualTo("28 of 30 (93.3%)");
        assertThat(Markdown.shorten("one two three four", 10)).isEqualTo("one two…");
        assertThat(Markdown.shorten("short", 10)).isEqualTo("short");
    }

    @Test
    void reportWithoutARuntimeExplainsWhyNothingWasMeasured() {
        String report = ReportWriter.write(new ReportWriter.Inputs(ZonedDateTime.now(), 1000, ENVIRONMENT, material, TRUTH,
                List.of(), "No runtime folder was found."));
        assertThat(report).contains("# LearnSphere model testbench report", "The AI evaluations were skipped",
                "No model was evaluated", "| Retrieval | Recall@5, hybrid + reranker | at least 0.9 |", "## Setup",
                "## How the measures are defined");
        assertThat(report).doesNotContain("## Answers", "null");
    }

    @Test
    void reportOfARunThatEndedEarlyShowsWhatWasMeasured() {
        IngestionEval.Result ingestion = new IngestionEval.Result(
                List.of(new IngestionEval.DocumentResult("os", "os-chapter", 20, 30_000, 50, 180, 300, 0, 0, 2, 4, 9000, 4,
                                2000),
                        new IngestionEval.DocumentResult("scan", "research-methods-scanned", 3, 60_000, 6, 170, 250, 3, 50_000,
                                0, 0, 0, 1, 300)),
                List.of(new IngestionEval.ScannedPage(1, 4, 0.97, 0.93, 16_000, 0, 2000, "Transcribed text")),
                3, List.of(new IngestionEval.FigureNotes("os", 4, List.of("A state diagram"))));
        MemorySampler.Peaks memory = new MemorySampler.Peaks(Map.of(ServerRole.CHAT, 3L << 30), Map.of(ServerRole.CHAT, 4L << 30),
                3L << 30, 4L << 30, Map.of("ingest:os", 4L << 30), 120, new MemorySampler.Activity(12.5, 30, 3, 0.4));
        ModelRun.Result result = new ModelRun.Result(ModelChoice.CHAT_4B, 200_000,
                new ModelRun.LoadTimes(Map.of(ServerRole.CHAT, 12_000L, ServerRole.EMBED, 4_000L), 12_000), ingestion, null,
                null, null, null, memory, Map.of(), List.of(), "IllegalStateException: the chat server stopped", List.of());

        List<Targets.Check> checks = Targets.checks(result);
        assertThat(checks).filteredOn(c -> c.measure().equals("Time per text page")).singleElement()
                .satisfies(c -> assertThat(c.met()).isTrue());
        assertThat(checks).filteredOn(c -> c.area().equals("Answers")).allSatisfy(c -> assertThat(c.met()).isNull());
        assertThat(checks).filteredOn(c -> c.measure().equals("Peak memory, all servers")).singleElement()
                .satisfies(c -> assertThat(c.value()).isEqualTo("4.00 GiB (footprint)"));

        String report = ReportWriter.write(new ReportWriter.Inputs(ZonedDateTime.now(), 200_000, ENVIRONMENT, material, TRUTH,
                List.of(result), null));
        assertThat(report).contains("| chat-4b | Met |", "The run ended early: IllegalStateException",
                "Not measured: the run ended before this evaluation.", "A state diagram", "| 1 | 4 | 97.0% | 93.0% |",
                "## Resource use", "## Appendix B: every generated item",
                "load average was 12.5 on average (peak 30.0) on 8 cores", "40.0% of the run (up to 3 at once)");
        assertThat(report).doesNotContain("null");
    }


    @Test
    void reportOfACompleteRunRendersEverySection() {
        ModelRun.Result result = completeRun();
        List<Targets.Check> checks = Targets.checks(result);
        assertThat(checks).allSatisfy(c -> assertThat(c.met()).as(c.measure()).isNotNull());

        String report = ReportWriter.write(new ReportWriter.Inputs(ZonedDateTime.now(), 1_500_000, ENVIRONMENT, material,
                TRUTH, List.of(result), null));

        assertThat(report).contains("## Summary", "## Setup", "## Import", "## Retrieval", "## Answers",
                "## Item generation", "## Grading short answers", "## Resource use", "## How the measures are defined",
                "## Appendix A: per-question results", "## Appendix B: every generated item");
        assertThat(report).doesNotContain("Not measured", "The run ended early", "null", "NaN");
        // Every summary row has a verdict in the Met column.
        List<String> summaryRows = section(report, "## Summary").lines().filter(l -> l.startsWith("| ")).skip(1).toList();
        assertThat(summaryRows).isNotEmpty().allSatisfy(row -> assertThat(row).matches(".*\\| (yes|\\*\\*no\\*\\*) \\|$"));
        assertThat(report).contains("| Keyword (BM25) only | 0.500 | 0.750 |", "| **Hybrid + reranker (the app)** | 0.750 | 1.000 |",
                "Questions without an answering passage in the reranked top five: q02 (rank 7).");
        // Answers: the error row is counted, and both kinds of sample answer are quoted.
        assertThat(report).contains("| Covered by the chapters | 3 | 1 | 1 | 1 |", "Process control block",
                "u01 (not covered) — Who wrote the first Unix shell?");
        // Generation: the rule-breaking item is listed, sample items print, the disputed support pair is named.
        assertThat(report).contains("- True or false: a true-or-false statement needs a true or false answer",
                "**Multiple choice** (os-chapter, p. 4, supported)", "Wrong options: FIFO; Lottery; Priority",
                "Disagreements: q03 (judged supported: The passage mentions paging)");
        // Grading: the confusion matrix and the disputed verdict.
        assertThat(report).contains("| correct | 1 | 0 | 0 | 0.95 |", "| partly correct | 0 | 0 | 1 | 0.10 |",
                "| sa01 | partial | partly correct | **wrong** |");
        // Appendices.
        assertThat(report).contains("| q01 | os 4 | 1 | 2 | 1 | 1 | 0.910 | answered | 1 of 1 |", "| q03 |",
                "| error |", "| True or false | Paging needs no page table.");
    }

    private static String section(String report, String heading) {
        int start = report.indexOf(heading);
        int end = report.indexOf("\n## ", start + heading.length());
        return report.substring(start, end < 0 ? report.length() : end);
    }

    /** A run where every evaluation produced a few rows, including an error, a rule-breaking item and a dispute. */
    private static ModelRun.Result completeRun() {
        IngestionEval.Result ingestion = new IngestionEval.Result(
                List.of(new IngestionEval.DocumentResult("os", "os-chapter", 20, 30_000, 50, 180, 300, 0, 0, 2, 4, 9000, 4,
                        2000)),
                List.of(new IngestionEval.ScannedPage(1, 4, 0.97, 0.93, 16_000, 0, 2000, "Transcribed text")),
                3, List.of());

        List<RetrievalEval.ModeScore> modes = List.of(new RetrievalEval.ModeScore(Mode.KEYWORD, 0.5, 0.75, 0.6, 1),
                new RetrievalEval.ModeScore(Mode.VECTOR, 0.5, 0.75, 0.62, 1),
                new RetrievalEval.ModeScore(Mode.HYBRID, 0.5, 1, 0.7, 1),
                new RetrievalEval.ModeScore(Mode.HYBRID_RERANKED, 0.75, 1, 0.85, 1));
        List<RetrievalEval.QuestionRow> questions = List.of(
                new RetrievalEval.QuestionRow("q01", "os", List.of(4), true,
                        Map.of(Mode.KEYWORD, 1, Mode.VECTOR, 2, Mode.HYBRID, 1, Mode.HYBRID_RERANKED, 1), 0.91, 0.91),
                new RetrievalEval.QuestionRow("q02", "os", List.of(9), true,
                        Map.of(Mode.KEYWORD, 3, Mode.VECTOR, 0, Mode.HYBRID, 4, Mode.HYBRID_RERANKED, 7), 0.6, 0.2),
                new RetrievalEval.QuestionRow("q03", "rm", List.of(2, 3), true,
                        Map.of(Mode.KEYWORD, 2, Mode.VECTOR, 1, Mode.HYBRID, 1, Mode.HYBRID_RERANKED, 2), 0.8, 0.7),
                new RetrievalEval.QuestionRow("u01", "", List.of(), false, Map.of(), 0.04, -1));
        RetrievalEval.Result retrieval = new RetrievalEval.Result(3, modes, 1, modes, questions,
                List.of(new RetrievalEval.ThresholdRow(0, 1, 0), new RetrievalEval.ThresholdRow(0.1, 1, 1),
                        new RetrievalEval.ThresholdRow(0.15, 2 / 3.0, 1), new RetrievalEval.ThresholdRow(0.2, 2 / 3.0, 1)),
                0.1, 0.15, 40, 12, 300, Map.of());

        AnswerEval.Result answers = new AnswerEval.Result(List.of(
                new AnswerEval.Row("q01", "covered", "What does the process control block hold?",
                        AnswerEval.Outcome.ANSWERED, "", 4, 1, 1, 1, 1, 1.0, 900, 6000,
                        "Process control block: the state, registers and open files of a process [1]."),
                new AnswerEval.Row("q02", "covered", "Why does round robin need a quantum?", AnswerEval.Outcome.NOT_COVERED,
                        "answerer", 2, 0, 0, 0, 0, 0, 1200, 3000, "The material does not say."),
                new AnswerEval.Row("q03", "covered", "What is a sampling frame?", AnswerEval.Outcome.ERROR, "", 0, 0, 0, 0,
                        0, 0, -1, 300_000, "No answer within 300 s"),
                new AnswerEval.Row("s01", "scanned", "What is a pilot study?", AnswerEval.Outcome.ANSWERED, "", 3, 2, 2, 2,
                        1, 0.5, 1500, 8000, "A small trial run of a study [1][2]."),
                new AnswerEval.Row("u01", "uncovered", "Who wrote the first Unix shell?", AnswerEval.Outcome.NOT_COVERED,
                        "retrieval", 0, 0, 0, 0, 0, 0, -1, 400, "Your course material doesn't cover this.")),
                List.of(new ChatLog.Completion("answers", "interactive", 1800, 120, 900, 6000, "stop")));

        List<GenerationEval.StoredItem> items = List.of(
                new GenerationEval.StoredItem(ItemType.MCQ, "Which scheduler gives each process a fixed time slice?",
                        "Round robin", List.of("FIFO", "Lottery", "Priority"), "Round robin uses a quantum.", "os-chapter",
                        4, SupportStatus.SUPPORTED, "", Optional.empty()),
                new GenerationEval.StoredItem(ItemType.TRUE_FALSE, "Paging needs no page table.", "Maybe", List.of(), "",
                        "os-chapter", 12, SupportStatus.UNSUPPORTED, "Not in the passage",
                        Optional.of("a true-or-false statement needs a true or false answer")),
                new GenerationEval.StoredItem(ItemType.FLASHCARD, "Sampling frame", "The list a sample is drawn from", List.of(),
                        "", null, null, SupportStatus.UNCHECKED, "", Optional.empty()));
        GenerationEval.Result generation = new GenerationEval.Result(List.of("process", "scheduling"), 40_000, 36, 40, 3, 1,
                600_000, items, List.of(new GenerationEval.DuplicatePair("Paging needs no page table.",
                        "Paging needs a page table.", 0.8)), Map.of("answer not among the options", 2L), 45, 1,
                List.of(new GenerationEval.SupportPair("q01", true, true, "", 900),
                        new GenerationEval.SupportPair("q03", false, true, "The passage mentions paging", 1100)), 1);

        GradingEval.Result grading = new GradingEval.Result(List.of(
                new GradingEval.Row("sa01", "correct", Verdict.CORRECT, Verdict.CORRECT, 0.95, 2000, "Complete.", true),
                new GradingEval.Row("sa01", "partial", Verdict.PARTIAL, Verdict.INCORRECT, 0.1, 2100, "Misses the cost.", true),
                new GradingEval.Row("sa01", "wrong", Verdict.INCORRECT, Verdict.INCORRECT, 0, 1900, "Not about paging.",
                        true)), 3, 0);

        MemorySampler.Peaks memory = new MemorySampler.Peaks(
                Map.of(ServerRole.CHAT, 3L << 30, ServerRole.EMBED, 1L << 29, ServerRole.RERANK, 1L << 29),
                Map.of(ServerRole.CHAT, 4L << 30, ServerRole.EMBED, 1L << 29, ServerRole.RERANK, 1L << 29), 4L << 30, 5L << 30,
                Map.of("answers", 5L << 30), 3000, new MemorySampler.Activity(3, 6, 0, 0));
        return new ModelRun.Result(ModelChoice.CHAT_4B, 1_500_000,
                new ModelRun.LoadTimes(Map.of(ServerRole.CHAT, 3000L, ServerRole.EMBED, 1300L, ServerRole.RERANK, 1300L), 3000),
                ingestion, retrieval, answers, generation, grading, memory,
                Map.of(ServerRole.CHAT, new ServerLogs.Buffers(Map.of("model", 2600.0, "KV", 576.0, "compute", 300.0),
                        Map.of("MTL0", 3476.0))),
                List.of(new ChatLog.Completion("answers", "interactive", 1800, 120, 900, 6000, "stop")), null, List.of());
    }
}
