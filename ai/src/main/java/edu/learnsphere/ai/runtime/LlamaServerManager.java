package edu.learnsphere.ai.runtime;

import edu.learnsphere.ai.AiMessages;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.spi.AiRuntime;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Subscription;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the three server processes (chat, embeddings, reranking) and is the app's {@link AiRuntime}. Each is a
 * {@code llama-server}, except that on Apple silicon the chat server is the MLX server ({@code mlx_vlm.server})
 * when the runtime folder holds an MLX build of the chat model ({@link ModelCatalog#mlxChatModel}). Both speak
 * the same OpenAI-compatible HTTP and take the same {@code --host}, {@code --port} and {@code --api-key}.
 *
 * <ul>
 *   <li><b>Starting.</b> {@link #start} returns at once and launches each process in the background on a
 *       free port bound to {@code 127.0.0.1} with a random {@code --api-key}, then polls {@code /health}
 *       until it answers. Nothing runs until {@link #start} is called or a request needs a server
 *       ({@link #await}, which starts an idle runtime with the last requested model). Starting again with
 *       another chat model replaces only the chat process.</li>
 *   <li><b>Supervision.</b> A process that exits unexpectedly (or stops answering) is restarted up to
 *       {@link ServerOptions#maxRestarts()} times with doubling back-off; after that the runtime reports
 *       {@link AiStatus.State#FAILED} until it is started again. A process that ran for
 *       {@link ServerOptions#stableAfter()} before crashing gets its restart budget back.</li>
 *   <li><b>Logs.</b> Each process writes to {@code <logs>/llama-<role>.log}, rolled to
 *       {@code llama-<role>.1.log} at launch once it passes 10 MB.</li>
 *   <li><b>Stopping.</b> {@link #stop} ends every process (forcibly after {@link ServerOptions#stopTimeout()});
 *       a JVM shutdown hook kills any process still running when the app exits; {@link #close} stops for
 *       good.</li>
 * </ul>
 *
 * <p>Status listeners are called in order on one background thread, only when the state or model changes.
 * Thread-safe.
 */
public final class LlamaServerManager implements AiRuntime, EndpointProvider {

    private static final Logger LOG = LoggerFactory.getLogger(LlamaServerManager.class);
    private static final long LOG_ROLL_BYTES = 10L * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final AtomicInteger INSTANCES = new AtomicInteger();

    private enum Lifecycle { IDLE, RUNNING, CLOSED }

    private enum SlotState { STOPPED, STARTING, READY, FAILED }

    /**
     * What a role's process is started with, apart from its port and key.
     *
     * @param model       the model name requests must carry (MLX), or null
     * @param environment variables added to the process environment
     */
    private record LaunchSpec(Path executable, List<String> arguments, boolean vision, String model,
                              Map<String, String> environment) {
    }

    /** Keeps the MLX server from ever reaching the network: models load only from the runtime folder. */
    private static final Map<String, String> MLX_ENVIRONMENT = Map.of(
            "HF_HUB_OFFLINE", "1",
            "HF_HUB_DISABLE_TELEMETRY", "1",
            "TRANSFORMERS_OFFLINE", "1");

    /** One role's process. Guarded by {@link #lock}. */
    private static final class Slot {
        final ServerRole role;
        LaunchSpec spec;
        Process process;
        ServerEndpoint endpoint;
        SlotState state = SlotState.STOPPED;
        int restarts;
        long healthySince;
        long launchId;
        String failure;

        Slot(ServerRole role) {
            this.role = role;
        }
    }

    private final Optional<Path> runtimeDirectory;
    private final HardwareProbe.Hardware hardware;
    private final Path logsDirectory;
    private final ServerOptions options;
    private final HttpClient health;
    private final ExecutorService workers;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService notifier;
    private final List<Consumer<AiStatus>> listeners = new CopyOnWriteArrayList<>();
    private final Set<Process> liveProcesses = ConcurrentHashMap.newKeySet();
    private final List<AutoCloseable> closeWith = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private final Map<ServerRole, Slot> slots = new EnumMap<>(ServerRole.class);
    private ModelCatalog catalog;
    private Lifecycle lifecycle = Lifecycle.IDLE;
    private ModelChoice requested = ModelChoice.AUTO;
    private AiStatus status;
    private Thread shutdownHook;

    /**
     * A manager that has not started anything yet.
     *
     * @param runtimeDirectory the runtime folder ({@link RuntimeLocator#locate()}), or empty when none exists
     * @param hardware         this computer ({@link HardwareProbe#probe()})
     * @param logsDirectory    where process logs go (the app's logs folder)
     */
    public LlamaServerManager(Optional<Path> runtimeDirectory, HardwareProbe.Hardware hardware, Path logsDirectory,
                              ServerOptions options) {
        this.runtimeDirectory = Objects.requireNonNull(runtimeDirectory, "runtimeDirectory");
        this.hardware = Objects.requireNonNull(hardware, "hardware");
        this.logsDirectory = Objects.requireNonNull(logsDirectory, "logsDirectory");
        this.options = Objects.requireNonNull(options, "options");
        int instance = INSTANCES.incrementAndGet();
        this.workers = Executors.newCachedThreadPool(
                Thread.ofPlatform().daemon(true).name("learnsphere-ai-" + instance + "-launch-", 0).factory());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("learnsphere-ai-" + instance + "-restart").factory());
        this.notifier = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("learnsphere-ai-" + instance + "-status").factory());
        this.health = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(workers)
                .build();
        for (ServerRole role : ServerRole.values()) {
            slots.put(role, new Slot(role));
        }
        this.catalog = ModelCatalog.scan(runtimeDirectory, hardware);
        this.status = idleStatus();
    }

    // ---------------------------------------------------------------- AiRuntime

    @Override
    public void start(ModelChoice choice) {
        synchronized (lock) {
            if (lifecycle == Lifecycle.CLOSED) {
                LOG.warn("The AI runtime is closed; ignoring start({})", choice);
                return;
            }
            startLocked(choice == null ? ModelChoice.AUTO : choice);
        }
    }

    @Override
    public void stop() {
        List<Process> stopping = new ArrayList<>();
        synchronized (lock) {
            if (lifecycle == Lifecycle.CLOSED) {
                return;
            }
            boolean wasRunning = lifecycle == Lifecycle.RUNNING;
            lifecycle = Lifecycle.IDLE;
            for (Slot slot : slots.values()) {
                Process process = stopSlotLocked(slot);
                if (process != null) {
                    stopping.add(process);
                }
            }
            setStatusLocked(new AiStatus(AiStatus.State.UNAVAILABLE, null, "stopped"));
            lock.notifyAll();
            if (wasRunning) {
                LOG.info("Stopping the AI runtime");
            }
        }
        terminateAll(stopping);
    }

    @Override
    public AiStatus status() {
        synchronized (lock) {
            return status;
        }
    }

    @Override
    public Set<ModelChoice> availableModels() {
        return rescan().availableModels();
    }

    @Override
    public ModelChoice recommendedModel() {
        return rescan().recommendedModel();
    }

    @Override
    public Subscription addListener(Consumer<AiStatus> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    @Override
    public void close() {
        stop();
        synchronized (lock) {
            lifecycle = Lifecycle.CLOSED;
            lock.notifyAll();
        }
        listeners.clear();
        scheduler.shutdownNow();
        workers.shutdownNow();
        notifier.shutdown();
        // Processes no slot holds any more: one waiting for a restart, or one a worker was still ending.
        // A launch that was under way when this closed ends its own process (see runLaunch).
        terminateAll(List.copyOf(liveProcesses));
        Thread hook = null;
        synchronized (lock) {
            if (liveProcesses.isEmpty()) {
                hook = shutdownHook;
                shutdownHook = null;
            }
        }
        if (hook != null) {
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // The JVM is already shutting down; the hook runs anyway.
            }
        }
        health.close();
        for (AutoCloseable resource : closeWith) {
            try {
                resource.close();
            } catch (Exception e) {
                LOG.warn("Closing {} failed", resource, e);
            }
        }
        closeWith.clear();
    }

    /** Closes {@code resource} (for example the HTTP client the servers are used through) when this closes. */
    public void closeWith(AutoCloseable resource) {
        closeWith.add(Objects.requireNonNull(resource, "resource"));
    }

    // ---------------------------------------------------------------- EndpointProvider

    @Override
    public ServerEndpoint await(ServerRole role, CancellationToken token) {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(token, "token");
        long deadline = System.nanoTime() + options.readyWait().toNanos();
        Subscription wake = token.onCancel(() -> {
            synchronized (lock) {
                lock.notifyAll();
            }
        });
        try {
            synchronized (lock) {
                while (true) {
                    token.throwIfCancelled();
                    if (lifecycle == Lifecycle.CLOSED) {
                        throw new UnavailableException(UserMessage.ASSISTANT_UNAVAILABLE, "The AI runtime is closed");
                    }
                    if (lifecycle == Lifecycle.IDLE) {
                        LOG.info("Starting the AI runtime for a {} request", role.label());
                        startLocked(requested);
                    }
                    Slot slot = slots.get(role);
                    if (slot.endpoint != null) {
                        return slot.endpoint;
                    }
                    if (status.state() == AiStatus.State.UNAVAILABLE) {
                        throw new UnavailableException(AiMessages.CANNOT_RUN, "AI runtime unavailable: " + status.detail());
                    }
                    if (slot.state == SlotState.FAILED) {
                        throw new UnavailableException(UserMessage.ASSISTANT_UNAVAILABLE,
                                "The " + role.label() + " server failed: " + slot.failure);
                    }
                    long left = deadline - System.nanoTime();
                    if (left <= 0) {
                        throw new UnavailableException(UserMessage.ASSISTANT_STARTING,
                                "The " + role.label() + " server was not ready within " + options.readyWait());
                    }
                    lock.wait(Math.max(1, Math.min(TimeUnit.NANOSECONDS.toMillis(left), 500)));
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Interrupted while waiting for the " + role.label() + " server");
        } finally {
            wake.close();
        }
    }

    @Override
    public void reportUnresponsive(ServerEndpoint endpoint) {
        Objects.requireNonNull(endpoint, "endpoint");
        try {
            workers.execute(() -> checkResponsive(endpoint));
        } catch (RejectedExecutionException e) {
            // Closed.
        }
    }

    // ---------------------------------------------------------------- queries

    /** The endpoint of a role while it is healthy (no waiting, no starting). */
    public Optional<ServerEndpoint> endpoint(ServerRole role) {
        synchronized (lock) {
            return Optional.ofNullable(slots.get(role).endpoint);
        }
    }

    /** The model files found at the last start (or construction). */
    public ModelCatalog catalog() {
        synchronized (lock) {
            return catalog;
        }
    }

    /** The log file of a role's process. */
    public Path logFile(ServerRole role) {
        return logsDirectory.resolve(role.logFileName());
    }

    /** Process ids of the running servers, for diagnostics and tests. */
    public Map<ServerRole, Long> processIds() {
        Map<ServerRole, Long> ids = new EnumMap<>(ServerRole.class);
        synchronized (lock) {
            for (Slot slot : slots.values()) {
                if (slot.process != null && slot.process.isAlive()) {
                    ids.put(slot.role, slot.process.pid());
                }
            }
        }
        return ids;
    }

    // ---------------------------------------------------------------- lifecycle internals

    private ModelCatalog rescan() {
        ModelCatalog fresh = ModelCatalog.scan(runtimeDirectory, hardware);
        synchronized (lock) {
            catalog = fresh;
        }
        return fresh;
    }

    private AiStatus idleStatus() {
        Optional<String> problem = catalog.problem(requested);
        return new AiStatus(AiStatus.State.UNAVAILABLE, null, problem.orElse("not started"));
    }

    private void startLocked(ModelChoice choice) {
        lifecycle = Lifecycle.RUNNING;
        requested = choice;
        catalog = ModelCatalog.scan(runtimeDirectory, hardware);
        ModelChoice model = catalog.resolve(choice);
        Optional<String> problem = catalog.problem(model);
        if (problem.isPresent()) {
            LOG.warn("The study assistant can't run: {}", problem.get());
            List<Process> stopping = new ArrayList<>();
            for (Slot slot : slots.values()) {
                Process process = stopSlotLocked(slot);
                if (process != null) {
                    stopping.add(process);
                }
            }
            setStatusLocked(new AiStatus(AiStatus.State.UNAVAILABLE, null, problem.get()));
            lock.notifyAll();
            if (!stopping.isEmpty()) {
                inBackground(() -> terminateAll(stopping));
            }
            return;
        }
        LOG.info("Starting the AI runtime with {} from {}", model.code(), catalog.runtimeDirectory().orElseThrow());
        for (Slot slot : slots.values()) {
            LaunchSpec wanted = specFor(slot.role, model);
            boolean reusable = slot.process != null && slot.process.isAlive() && wanted.equals(slot.spec)
                    && slot.state != SlotState.FAILED;
            slot.restarts = 0;
            if (reusable) {
                continue;
            }
            Process previous = stopSlotLocked(slot);
            slot.spec = wanted;
            launchLocked(slot, previous);
        }
        updateStatusLocked();
        lock.notifyAll();
    }

    private LaunchSpec specFor(ServerRole role, ModelChoice model) {
        if (role == ServerRole.CHAT) {
            Optional<Path> mlx = catalog.mlxChatModel(model);
            if (mlx.isPresent()) {
                return mlxSpec(mlx.get());
            }
        }
        Path executable = catalog.serverExecutable().orElseThrow();
        List<String> args = new ArrayList<>();
        boolean vision = false;
        switch (role) {
            case CHAT -> {
                ModelCatalog.ChatModel chat = catalog.chatModel(model).orElseThrow();
                args.addAll(List.of("-m", chat.model().toString()));
                if (chat.projector().isPresent()) {
                    args.addAll(List.of("--mmproj", chat.projector().get().toString()));
                    vision = true;
                }
                args.addAll(List.of("--jinja", "-c", String.valueOf(options.chatContext()),
                        "-ngl", String.valueOf(options.gpuLayers()), "--parallel", "1", "-fa", "on",
                        "--cache-ram", String.valueOf(options.chatCacheMib())));
            }
            case EMBED -> args.addAll(List.of("-m", catalog.embeddingModel().orElseThrow().toString(),
                    "--embedding", "--pooling", "last",
                    "-c", String.valueOf(options.embedContext()), "-ub", String.valueOf(options.embedContext()),
                    "-ngl", String.valueOf(options.gpuLayers())));
            case RERANK -> args.addAll(List.of("-m", catalog.rerankModel().orElseThrow().toString(),
                    "--reranking",
                    "-c", String.valueOf(options.rerankContext()), "-ub", String.valueOf(options.rerankContext()),
                    "-ngl", String.valueOf(options.gpuLayers())));
        }
        args.add("--no-webui");
        return new LaunchSpec(executable, List.copyOf(args), vision, null, Map.of());
    }

    /** The MLX chat server for the model in {@code folder}; it reads images when the folder has an image processor. */
    private LaunchSpec mlxSpec(Path folder) {
        boolean vision = Files.isRegularFile(folder.resolve("preprocessor_config.json"));
        List<String> args = List.of("-I", "-m", "mlx_vlm.server", "--model", folder.toString(),
                "--log-progress-interval", "0");
        return new LaunchSpec(catalog.mlxPython().orElseThrow(), args, vision, folder.toString(), MLX_ENVIRONMENT);
    }

    /** Detaches the slot's process (returned, for the caller to terminate) and marks the slot stopped. */
    private Process stopSlotLocked(Slot slot) {
        slot.launchId++;
        slot.endpoint = null;
        slot.state = SlotState.STOPPED;
        slot.spec = null;
        slot.failure = null;
        Process process = slot.process;
        slot.process = null;
        return process;
    }

    private void launchLocked(Slot slot, Process previous) {
        long id = ++slot.launchId;
        slot.state = SlotState.STARTING;
        slot.endpoint = null;
        slot.process = null;
        LaunchSpec spec = slot.spec;
        try {
            workers.execute(() -> runLaunch(slot, id, spec, previous));
        } catch (RejectedExecutionException e) {
            LOG.debug("Not launching the {} server: closing", slot.role.label());
            if (previous != null) {
                inBackground(() -> terminate(previous));
            }
        }
    }

    private void runLaunch(Slot slot, long id, LaunchSpec spec, Process previous) {
        if (previous != null) {
            terminate(previous);
        }
        ServerRole role = slot.role;
        int port;
        try {
            port = freePort();
        } catch (IOException e) {
            failed(slot, id, "no free port: " + e.getMessage());
            return;
        }
        String key = randomKey();
        List<String> arguments = new ArrayList<>(spec.arguments());
        arguments.addAll(List.of("--host", "127.0.0.1", "--port", String.valueOf(port), "--api-key", key));
        List<String> command = options.launchCommand().command(role, spec.executable(), arguments);
        Path log = logFile(role);
        Process process;
        synchronized (lock) {
            if (slot.launchId != id || lifecycle != Lifecycle.RUNNING) {
                return;
            }
        }
        try {
            prepareLog(log, command, key);
            ProcessBuilder builder = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .redirectInput(ProcessBuilder.Redirect.PIPE);
            builder.environment().putAll(spec.environment());
            Path workingDirectory = spec.executable().getParent();
            if (workingDirectory != null && Files.isDirectory(workingDirectory)) {
                builder.directory(workingDirectory.toFile());
            }
            process = builder.start();
            process.getOutputStream().close();
        } catch (IOException e) {
            failed(slot, id, "could not launch: " + e.getMessage());
            return;
        }
        liveProcesses.add(process);
        boolean wanted;
        synchronized (lock) {
            wanted = slot.launchId == id && lifecycle == Lifecycle.RUNNING;
            if (wanted) {
                slot.process = process;
                ensureShutdownHookLocked();
            }
        }
        if (!wanted) {
            // Stopped or closed while launching. End it on this thread: the worker pool may already be shut down,
            // and nothing else knows about this process.
            terminate(process);
            return;
        }
        LOG.info("Launched the {} server (pid {}) on port {}{}", role.label(), process.pid(), port,
                spec.model() == null ? "" : " with MLX");
        process.onExit().thenRun(() -> exited(slot, id, process));
        pollUntilHealthy(slot, id, process, new ServerEndpoint(role,
                URI.create("http://127.0.0.1:" + port + "/"), key, spec.vision(), spec.model()));
    }

    private void pollUntilHealthy(Slot slot, long id, Process process, ServerEndpoint endpoint) {
        long started = System.nanoTime();
        long deadline = started + options.startTimeout().toNanos();
        while (true) {
            synchronized (lock) {
                if (slot.launchId != id) {
                    return;
                }
            }
            if (!process.isAlive()) {
                return; // exited() reports it
            }
            if (isHealthy(endpoint)) {
                synchronized (lock) {
                    if (slot.launchId != id) {
                        return;
                    }
                    slot.endpoint = endpoint;
                    slot.state = SlotState.READY;
                    slot.healthySince = System.nanoTime();
                    updateStatusLocked();
                    lock.notifyAll();
                }
                LOG.info("The {} server is ready after {} ms", slot.role.label(),
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                return;
            }
            if (System.nanoTime() > deadline) {
                failed(slot, id, "not ready within " + options.startTimeout());
                return;
            }
            try {
                Thread.sleep(options.healthInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private boolean isHealthy(ServerEndpoint endpoint) {
        // The MLX server checks the key on /health too; llama-server ignores it there.
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve("/health"))
                .header("Authorization", "Bearer " + endpoint.apiKey())
                .timeout(Duration.ofSeconds(3))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = health.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void checkResponsive(ServerEndpoint endpoint) {
        if (isHealthy(endpoint)) {
            return;
        }
        Slot slot = slots.get(endpoint.role());
        long id;
        synchronized (lock) {
            if (!endpoint.equals(slot.endpoint)) {
                return;
            }
            id = slot.launchId;
        }
        failed(slot, id, "stopped answering");
    }

    private void exited(Slot slot, long id, Process process) {
        liveProcesses.remove(process);
        String reason = "exited with code " + safeExitValue(process);
        synchronized (lock) {
            if (slot.launchId != id || slot.process != process) {
                return;
            }
            if (slot.state == SlotState.READY
                    && System.nanoTime() - slot.healthySince >= options.stableAfter().toNanos()) {
                slot.restarts = 0;
            }
        }
        failed(slot, id, reason);
    }

    /** The launch {@code id} of {@code slot} failed: restart with back-off, or report failure. */
    private void failed(Slot slot, long id, String reason) {
        Process broken;
        synchronized (lock) {
            if (slot.launchId != id || lifecycle != Lifecycle.RUNNING) {
                return;
            }
            broken = slot.process;
            slot.process = null;
            slot.endpoint = null;
            if (slot.restarts < options.maxRestarts()) {
                slot.restarts++;
                long newId = ++slot.launchId;
                slot.state = SlotState.STARTING;
                Duration delay = options.restartBackoff().multipliedBy(1L << Math.min(slot.restarts - 1, 16));
                LOG.warn("The {} server {}; restarting in {} ms (attempt {} of {}). See {}", slot.role.label(), reason,
                        delay.toMillis(), slot.restarts, options.maxRestarts(), logFile(slot.role));
                try {
                    scheduler.schedule(() -> relaunch(slot, newId, broken), delay.toMillis(), TimeUnit.MILLISECONDS);
                } catch (RejectedExecutionException e) {
                    return;
                }
            } else {
                slot.launchId++;
                slot.state = SlotState.FAILED;
                slot.failure = reason;
                LOG.error("The {} server {} and was restarted {} times; giving up. See {}", slot.role.label(), reason,
                        options.maxRestarts(), logFile(slot.role));
                if (broken != null) {
                    inBackground(() -> terminate(broken));
                }
            }
            updateStatusLocked();
            lock.notifyAll();
        }
    }

    private void relaunch(Slot slot, long id, Process previous) {
        synchronized (lock) {
            if (slot.launchId != id || lifecycle != Lifecycle.RUNNING) {
                if (previous != null) {
                    inBackground(() -> terminate(previous));
                }
                return;
            }
            // launchLocked takes a new id; the scheduled id only guarded against a stop or start in between.
            launchLocked(slot, previous);
        }
    }

    private void updateStatusLocked() {
        if (lifecycle != Lifecycle.RUNNING) {
            return;
        }
        ModelChoice model = catalog.resolve(requested);
        List<String> failedRoles = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        for (Slot slot : slots.values()) {
            switch (slot.state) {
                case FAILED -> failedRoles.add(slot.role.label() + " " + slot.failure);
                case READY -> { }
                default -> waiting.add(slot.role.label());
            }
        }
        if (!failedRoles.isEmpty()) {
            setStatusLocked(new AiStatus(AiStatus.State.FAILED, model, String.join("; ", failedRoles)));
        } else if (waiting.isEmpty()) {
            setStatusLocked(new AiStatus(AiStatus.State.READY, model, null));
        } else {
            setStatusLocked(new AiStatus(AiStatus.State.STARTING, model, "waiting for " + String.join(", ", waiting)));
        }
    }

    private void setStatusLocked(AiStatus next) {
        AiStatus previous = status;
        status = next;
        if (previous.state() == next.state() && previous.model() == next.model()) {
            return;
        }
        LOG.info("Study assistant {}{}", next.state(), next.detail() == null ? "" : " (" + next.detail() + ")");
        try {
            notifier.execute(() -> {
                for (Consumer<AiStatus> listener : listeners) {
                    try {
                        listener.accept(next);
                    } catch (RuntimeException e) {
                        LOG.warn("A study assistant status listener failed", e);
                    }
                }
            });
        } catch (RejectedExecutionException e) {
            // Closed.
        }
    }

    private void ensureShutdownHookLocked() {
        if (shutdownHook != null) {
            return;
        }
        shutdownHook = Thread.ofPlatform().name("learnsphere-ai-shutdown").unstarted(() -> {
            for (Process process : liveProcesses) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            for (Process process : liveProcesses) {
                try {
                    long left = Math.max(0, deadline - System.nanoTime());
                    if (!process.waitFor(left, TimeUnit.NANOSECONDS)) {
                        process.descendants().forEach(ProcessHandle::destroyForcibly);
                        process.destroyForcibly();
                    }
                } catch (InterruptedException e) {
                    process.destroyForcibly();
                }
            }
        });
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    /**
     * Runs {@code work} (ending processes) on a worker, or on a thread of its own once the workers are shut down,
     * so a process is never left running because this manager is closing.
     */
    private void inBackground(Runnable work) {
        try {
            workers.execute(work);
        } catch (RejectedExecutionException e) {
            Thread.ofPlatform().name("learnsphere-ai-stop").start(work);
        }
    }

    /** Ends a process: politely, then forcibly after the stop timeout. Blocks until it is gone. */
    private void terminate(Process process) {
        if (process != null) {
            terminateAll(List.of(process));
        }
    }

    /** Ends processes together: politely, then forcibly after the stop timeout. Blocks until they are gone. */
    private void terminateAll(List<Process> processes) {
        for (Process process : processes) {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroy);
                process.destroy();
            }
        }
        long deadline = System.nanoTime() + options.stopTimeout().toNanos();
        try {
            for (Process process : processes) {
                long left = Math.max(0, deadline - System.nanoTime());
                if (!process.waitFor(left, TimeUnit.NANOSECONDS)) {
                    LOG.warn("A server (pid {}) did not stop within {}; killing it", process.pid(), options.stopTimeout());
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    process.waitFor(options.stopTimeout().toMillis(), TimeUnit.MILLISECONDS);
                }
            }
        } catch (InterruptedException e) {
            processes.forEach(Process::destroyForcibly);
            Thread.currentThread().interrupt();
        } finally {
            for (Process process : processes) {
                if (!process.isAlive()) {
                    liveProcesses.remove(process);
                }
            }
        }
    }

    private static String safeExitValue(Process process) {
        try {
            return String.valueOf(process.exitValue());
        } catch (IllegalThreadStateException e) {
            return "?";
        }
    }

    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    static String randomKey() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void prepareLog(Path log, List<String> command, String key) throws IOException {
        Files.createDirectories(log.toAbsolutePath().getParent());
        if (Files.isRegularFile(log) && Files.size(log) > LOG_ROLL_BYTES) {
            Path rolled = log.resolveSibling(log.getFileName().toString().replace(".log", ".1.log"));
            Files.move(log, rolled, StandardCopyOption.REPLACE_EXISTING);
        }
        String shown = String.join(" ", command).replace(key, "<key>");
        Files.writeString(log, System.lineSeparator() + "=== " + Instant.now() + " launching: " + shown
                        + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
