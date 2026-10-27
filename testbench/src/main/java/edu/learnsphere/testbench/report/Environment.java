package edu.learnsphere.testbench.report;

import edu.learnsphere.ai.runtime.GgufMetadata;
import edu.learnsphere.ai.runtime.HardwareProbe;
import edu.learnsphere.ai.runtime.ModelCatalog;
import edu.learnsphere.ai.runtime.ServerOptions;
import edu.learnsphere.core.spi.ModelChoice;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The computer and software the evaluation ran on: hardware, operating system, Java, the llama.cpp build and the
 * model files (with what their GGUF metadata says about them).
 *
 * @param runtime      the runtime folder, when there is one
 * @param llamaVersion what {@code llama-server --version} printed, or empty when it could not be run
 * @param serverFlags  the server settings the app uses
 */
public record Environment(String cpu, int processors, long memoryBytes, String operatingSystem, String java,
                          Optional<Path> runtime, String llamaVersion, List<ModelFile> models, String serverFlags) {

    /**
     * One model file.
     *
     * @param role what the app uses it for
     */
    public record ModelFile(String role, String file, long bytes, String name, String architecture, String sizeLabel,
                            String contextLength) {
    }

    public Environment {
        models = List.copyOf(models);
    }

    /** Describes this computer and the runtime folder (which may be absent). */
    public static Environment probe(Optional<Path> runtime) {
        HardwareProbe.Hardware hardware = HardwareProbe.probe();
        ModelCatalog catalog = ModelCatalog.scan(runtime, hardware);
        List<ModelFile> models = new ArrayList<>();
        for (ModelChoice choice : List.of(ModelChoice.CHAT_4B, ModelChoice.CHAT_9B)) {
            if (catalog.availableModels().contains(choice)) {
                catalog.chatModel(choice).ifPresent(chat -> {
                    models.add(describe("Chat (" + choice.code() + ")", chat.model()));
                    chat.projector().ifPresent(p -> models.add(describe("Vision projector (" + choice.code() + ")", p)));
                });
            }
        }
        catalog.embeddingModel().ifPresent(p -> models.add(describe("Embedding", p)));
        catalog.rerankModel().ifPresent(p -> models.add(describe("Reranking", p)));
        String version = catalog.serverExecutable().map(Environment::llamaVersion).orElse("");
        ServerOptions options = ServerOptions.defaults();
        String flags = String.format(Locale.ROOT, "chat context %d tokens, embedding and reranking context %d/%d tokens, "
                        + "all layers on the GPU (-ngl %d), flash attention on, one request at a time per server, "
                        + "%d MiB prompt cache", options.chatContext(), options.embedContext(), options.rerankContext(),
                options.gpuLayers(), options.chatCacheMib());
        return new Environment(probeCpu(), Runtime.getRuntime().availableProcessors(), hardware.totalMemoryBytes(),
                probeOperatingSystem(), System.getProperty("java.vm.name", "Java") + " " + Runtime.version(), runtime,
                version, models, flags);
    }

    private static ModelFile describe(String role, Path file) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException e) {
            size = 0;
        }
        String name = "";
        String architecture = "";
        String sizeLabel = "";
        String context = "";
        try {
            Map<String, Object> values = GgufMetadata.read(file, seen -> {
                Object arch = seen.get("general.architecture");
                return arch != null && seen.containsKey(arch + ".context_length");
            });
            architecture = String.valueOf(values.getOrDefault("general.architecture", ""));
            name = String.valueOf(values.getOrDefault("general.name", ""));
            sizeLabel = String.valueOf(values.getOrDefault("general.size_label", ""));
            Object length = values.get(architecture + ".context_length");
            context = length == null ? "" : String.valueOf(length);
        } catch (IOException | RuntimeException e) {
            // Not every file says everything; the report shows what is known.
        }
        return new ModelFile(role, file.getFileName().toString(), size, name, architecture, sizeLabel, context);
    }

    private static String llamaVersion(Path executable) {
        String output = run(List.of(executable.toString(), "--version"), executable.getParent());
        return output.lines().filter(line -> line.startsWith("version:")).findFirst()
                .map(line -> line.substring("version:".length()).strip()).orElse("");
    }

    private static String probeCpu() {
        String brand = run(List.of("sysctl", "-n", "machdep.cpu.brand_string"), null).strip();
        return brand.isEmpty() ? System.getProperty("os.arch", "unknown") : brand;
    }

    private static String probeOperatingSystem() {
        String name = run(List.of("sw_vers", "-productName"), null).strip();
        String version = run(List.of("sw_vers", "-productVersion"), null).strip();
        if (!name.isEmpty() && !version.isEmpty()) {
            return name + " " + version + " (" + System.getProperty("os.arch") + ")";
        }
        return System.getProperty("os.name") + " " + System.getProperty("os.version") + " (" + System.getProperty("os.arch")
                + ")";
    }

    private static String run(List<String> command, Path directory) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
            if (directory != null) {
                builder.directory(directory.toFile());
            }
            Process process = builder.start();
            process.getOutputStream().close();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(20, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return output;
        } catch (IOException e) {
            return "";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "";
        }
    }
}
