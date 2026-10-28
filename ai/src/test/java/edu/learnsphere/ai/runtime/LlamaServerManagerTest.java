package edu.learnsphere.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.ai.AiMessages;
import edu.learnsphere.ai.LocalAi;
import edu.learnsphere.ai.client.ChatGate;
import edu.learnsphere.ai.client.ChatMessage;
import edu.learnsphere.ai.client.ChatRequest;
import edu.learnsphere.ai.client.LocalChat;
import edu.learnsphere.ai.client.OpenAiClient;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.spi.AiCapabilities;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The server manager against stand-in servers ({@link FakeLlamaServer}) run as real child processes: start,
 * health polling, logs, stop, crash restarts, the restart budget, lazy start and close.
 */
@Timeout(120)
class LlamaServerManagerTest {

    private static final Duration WAIT = Duration.ofSeconds(60);
    /**
     * Start timeout for tests whose server never answers, so it is meant to run out. Short, but long enough for the
     * test to see the server's process before the manager gives up on it.
     */
    private static final Duration GIVE_UP = Duration.ofMillis(1_500);

    @TempDir
    Path temp;

    private Path runtime;
    private Path logs;
    private final Map<ServerRole, String> modes = new ConcurrentHashMap<>();
    private final Map<ServerRole, AtomicInteger> launches = new EnumMap<>(ServerRole.class);
    private final Set<Long> seenPids = ConcurrentHashMap.newKeySet();
    private final List<AutoCloseable> toClose = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        runtime = TestRuntime.create(temp, 8);
        logs = temp.resolve("logs");
        for (ServerRole role : ServerRole.values()) {
            launches.put(role, new AtomicInteger());
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable closeable : toClose) {
            closeable.close();
        }
        // Belt and braces: never leave a stand-in server running.
        for (long pid : seenPids) {
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroyForcibly);
        }
    }

    private ServerOptions options(int restarts, Duration startTimeout) {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes;
        try {
            classes = Path.of(FakeLlamaServer.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        LaunchCommand command = (role, executable, arguments) -> {
            launches.get(role).incrementAndGet();
            List<String> line = new ArrayList<>(List.of(java, "-Xmx32m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
                    "-cp", classes, FakeLlamaServer.class.getName(), modes.getOrDefault(role, "ok")));
            line.addAll(arguments);
            return line;
        };
        return ServerOptions.defaults()
                .withLaunchCommand(command)
                .withTimings(startTimeout, Duration.ofMillis(50), Duration.ofMillis(50), WAIT, Duration.ofSeconds(3))
                .withRestarts(restarts, Duration.ofMinutes(5));
    }

    private LlamaServerManager manager(Optional<Path> runtimeDirectory, ServerOptions options) {
        LlamaServerManager manager = new LlamaServerManager(runtimeDirectory, TestRuntime.MACHINE_16GB, logs, options);
        toClose.add(manager);
        return manager;
    }

    private LlamaServerManager manager() {
        return manager(Optional.of(runtime), options(3, Duration.ofSeconds(40)));
    }

    private static void waitUntil(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting until " + what);
            }
            Thread.sleep(20);
        }
    }

    private Map<ServerRole, Long> pids(LlamaServerManager manager) {
        Map<ServerRole, Long> pids = manager.processIds();
        seenPids.addAll(pids.values());
        return pids;
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    private static boolean allDead(Collection<Long> pids) {
        return pids.stream().noneMatch(LlamaServerManagerTest::alive);
    }

    private static ChatRequest hello() {
        return ChatRequest.text(List.of(ChatMessage.user("hi")), 10, 0);
    }

    // ------------------------------------------------------------------ tests

    @Test
    void startsThreeServersOnLoopbackWithKeysAndLogsThenStopsThem() throws Exception {
        LlamaServerManager manager = manager();
        List<AiStatus.State> states = Collections.synchronizedList(new ArrayList<>());
        manager.addListener(status -> states.add(status.state()));
        assertThat(manager.status().state()).isEqualTo(AiStatus.State.UNAVAILABLE);
        assertThat(manager.processIds()).isEmpty();

        manager.start(ModelChoice.AUTO);
        waitUntil("ready", () -> manager.status().state() == AiStatus.State.READY);

        assertThat(manager.status().model()).isEqualTo(ModelChoice.CHAT_4B);
        Map<ServerRole, Long> pids = pids(manager);
        assertThat(pids).containsOnlyKeys(ServerRole.values());
        Set<Integer> ports = new HashSet<>();
        Set<String> keys = new HashSet<>();
        for (ServerRole role : ServerRole.values()) {
            ServerEndpoint endpoint = manager.endpoint(role).orElseThrow();
            assertThat(endpoint.baseUri().getHost()).isEqualTo("127.0.0.1");
            ports.add(endpoint.baseUri().getPort());
            keys.add(endpoint.apiKey());
            assertThat(endpoint.apiKey()).hasSizeGreaterThanOrEqualTo(24);
            assertThat(endpoint.toString()).doesNotContain(endpoint.apiKey());

            String log = Files.readString(manager.logFile(role));
            assertThat(log).contains("launching:", "--api-key <key>", "--host 127.0.0.1", "fake llama-server listening")
                    .doesNotContain(endpoint.apiKey());
        }
        assertThat(ports).hasSize(3);
        assertThat(keys).hasSize(3);
        assertThat(manager.endpoint(ServerRole.CHAT).orElseThrow().vision()).isTrue();
        assertThat(Files.readString(manager.logFile(ServerRole.CHAT))).contains("--mmproj", "--jinja", "-c 16384");
        assertThat(Files.readString(manager.logFile(ServerRole.EMBED))).contains("--embedding --pooling last");
        assertThat(Files.readString(manager.logFile(ServerRole.RERANK))).contains("--reranking");

        // Requests reach the chat server with its key.
        try (OpenAiClient client = new OpenAiClient()) {
            String reply = new LocalChat(manager, client, new ChatGate())
                    .complete(ChatPriority.INTERACTIVE, hello(), TokenSink.NONE, CancellationToken.NONE).text();
            assertThat(reply).isEqualTo("Hi from " + manager.endpoint(ServerRole.CHAT).orElseThrow().baseUri().getPort());
        }

        manager.stop();

        assertThat(allDead(pids.values())).isTrue();
        assertThat(manager.processIds()).isEmpty();
        assertThat(manager.status().state()).isEqualTo(AiStatus.State.UNAVAILABLE);
        waitUntil("listeners told", () -> states.contains(AiStatus.State.UNAVAILABLE));
        assertThat(states).containsSubsequence(AiStatus.State.STARTING, AiStatus.State.READY, AiStatus.State.UNAVAILABLE);
    }

    @Test
    void onAppleSiliconTheChatServerRunsOnMlxWhenItsFilesArePresent() throws Exception {
        TestRuntime.mlxPython(runtime);
        Path folder = TestRuntime.mlxChat(runtime, "chat-4b").toAbsolutePath().normalize();
        Map<ServerRole, Path> executables = new ConcurrentHashMap<>();
        ServerOptions base = options(3, Duration.ofSeconds(40));
        LlamaServerManager manager = manager(Optional.of(runtime), base.withLaunchCommand((role, executable, arguments) -> {
            executables.put(role, executable);
            return base.launchCommand().command(role, executable, arguments);
        }));

        manager.start(ModelChoice.AUTO);
        waitUntil("ready", () -> manager.status().state() == AiStatus.State.READY);

        assertThat(executables.get(ServerRole.CHAT).toString()).endsWith("mlx/venv/bin/python");
        assertThat(executables.get(ServerRole.EMBED).getFileName().toString()).isEqualTo("llama-server");
        ServerEndpoint chat = manager.endpoint(ServerRole.CHAT).orElseThrow();
        assertThat(chat.model()).isEqualTo(folder.toString());
        assertThat(chat.vision()).isTrue();
        assertThat(manager.endpoint(ServerRole.EMBED).orElseThrow().model()).isNull();
        String log = Files.readString(manager.logFile(ServerRole.CHAT));
        assertThat(log).contains("-m mlx_vlm.server --model " + folder, "--api-key <key>", "HF_HUB_OFFLINE=1")
                .doesNotContain("--mmproj", "--no-webui");

        try (OpenAiClient client = new OpenAiClient()) {
            String reply = new LocalChat(manager, client, new ChatGate())
                    .complete(ChatPriority.INTERACTIVE, hello(), TokenSink.NONE, CancellationToken.NONE).text();
            assertThat(reply).isEqualTo("Hi from " + chat.baseUri().getPort());
        }
    }

    @Test
    void aRequestStartsAnIdleRuntime() {
        LlamaServerManager manager = manager();
        assertThat(launches.values()).allMatch(count -> count.get() == 0);

        ServerEndpoint embed = manager.await(ServerRole.EMBED, CancellationToken.NONE);

        assertThat(embed.role()).isEqualTo(ServerRole.EMBED);
        assertThat(pids(manager)).containsKey(ServerRole.EMBED);
        assertThat(launches.values()).allMatch(count -> count.get() == 1);
    }

    @Test
    void restartsAServerThatDies() throws Exception {
        LlamaServerManager manager = manager();
        List<AiStatus.State> states = Collections.synchronizedList(new ArrayList<>());
        manager.addListener(status -> states.add(status.state()));
        manager.start(ModelChoice.AUTO);
        waitUntil("ready", () -> manager.status().state() == AiStatus.State.READY);
        long first = pids(manager).get(ServerRole.CHAT);
        ServerEndpoint before = manager.endpoint(ServerRole.CHAT).orElseThrow();

        ProcessHandle.of(first).orElseThrow().destroyForcibly(); // our own child

        waitUntil("chat restarted", () -> {
            Long now = pids(manager).get(ServerRole.CHAT);
            return now != null && now != first && manager.status().state() == AiStatus.State.READY;
        });
        assertThat(launches.get(ServerRole.CHAT)).hasValue(2);
        assertThat(launches.get(ServerRole.EMBED)).hasValue(1);
        assertThat(manager.endpoint(ServerRole.CHAT).orElseThrow()).isNotEqualTo(before);
        waitUntil("listeners told", () -> states.lastIndexOf(AiStatus.State.READY) > states.indexOf(AiStatus.State.READY));
        assertThat(states).containsSubsequence(AiStatus.State.READY, AiStatus.State.STARTING, AiStatus.State.READY);
    }

    @Test
    void reportsFailureAfterTheRestartBudgetAndRecoversOnTheNextStart() throws Exception {
        modes.put(ServerRole.CHAT, "crash");
        LlamaServerManager manager = manager();
        manager.start(ModelChoice.AUTO);

        waitUntil("failed", () -> manager.status().state() == AiStatus.State.FAILED);

        assertThat(launches.get(ServerRole.CHAT)).hasValue(1 + 3);
        assertThat(manager.status().detail()).contains("chat");
        assertThatThrownBy(() -> manager.await(ServerRole.CHAT, CancellationToken.NONE))
                .isInstanceOf(UnavailableException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(UserMessage.ASSISTANT_UNAVAILABLE);
        assertThat(manager.await(ServerRole.EMBED, CancellationToken.NONE).role()).isEqualTo(ServerRole.EMBED);

        modes.put(ServerRole.CHAT, "ok");
        manager.start(ModelChoice.AUTO);
        waitUntil("ready again", () -> manager.status().state() == AiStatus.State.READY);
        // Only the failed server was launched again.
        assertThat(launches.get(ServerRole.EMBED)).hasValue(1);
        pids(manager);
    }

    @Test
    void aServerThatNeverAnswersIsGivenUpOnAndEnded() throws Exception {
        modes.put(ServerRole.RERANK, "hang");
        LlamaServerManager manager = manager(Optional.of(runtime), options(0, GIVE_UP));
        manager.start(ModelChoice.AUTO);
        waitUntil("rerank launched", () -> pids(manager).containsKey(ServerRole.RERANK));
        long hung = manager.processIds().get(ServerRole.RERANK);

        waitUntil("failed", () -> manager.status().state() == AiStatus.State.FAILED);

        assertThat(manager.status().detail()).contains("not ready within");
        waitUntil("hung server ended", () -> !alive(hung));
    }

    @Test
    void waitingCanBeCancelled() throws Exception {
        modes.put(ServerRole.CHAT, "hang");
        LlamaServerManager manager = manager();
        CancellationToken token = CancellationToken.create();
        CompletableFuture<ServerEndpoint> waiting = CompletableFuture.supplyAsync(
                () -> manager.await(ServerRole.CHAT, token));
        waitUntil("chat launched", () -> pids(manager).containsKey(ServerRole.CHAT));

        token.cancel();

        assertThatThrownBy(() -> waiting.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
    }

    @Test
    void startingWithTheSameModelKeepsTheRunningServers() throws Exception {
        LlamaServerManager manager = manager();
        manager.start(ModelChoice.AUTO);
        waitUntil("ready", () -> manager.status().state() == AiStatus.State.READY);
        Map<ServerRole, Long> before = pids(manager);

        manager.start(ModelChoice.CHAT_4B);

        assertThat(manager.status().state()).isEqualTo(AiStatus.State.READY);
        assertThat(pids(manager)).isEqualTo(before);
        assertThat(launches.values()).allMatch(count -> count.get() == 1);
    }

    @Test
    void withoutARuntimeFolderNothingStartsAndRequestsSaySo() {
        LlamaServerManager manager = manager(Optional.empty(), options(3, Duration.ofSeconds(10)));
        manager.start(ModelChoice.AUTO);

        assertThat(manager.status().state()).isEqualTo(AiStatus.State.UNAVAILABLE);
        assertThat(manager.status().detail()).contains("no runtime folder");
        assertThat(manager.availableModels()).isEmpty();
        assertThatThrownBy(() -> manager.await(ServerRole.CHAT, CancellationToken.NONE))
                .isInstanceOf(UnavailableException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(AiMessages.CANNOT_RUN);
        assertThat(launches.values()).allMatch(count -> count.get() == 0);
    }

    @Test
    void closeEndsEveryServerForGood() throws Exception {
        LlamaServerManager manager = manager();
        manager.start(ModelChoice.AUTO);
        waitUntil("ready", () -> manager.status().state() == AiStatus.State.READY);
        Map<ServerRole, Long> pids = pids(manager);

        manager.close();

        assertThat(allDead(pids.values())).isTrue();
        manager.start(ModelChoice.AUTO);
        assertThat(manager.processIds()).isEmpty();
        assertThatThrownBy(() -> manager.await(ServerRole.CHAT, CancellationToken.NONE))
                .isInstanceOf(UnavailableException.class);
    }

    @Test
    void closeEndsAServerThatIsWaitingToBeRestarted() throws Exception {
        modes.put(ServerRole.RERANK, "hang");
        ServerOptions slowRestart = options(1, GIVE_UP)
                .withTimings(GIVE_UP, Duration.ofMillis(50), Duration.ofMinutes(10), WAIT, Duration.ofSeconds(3));
        LlamaServerManager manager = manager(Optional.of(runtime), slowRestart);
        manager.start(ModelChoice.AUTO);
        waitUntil("rerank launched", () -> pids(manager).containsKey(ServerRole.RERANK));
        long hung = manager.processIds().get(ServerRole.RERANK);
        // It never answers, so the manager gives up on it and schedules a restart far in the future.
        waitUntil("rerank given up on", () -> !manager.processIds().containsKey(ServerRole.RERANK));
        assertThat(alive(hung)).isTrue();

        manager.close();

        assertThat(alive(hung)).isFalse();
    }

    @Test
    void theEntryPointStartsNothingAndSizesEmbeddingsFromTheModel() {
        LocalAi ai = LocalAi.create(Optional.of(runtime), TestRuntime.MACHINE_16GB, logs, options(3, Duration.ofSeconds(10)));
        toClose.add(ai);
        AiCapabilities capabilities = ai.capabilities();

        assertThat(capabilities.runtime()).isSameAs(ai.runtime());
        assertThat(capabilities.embedder().dimensions()).isEqualTo(8);
        assertThat(capabilities.embedder().modelName()).isEqualTo("Embed-0.6B-Q8_0");
        assertThat(capabilities.runtime().availableModels()).containsExactly(ModelChoice.CHAT_4B);
        assertThat(capabilities.runtime().recommendedModel()).isEqualTo(ModelChoice.CHAT_4B);
        assertThat(capabilities.runtime().status().state()).isEqualTo(AiStatus.State.UNAVAILABLE);
        assertThat(ai.runtime().processIds()).isEmpty();
        assertThat(launches.values()).allMatch(count -> count.get() == 0);
    }
}
