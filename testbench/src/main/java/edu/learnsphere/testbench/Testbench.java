package edu.learnsphere.testbench;

import edu.learnsphere.ai.runtime.HardwareProbe;
import edu.learnsphere.ai.runtime.ModelCatalog;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.pdf.PdfBoxTextExtractor;
import edu.learnsphere.testbench.eval.ModelRun;
import edu.learnsphere.testbench.material.Material;
import edu.learnsphere.testbench.report.Environment;
import edu.learnsphere.testbench.report.ReportWriter;
import edu.learnsphere.testbench.truth.EvaluationSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point of the model testbench: {@code Testbench <report.md>}.
 *
 * <ol>
 *   <li>Generates the sample material and checks the ground truth against it.</li>
 *   <li>Finds the runtime folder ({@code learnsphere.runtime} system property). Without one, or without a chat
 *       model in it, the AI evaluations are skipped and the report says why.</li>
 *   <li>Evaluates each chat model ({@code learnsphere.testbench.models}, a comma-separated list of codes such as
 *       {@code chat-4b}; every available model when empty) with {@link ModelRun}.</li>
 *   <li>Writes the Markdown report.</li>
 * </ol>
 *
 * <p>Work files go to {@code learnsphere.testbench.work} (default {@code build/testbench}).
 */
public final class Testbench {

    private static final Logger LOG = LoggerFactory.getLogger(Testbench.class);
    /** Lucene reports through java.util.logging; kept here so its level setting is not garbage-collected. */
    private static final java.util.logging.Logger LUCENE = java.util.logging.Logger.getLogger("org.apache.lucene");

    private Testbench() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: Testbench <report.md>");
            System.exit(2);
        }
        LUCENE.setLevel(java.util.logging.Level.WARNING);
        Path report = Path.of(args[0]).toAbsolutePath();
        Path work = Path.of(System.getProperty("learnsphere.testbench.work", "build/testbench")).toAbsolutePath();
        Optional<Path> runtime = runtimeFolder();
        ZonedDateTime started = ZonedDateTime.now();
        long start = System.nanoTime();

        EvaluationSet truth = EvaluationSet.load();
        Material material = Material.generate(Files.createDirectories(work.resolve("material")), truth.scan().pages());
        List<String> problems = truth.validate(material, new PdfBoxTextExtractor());
        if (!problems.isEmpty()) {
            throw new IllegalStateException("The ground truth does not match the generated material:\n- "
                    + String.join("\n- ", problems));
        }
        LOG.info("Material written to {}; ground truth checked", work.resolve("material"));

        Environment environment = Environment.probe(runtime);
        List<ModelRun.Result> results = new ArrayList<>();
        String skipped = null;
        if (runtime.isEmpty()) {
            skipped = "No runtime folder was found. Pass one with `-Plearnsphere.runtime=/path/to/runtime` (or the "
                    + "LEARNSPHERE_RUNTIME environment variable); it holds the llama.cpp build and the model files.";
        } else {
            List<ModelChoice> models = modelsToEvaluate(runtime.get());
            if (models.isEmpty()) {
                skipped = "The runtime folder " + runtime.get() + " has no complete set of models (a chat model with its "
                        + "vision projector, the embedding model and the reranking model) for this computer.";
            }
            for (ModelChoice model : models) {
                LOG.info("Evaluating {}", model.code());
                results.add(ModelRun.run(model, runtime.get(), work.resolve("home-" + model.code()), material, truth));
            }
        }
        if (skipped != null) {
            LOG.warn("AI evaluations skipped: {}", skipped);
        }
        long totalMs = (System.nanoTime() - start) / 1_000_000;
        String markdown = ReportWriter.write(new ReportWriter.Inputs(started, totalMs, environment, material, truth, results,
                skipped));
        write(report, markdown);
        LOG.info("Report written to {}", report);
    }

    /** The runtime folder from {@code learnsphere.runtime}, when it names an existing folder. */
    private static Optional<Path> runtimeFolder() {
        String value = System.getProperty("learnsphere.runtime", "").strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        Path path = Path.of(value).toAbsolutePath();
        return Files.isDirectory(path) ? Optional.of(path) : Optional.empty();
    }

    /** The chat models to evaluate: the requested ones that are available, or every available one. */
    private static List<ModelChoice> modelsToEvaluate(Path runtime) {
        ModelCatalog catalog = ModelCatalog.scan(Optional.of(runtime), HardwareProbe.probe());
        List<ModelChoice> available = List.of(ModelChoice.CHAT_4B, ModelChoice.CHAT_9B).stream()
                .filter(choice -> catalog.availableModels().contains(choice) && catalog.problem(choice).isEmpty())
                .toList();
        String requested = System.getProperty("learnsphere.testbench.models", "").strip();
        if (requested.isEmpty()) {
            return available;
        }
        List<ModelChoice> wanted = Arrays.stream(requested.split(",")).map(String::strip).filter(s -> !s.isEmpty())
                .map(code -> ModelChoice.fromCode(code.toLowerCase(Locale.ROOT))).toList();
        List<ModelChoice> missing = wanted.stream().filter(m -> !available.contains(m)).toList();
        if (!missing.isEmpty()) {
            LOG.warn("Not available in {}: {}", runtime, missing.stream().map(ModelChoice::code).collect(Collectors.joining(", ")));
        }
        return wanted.stream().filter(available::contains).distinct().toList();
    }

    private static void write(Path report, String content) throws IOException {
        Path parent = report.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(report, content, StandardCharsets.UTF_8);
    }
}
