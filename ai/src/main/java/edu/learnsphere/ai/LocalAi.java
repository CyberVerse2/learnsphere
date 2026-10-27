package edu.learnsphere.ai;

import edu.learnsphere.ai.client.ChatGate;
import edu.learnsphere.ai.client.LlamaConceptExtractor;
import edu.learnsphere.ai.client.LlamaEmbedder;
import edu.learnsphere.ai.client.LlamaFigureDescriber;
import edu.learnsphere.ai.client.LlamaGroundedAnswerer;
import edu.learnsphere.ai.client.LlamaItemGenerator;
import edu.learnsphere.ai.client.LlamaPageReader;
import edu.learnsphere.ai.client.LlamaReranker;
import edu.learnsphere.ai.client.LlamaShortAnswerGrader;
import edu.learnsphere.ai.client.LlamaSupportChecker;
import edu.learnsphere.ai.client.LocalChat;
import edu.learnsphere.ai.client.OpenAiClient;
import edu.learnsphere.ai.runtime.HardwareProbe;
import edu.learnsphere.ai.runtime.LlamaServerManager;
import edu.learnsphere.ai.runtime.ModelCatalog;
import edu.learnsphere.ai.runtime.RuntimeLocator;
import edu.learnsphere.ai.runtime.ServerOptions;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.spi.AiCapabilities;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The real AI of LearnSphere: the llama-server runtime and an implementation of every AI interface on
 * top of it, bundled as {@link AiCapabilities}. Creating it starts nothing; the servers start when
 * {@link edu.learnsphere.core.spi.AiRuntime#start} is called (a workspace does so when it opens) or when a
 * request first needs them.
 *
 * <pre>{@code
 * LocalAi ai = LocalAi.create(paths);
 * Capabilities capabilities = Capabilities.builder()
 *         .pdf(inspector, extractor, chunker, renderer, locator)
 *         .searchIndexFactory(factory)
 *         .ai(ai.capabilities())
 *         .build();
 * // on exit: capabilities.aiRuntime().close()  (or ai.close())
 * }</pre>
 */
public final class LocalAi implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(LocalAi.class);

    private final LlamaServerManager runtime;
    private final LocalChat chat;
    private final AiCapabilities capabilities;

    private LocalAi(LlamaServerManager runtime, OpenAiClient client) {
        this.runtime = runtime;
        this.chat = new LocalChat(runtime, client, new ChatGate());
        ModelCatalog catalog = runtime.catalog();
        this.capabilities = new AiCapabilities(
                runtime,
                new LlamaEmbedder(runtime, client, catalog.embeddingDimensions(), catalog.embeddingModelName()),
                new LlamaReranker(runtime, client),
                new LlamaGroundedAnswerer(chat),
                new LlamaConceptExtractor(chat),
                new LlamaItemGenerator(chat),
                new LlamaSupportChecker(chat),
                new LlamaShortAnswerGrader(chat),
                new LlamaPageReader(chat),
                new LlamaFigureDescriber(chat));
        runtime.closeWith(client);
    }

    /**
     * The app's AI: finds the runtime folder ({@link RuntimeLocator}), checks the hardware, and writes
     * server logs to the app's logs folder. Nothing starts yet.
     */
    public static LocalAi create(AppPaths paths) {
        Objects.requireNonNull(paths, "paths");
        Optional<Path> runtimeDirectory = RuntimeLocator.locate();
        HardwareProbe.Hardware hardware = HardwareProbe.probe();
        LOG.info("AI runtime folder: {}; {} GiB memory, {} processors, platform {}",
                runtimeDirectory.map(Path::toString).orElse("not found"),
                String.format(java.util.Locale.ROOT, "%.1f", hardware.totalMemoryGib()), hardware.processors(),
                hardware.platform().map(Enum::name).orElse("unsupported"));
        return create(runtimeDirectory, hardware, paths.logsDir(), ServerOptions.defaults());
    }

    /** An AI over an explicit runtime folder, hardware, logs folder and server options (tests, testbench). */
    public static LocalAi create(Optional<Path> runtimeDirectory, HardwareProbe.Hardware hardware, Path logsDirectory,
                                 ServerOptions options) {
        LlamaServerManager runtime = new LlamaServerManager(runtimeDirectory, hardware, logsDirectory, options);
        return new LocalAi(runtime, new OpenAiClient());
    }

    /** Shorthand for {@code create(paths).capabilities()}; close the bundle's runtime on exit. */
    public static AiCapabilities capabilities(AppPaths paths) {
        return create(paths).capabilities();
    }

    /** Every AI slot, ready for {@code Capabilities.builder().ai(…)}. */
    public AiCapabilities capabilities() {
        return capabilities;
    }

    /** The server processes (also {@link AiCapabilities#runtime()}). */
    public LlamaServerManager runtime() {
        return runtime;
    }

    /** The prioritized chat channel every chat-based capability shares. */
    public LocalChat chat() {
        return chat;
    }

    /** Stops the servers and releases the HTTP client. */
    @Override
    public void close() {
        runtime.close();
    }
}
