package edu.learnsphere.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.ai.client.ChatMessage;
import edu.learnsphere.ai.client.ChatRequest;
import edu.learnsphere.ai.client.ChatResult;
import edu.learnsphere.ai.runtime.LlamaServerManager;
import edu.learnsphere.ai.runtime.ServerRole;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The real llama-server processes: three healthy servers with logs, cancelling a generation mid-stream,
 * restarting a server that was killed, and stopping and lazily starting again. Results go to
 * {@code build/reports/ai-eval/runtime.md}.
 */
@ExtendWith(RealRuntime.Resolver.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ServerLifecycleIT {

    private static final EvalReport REPORT = new EvalReport("runtime", "Server processes");

    @AfterAll
    static void write() {
        REPORT.write();
    }

    private static void waitUntil(String what, long seconds, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Timed out waiting until " + what);
            }
            Thread.sleep(100);
        }
    }

    private static boolean alive(long pid) {
        return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
    }

    @Test
    @Order(1)
    void threeHealthyServersWithLogs(LocalAi ai) throws IOException {
        LlamaServerManager runtime = ai.runtime();
        Map<ServerRole, Long> pids = runtime.processIds();

        assertThat(runtime.status().state()).isEqualTo(AiStatus.State.READY);
        assertThat(pids).containsOnlyKeys(ServerRole.values());
        for (ServerRole role : ServerRole.values()) {
            assertThat(runtime.endpoint(role)).hasValueSatisfying(e -> assertThat(e.baseUri().getHost()).isEqualTo("127.0.0.1"));
            String log = Files.readString(runtime.logFile(role));
            assertThat(log).contains("launching:", "--api-key <key>").doesNotContain(runtime.endpoint(role).orElseThrow().apiKey());
            // The server's own output lands in the log too.
            assertThat(log).contains("llama");
        }
        REPORT.line("## Running servers").line("")
                .line("| Role | pid | Address | Log |").line("|---|---|---|---|");
        for (ServerRole role : ServerRole.values()) {
            REPORT.line("| %s | %d | %s | %s |", role.label(), pids.get(role), runtime.endpoint(role).orElseThrow().baseUri(),
                    runtime.logFile(role).getFileName());
        }
        REPORT.line("");
    }

    @Test
    @Order(2)
    void cancellingAGenerationStopsItAndTheNextRequestIsServedPromptly(LocalAi ai) throws Exception {
        ChatRequest essay = ChatRequest.text(List.of(ChatMessage.user(
                "Write a detailed 600-word essay about how operating systems manage memory.")), 1200, 0.7);
        CancellationToken token = CancellationToken.create();
        CountDownLatch streaming = new CountDownLatch(5);
        AtomicInteger pieces = new AtomicInteger();
        CompletableFuture<ChatResult> running = CompletableFuture.supplyAsync(() ->
                ai.chat().complete(ChatPriority.USER_WAITING, essay, piece -> {
                    pieces.incrementAndGet();
                    streaming.countDown();
                }, token));
        assertThat(streaming.await(3, TimeUnit.MINUTES)).as("streaming started").isTrue();

        long cancelled = System.nanoTime();
        token.cancel();
        assertThatThrownBy(() -> running.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
        double abortMillis = (System.nanoTime() - cancelled) / 1e6;
        int streamedPieces = pieces.get();

        long next = System.nanoTime();
        ChatResult reply = ai.chat().complete(ChatPriority.INTERACTIVE,
                ChatRequest.text(List.of(ChatMessage.user("Reply with the single word: ready")), 5, 0),
                TokenSink.NONE, CancellationToken.NONE);
        double nextMillis = (System.nanoTime() - next) / 1e6;

        REPORT.line("## Cancelling a generation").line("")
                .line("| Measure | Result |").line("|---|---|")
                .line("| Pieces streamed before cancelling | %d (of up to 1200 tokens) |", streamedPieces)
                .line("| Cancel to the call returning | %s |", EvalReport.ms(abortMillis))
                .line("| Next request, whole reply | %s (first token %s) |", EvalReport.ms(nextMillis),
                        EvalReport.ms(reply.firstToken().toMillis()))
                .line("");

        assertThat(abortMillis).as("cancel latency (ms)").isLessThan(2_000);
        assertThat(reply.text().toLowerCase()).contains("ready");
        // Had the essay kept generating, the next request would have waited for all of it.
        assertThat(nextMillis).as("next request (ms)").isLessThan(60_000);
    }

    @Test
    @Order(3)
    void aKilledServerIsRestartedAndServesAgain(LocalAi ai) throws Exception {
        LlamaServerManager runtime = ai.runtime();
        long before = runtime.processIds().get(ServerRole.RERANK);
        long killedAt = System.nanoTime();
        ProcessHandle.of(before).orElseThrow().destroyForcibly(); // a child this test run started

        waitUntil("rerank restarted", 180, () -> {
            Long now = runtime.processIds().get(ServerRole.RERANK);
            return now != null && now != before && runtime.status().isReady();
        });
        double restartMillis = (System.nanoTime() - killedAt) / 1e6;
        double[] scores = ai.capabilities().reranker().score("What is paging?",
                List.of("Paging divides memory into frames.", "Deadlock needs four conditions."), CancellationToken.NONE);

        REPORT.line("## Restart after a crash").line("")
                .line("The reranking server (pid %d) was killed; it was running again and healthy after %s.", before,
                        EvalReport.ms(restartMillis))
                .line("");
        assertThat(scores[0]).isGreaterThan(scores[1]);
    }

    @Test
    @Order(4)
    void stopEndsEveryProcessAndARequestStartsThemAgain(LocalAi ai) throws Exception {
        LlamaServerManager runtime = ai.runtime();
        Map<ServerRole, Long> pids = runtime.processIds();
        long stopping = System.nanoTime();
        runtime.stop();
        double stopMillis = (System.nanoTime() - stopping) / 1e6;

        assertThat(pids.values()).noneMatch(ServerLifecycleIT::alive);
        assertThat(runtime.processIds()).isEmpty();
        assertThat(runtime.status().state()).isEqualTo(AiStatus.State.UNAVAILABLE);

        long starting = System.nanoTime();
        float[] vector = ai.capabilities().embedder().embed("What is a page fault?", EmbedKind.QUERY);
        double lazyMillis = (System.nanoTime() - starting) / 1e6;

        assertThat(vector).hasSize(ai.capabilities().embedder().dimensions());
        assertThat(runtime.processIds()).containsKey(ServerRole.EMBED);
        waitUntil("ready again", 240, () -> runtime.status().isReady());
        REPORT.line("## Stop and lazy start").line("")
                .line("| Measure | Result |").line("|---|---|")
                .line("| Stopping all three servers | %s |", EvalReport.ms(stopMillis))
                .line("| First embedding request after stopping (starts the servers) | %s |", EvalReport.ms(lazyMillis))
                .line("");
    }
}
