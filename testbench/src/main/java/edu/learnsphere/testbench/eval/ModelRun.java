package edu.learnsphere.testbench.eval;

import edu.learnsphere.ai.LocalAi;
import edu.learnsphere.ai.runtime.HardwareProbe;
import edu.learnsphere.ai.runtime.LlamaServerManager;
import edu.learnsphere.ai.runtime.ServerOptions;
import edu.learnsphere.ai.runtime.ServerRole;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.profile.ProfileRegistry;
import edu.learnsphere.core.security.PasswordHasher;
import edu.learnsphere.core.service.ProfileDraft;
import edu.learnsphere.core.service.ProfileService;
import edu.learnsphere.core.service.ProfileServices;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.Workspace;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.pdf.PdfModule;
import edu.learnsphere.search.LuceneSearchIndexFactory;
import edu.learnsphere.testbench.material.Material;
import edu.learnsphere.testbench.probe.ChatLog;
import edu.learnsphere.testbench.probe.MemorySampler;
import edu.learnsphere.testbench.probe.Recorder;
import edu.learnsphere.testbench.probe.ServerLogs;
import edu.learnsphere.testbench.truth.EvaluationSet;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates one chat model: starts the llama-server processes with it and times their loading, opens a fresh
 * profile on the app's real services (PDF tools, Lucene search, the local AI, all recorded), runs every
 * evaluation in turn, and always stops the servers it started.
 *
 * <p>An evaluation that fails ends the run; the results gathered before it are kept and the failure is
 * reported.
 */
public final class ModelRun {

    private static final Logger LOG = LoggerFactory.getLogger(ModelRun.class);
    private static final Duration LOAD_TIMEOUT = Duration.ofMinutes(5);

    /**
     * How long each server took from launch until it answered its health check.
     *
     * @param readyMs per role
     * @param allMs   until every role was ready (they load in parallel)
     */
    public record LoadTimes(Map<ServerRole, Long> readyMs, long allMs) {

        public LoadTimes {
            readyMs = Map.copyOf(readyMs);
        }
    }

    /**
     * One model's results. Evaluations that did not run are null.
     *
     * @param failure  why the run ended early, or null when every evaluation ran
     * @param leftover server processes still running after the run (stopped by the testbench), normally empty
     */
    public record Result(ModelChoice model, long totalMs, LoadTimes load, IngestionEval.Result ingestion,
                         RetrievalEval.Result retrieval, AnswerEval.Result answers, GenerationEval.Result generation,
                         GradingEval.Result grading, MemorySampler.Peaks memory, Map<ServerRole, ServerLogs.Buffers> buffers,
                         List<ChatLog.Completion> completions, String failure, List<Long> leftover) {

        public boolean complete() {
            return failure == null;
        }
    }

    private ModelRun() {
    }

    /**
     * Runs every evaluation with {@code model}.
     *
     * @param home a folder for the profile and server logs; emptied first
     */
    public static Result run(ModelChoice model, Path runtimeDirectory, Path home, Material material, EvaluationSet truth)
            throws IOException {
        deleteRecursively(home);
        AppPaths paths = AppPaths.at(home);
        Files.createDirectories(paths.logsDir());
        long started = System.nanoTime();
        Recorder recorder = new Recorder();
        ChatLog chatLog = ChatLog.install(recorder::currentLabel);
        LocalAi ai = LocalAi.create(Optional.of(runtimeDirectory), HardwareProbe.probe(), paths.logsDir(),
                ServerOptions.defaults());
        LlamaServerManager runtime = ai.runtime();
        Set<Long> seen = ConcurrentHashMap.newKeySet();
        MemorySampler sampler = new MemorySampler(() -> {
            Map<ServerRole, Long> ids = runtime.processIds();
            seen.addAll(ids.values());
            return ids;
        }, recorder::currentLabel);

        LoadTimes load = null;
        IngestionEval.Result ingestion = null;
        RetrievalEval.Result retrieval = null;
        AnswerEval.Result answers = null;
        GenerationEval.Result generation = null;
        GradingEval.Result grading = null;
        String failure = null;
        Workspace workspace = null;
        try {
            recorder.label("load");
            LOG.info("Starting the servers with {}", model.code());
            load = measureLoad(runtime, model);
            LOG.info("Servers ready in {} s", String.format("%.1f", load.allMs() / 1000.0));
            Capabilities base = PdfModule.install(Capabilities.builder())
                    .searchIndexFactory(new LuceneSearchIndexFactory())
                    .ai(ai.capabilities())
                    .build();
            Capabilities capabilities = recorder.instrument(base, model);
            ProfileService profiles = ProfileServices.create(paths, new ProfileRegistry(paths.profilesFile()),
                    new PasswordHasher(), Clock.systemDefaultZone(), capabilities);
            ProfileSummary profile = profiles.create(ProfileDraft.of("Testbench", 200));
            workspace = profiles.open(profile.id(), null);
            Session session = new Session(workspace, capabilities, recorder, chatLog, material, truth);
            ingestion = IngestionEval.run(session);
            retrieval = RetrievalEval.run(session);
            answers = AnswerEval.run(session);
            generation = GenerationEval.run(session, retrieval.bestPassages());
            grading = GradingEval.run(session);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = "Interrupted";
        } catch (RuntimeException e) {
            LOG.error("The {} run ended early", model.code(), e);
            failure = e.getClass().getSimpleName() + ": " + e.getMessage();
        } finally {
            recorder.label("closing");
            if (workspace != null) {
                try {
                    workspace.close();
                } catch (RuntimeException e) {
                    LOG.warn("Closing the profile failed", e);
                }
            }
            ai.close();
        }
        MemorySampler.Peaks memory = sampler.finish();
        List<ChatLog.Completion> completions = chatLog.completions();
        chatLog.close();
        List<Long> leftover = stopLeftovers(seen);
        Map<ServerRole, ServerLogs.Buffers> buffers = new EnumMap<>(ServerRole.class);
        for (ServerRole role : ServerRole.values()) {
            buffers.put(role, ServerLogs.read(runtime.logFile(role)));
        }
        long totalMs = (System.nanoTime() - started) / 1_000_000;
        return new Result(model, totalMs, load, ingestion, retrieval, answers, generation, grading, memory, buffers,
                completions, failure, leftover);
    }

    /** Starts the servers with {@code model} and waits until every role answers its health check. */
    static LoadTimes measureLoad(LlamaServerManager runtime, ModelChoice model) throws InterruptedException {
        long start = System.nanoTime();
        runtime.start(model);
        Map<ServerRole, Long> ready = new EnumMap<>(ServerRole.class);
        long deadline = start + LOAD_TIMEOUT.toNanos();
        while (ready.size() < ServerRole.values().length) {
            for (ServerRole role : ServerRole.values()) {
                if (!ready.containsKey(role) && runtime.endpoint(role).isPresent()) {
                    ready.put(role, (System.nanoTime() - start) / 1_000_000);
                }
            }
            AiStatus status = runtime.status();
            if (status.state() == AiStatus.State.FAILED || status.state() == AiStatus.State.UNAVAILABLE) {
                throw new IllegalStateException("The servers did not start: " + status.detail());
            }
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("The servers were not ready within " + LOAD_TIMEOUT);
            }
            Thread.sleep(25);
        }
        return new LoadTimes(ready, ready.values().stream().mapToLong(Long::longValue).max().orElse(0));
    }

    /** Ends any server process this run started that is somehow still running, and returns their ids. */
    private static List<Long> stopLeftovers(Set<Long> seen) {
        List<Long> alive = seen.stream()
                .filter(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false))
                .sorted().toList();
        for (long pid : alive) {
            LOG.warn("Server process {} was still running after the run; stopping it", pid);
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
        }
        return alive;
    }

    static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
