package edu.learnsphere.ai.runtime;

import edu.learnsphere.core.spi.ModelChoice;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the runtime folder holds, read once by {@link #scan}: the llama.cpp server for this platform and the
 * model files, located by glob so swapping quantizations needs no code change.
 *
 * <pre>
 * runtime/
 *   llama/&lt;platform folder&gt;/**&#47;llama-server[.exe]
 *   models/chat-4b/*.gguf          chat model; the file starting with "mmproj" is its vision projector
 *   models/chat-9b/*.gguf          optional larger chat model, same layout
 *   models/embed/*.gguf
 *   models/rerank/*.gguf
 * </pre>
 *
 * <p>Model choice: a chat model is available when its files, the server, and the embedding and reranking
 * models exist; the 9B model also needs at least {@value #MIN_GIB_9B} GiB of memory, so it is not offered when
 * memory is unknown.
 * {@link ModelChoice#AUTO} resolves to the 9B model only with {@value #RECOMMEND_GIB_9B} GiB or more,
 * otherwise to the 4B model.
 */
public final class ModelCatalog {

    /** Memory below which the 9B model is not offered. */
    public static final int MIN_GIB_9B = 12;
    /** Memory from which {@link ModelChoice#AUTO} picks the 9B model. */
    public static final int RECOMMEND_GIB_9B = 16;
    /** Vector length assumed when the embedding model cannot be read (the bundled model's). */
    public static final int DEFAULT_EMBEDDING_DIMENSIONS = 1024;
    /** Embedding model name assumed when the model file is missing (the bundled model's file name). */
    public static final String DEFAULT_EMBEDDING_NAME = "Qwen3-Embedding-0.6B-Q8_0";

    private static final Logger LOG = LoggerFactory.getLogger(ModelCatalog.class);
    private static final Pattern BUILD_NUMBER = Pattern.compile("(?:^|[^a-z])b(\\d{3,})(?:$|[^0-9])");

    /**
     * The files of one chat model.
     *
     * @param projector the vision projector; without it the model cannot read page images
     */
    public record ChatModel(ModelChoice choice, Path model, Optional<Path> projector) {

        public ChatModel {
            Objects.requireNonNull(choice, "choice");
            Objects.requireNonNull(model, "model");
            projector = projector == null ? Optional.empty() : projector;
        }

        /** True when the model can read images. */
        public boolean hasVision() {
            return projector.isPresent();
        }
    }

    private final Optional<Path> runtimeDirectory;
    private final HardwareProbe.Hardware hardware;
    private final Optional<Path> serverExecutable;
    private final Optional<ChatModel> chat4b;
    private final Optional<ChatModel> chat9b;
    private final Optional<Path> embeddingModel;
    private final Optional<Path> rerankModel;
    private final int embeddingDimensions;

    private ModelCatalog(Optional<Path> runtimeDirectory, HardwareProbe.Hardware hardware) {
        this.runtimeDirectory = runtimeDirectory;
        this.hardware = hardware;
        Optional<Path> models = runtimeDirectory.map(dir -> dir.resolve("models"));
        this.serverExecutable = runtimeDirectory.flatMap(dir -> hardware.platform().flatMap(p -> findServer(dir, p)));
        this.chat4b = models.flatMap(dir -> findChat(dir, ModelChoice.CHAT_4B));
        this.chat9b = models.flatMap(dir -> findChat(dir, ModelChoice.CHAT_9B));
        this.embeddingModel = models.flatMap(dir -> findModel(dir.resolve("embed")));
        this.rerankModel = models.flatMap(dir -> findModel(dir.resolve("rerank")));
        this.embeddingDimensions = embeddingModel.map(GgufMetadata::embeddingLength)
                .flatMap(length -> length.isPresent() ? Optional.of(length.getAsInt()) : Optional.empty())
                .orElse(DEFAULT_EMBEDDING_DIMENSIONS);
    }

    /** Scans {@code runtimeDirectory} (empty when no runtime folder was found). Never throws for missing files. */
    public static ModelCatalog scan(Optional<Path> runtimeDirectory, HardwareProbe.Hardware hardware) {
        return new ModelCatalog(Objects.requireNonNull(runtimeDirectory, "runtimeDirectory"),
                Objects.requireNonNull(hardware, "hardware"));
    }

    /** The runtime folder, when one was found. */
    public Optional<Path> runtimeDirectory() {
        return runtimeDirectory;
    }

    /** The hardware the choice is made for. */
    public HardwareProbe.Hardware hardware() {
        return hardware;
    }

    /** The llama.cpp server for this platform. */
    public Optional<Path> serverExecutable() {
        return serverExecutable;
    }

    /** The files of a chat model ({@link ModelChoice#AUTO} is resolved first). */
    public Optional<ChatModel> chatModel(ModelChoice choice) {
        return switch (resolve(choice)) {
            case CHAT_9B -> chat9b;
            default -> chat4b;
        };
    }

    /** The embedding model file. */
    public Optional<Path> embeddingModel() {
        return embeddingModel;
    }

    /** The reranking model file. */
    public Optional<Path> rerankModel() {
        return rerankModel;
    }

    /** Vector length of the embedding model (read from its header), or {@value #DEFAULT_EMBEDDING_DIMENSIONS}. */
    public int embeddingDimensions() {
        return embeddingDimensions;
    }

    /** Name stored with embeddings: the embedding model's file name without extension. */
    public String embeddingModelName() {
        return embeddingModel.map(ModelCatalog::stem).orElse(DEFAULT_EMBEDDING_NAME);
    }

    /** Chat models whose files are present and that this machine can run. */
    public Set<ModelChoice> availableModels() {
        Set<ModelChoice> available = EnumSet.noneOf(ModelChoice.class);
        if (serverExecutable.isEmpty() || embeddingModel.isEmpty() || rerankModel.isEmpty()) {
            return available;
        }
        if (chat4b.isPresent()) {
            available.add(ModelChoice.CHAT_4B);
        }
        if (chat9b.isPresent() && hardware.hasAtLeastGib(MIN_GIB_9B)) {
            available.add(ModelChoice.CHAT_9B);
        }
        return available;
    }

    /** What {@link ModelChoice#AUTO} resolves to: 9B with enough memory and its files, else 4B. */
    public ModelChoice recommendedModel() {
        if (availableModels().contains(ModelChoice.CHAT_9B) && hardware.hasAtLeastGib(RECOMMEND_GIB_9B)) {
            return ModelChoice.CHAT_9B;
        }
        return ModelChoice.CHAT_4B;
    }

    /** {@code choice} with {@link ModelChoice#AUTO} (or null) replaced by {@link #recommendedModel()}. */
    public ModelChoice resolve(ModelChoice choice) {
        return choice == null || choice == ModelChoice.AUTO ? recommendedModel() : choice;
    }

    /**
     * Why {@code choice} cannot run here, for logs, or empty when everything it needs is present.
     */
    public Optional<String> problem(ModelChoice choice) {
        ModelChoice model = resolve(choice);
        if (runtimeDirectory.isEmpty()) {
            return Optional.of("no runtime folder found (set -D" + RuntimeLocator.PROPERTY + " or "
                    + RuntimeLocator.ENVIRONMENT + ")");
        }
        if (hardware.platform().isEmpty()) {
            return Optional.of("no llama.cpp build exists for " + System.getProperty("os.name") + " "
                    + System.getProperty("os.arch"));
        }
        if (serverExecutable.isEmpty()) {
            return Optional.of("no " + hardware.platform().get().serverExecutable() + " under "
                    + runtimeDirectory.get().resolve("llama").resolve(hardware.platform().get().folder()));
        }
        if (embeddingModel.isEmpty()) {
            return Optional.of("no embedding model (*.gguf) in models/embed");
        }
        if (rerankModel.isEmpty()) {
            return Optional.of("no reranking model (*.gguf) in models/rerank");
        }
        if (chatModel(model).isEmpty()) {
            return Optional.of("no chat model (*.gguf) in models/" + model.folder());
        }
        if (!availableModels().contains(model)) {
            return Optional.of(model.code() + " needs at least " + MIN_GIB_9B + " GiB of memory");
        }
        return Optional.empty();
    }

    private static Optional<Path> findServer(Path runtime, Platform platform) {
        Path folder = runtime.resolve("llama").resolve(platform.folder());
        if (!Files.isDirectory(folder)) {
            return Optional.empty();
        }
        List<Path> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(folder, 6)) {
            files.filter(p -> p.getFileName().toString().equals(platform.serverExecutable()))
                    .filter(Files::isRegularFile)
                    .forEach(found::add);
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("Cannot read {}", folder, e);
            return Optional.empty();
        }
        // Prefer the newest llama.cpp build when several are present (folders named like llama-b11550).
        return found.stream()
                .max(Comparator.comparingLong(ModelCatalog::buildNumber).thenComparing(Path::toString))
                .map(p -> p.toAbsolutePath().normalize());
    }

    static long buildNumber(Path path) {
        long best = -1;
        for (Path part : path) {
            Matcher m = BUILD_NUMBER.matcher(part.toString().toLowerCase(Locale.ROOT));
            if (m.find()) {
                best = Math.max(best, Long.parseLong(m.group(1)));
            }
        }
        return best;
    }

    private static Optional<ChatModel> findChat(Path models, ModelChoice choice) {
        Path folder = models.resolve(choice.folder());
        return findModel(folder).map(model -> new ChatModel(choice, model, ggufFiles(folder).stream()
                .filter(ModelCatalog::isProjector)
                .findFirst()));
    }

    private static Optional<Path> findModel(Path folder) {
        List<Path> candidates = ggufFiles(folder).stream().filter(p -> !isProjector(p)).toList();
        if (candidates.size() > 1) {
            LOG.warn("Several models in {}; using {}", folder, candidates.getFirst().getFileName());
        }
        return candidates.stream().findFirst();
    }

    private static boolean isProjector(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).startsWith("mmproj");
    }

    private static List<Path> ggufFiles(Path folder) {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gguf"))
                    .map(p -> p.toAbsolutePath().normalize())
                    .sorted()
                    .toList();
        } catch (IOException | UncheckedIOException e) {
            LOG.warn("Cannot read {}", folder, e);
            return List.of();
        }
    }

    private static String stem(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
