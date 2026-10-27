package edu.learnsphere.testbench.report;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.runtime.ServerRole;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.testbench.eval.IngestionEval;
import edu.learnsphere.testbench.eval.ModelRun;
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
                3L << 30, 4L << 30, Map.of("ingest:os", 4L << 30), 120);
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
                "## Resource use", "## Appendix B: every generated item");
        assertThat(report).doesNotContain("null");
    }
}
