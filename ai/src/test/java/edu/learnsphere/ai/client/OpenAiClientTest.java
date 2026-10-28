package edu.learnsphere.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import edu.learnsphere.ai.AiMessages;
import edu.learnsphere.ai.client.FakeOpenAiServer.Reply;
import edu.learnsphere.ai.prompt.JsonSchemas;
import edu.learnsphere.ai.runtime.ServerEndpoint;
import edu.learnsphere.ai.runtime.ServerRole;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class OpenAiClientTest {

    private FakeOpenAiServer server;
    private OpenAiClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeOpenAiServer();
        client = new OpenAiClient(Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    private static ChatRequest hello() {
        return ChatRequest.text(List.of(ChatMessage.system("Be brief."), ChatMessage.user("Hi")), 50, 0.2);
    }

    @Test
    void streamsPiecesInOrderAndReportsUsage() {
        server.reply(Reply.text("Pro", "cesses ", "run [1]."));
        List<String> pieces = new ArrayList<>();
        ChatResult result = client.chat(server.endpoint(ServerRole.CHAT), hello(), pieces::add, CancellationToken.NONE);

        assertThat(pieces).containsExactly("Pro", "cesses ", "run [1].");
        assertThat(result.text()).isEqualTo("Processes run [1].");
        assertThat(result.finishReason()).isEqualTo("stop");
        assertThat(result.truncated()).isFalse();
        assertThat(result.promptTokens()).isEqualTo(12);
        assertThat(result.completionTokens()).isEqualTo(3);
        assertThat(result.elapsed()).isGreaterThanOrEqualTo(result.firstToken());
    }

    @Test
    void sendsStreamingNoThinkingAndCachedPromptRequests() {
        client.chat(server.endpoint(ServerRole.CHAT), hello(), TokenSink.NONE, CancellationToken.NONE);
        JsonNode body = server.lastChatRequest();

        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(body.path("cache_prompt").asBoolean()).isTrue();
        assertThat(body.path("chat_template_kwargs").path("enable_thinking").asBoolean(true)).isFalse();
        assertThat(body.path("max_tokens").asInt()).isEqualTo(50);
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(body.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(body.path("messages").get(1).path("content").asText()).isEqualTo("Hi");
        assertThat(body.has("response_format")).isFalse();
        assertThat(body.has("model")).isFalse();
    }

    @Test
    void namesTheModelWhenTheServerNeedsIt() {
        ServerEndpoint plain = server.endpoint(ServerRole.CHAT);
        ServerEndpoint named = new ServerEndpoint(ServerRole.CHAT, plain.baseUri(), plain.apiKey(), true, "/models/chat-4b-mlx");
        server.reply(Reply.text("Hello"));
        client.chat(named, hello(), TokenSink.NONE, CancellationToken.NONE);
        assertThat(server.lastChatRequest().path("model").asText()).isEqualTo("/models/chat-4b-mlx");
    }

    @Test
    void sendsImagesAsDataUrlsAndSchemasAsStrictJsonSchema() {
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        ChatRequest request = ChatRequest.json(List.of(ChatMessage.userWithImage(ChatMessage.Image.png(png), "Read this")),
                100, 0, "page", JsonSchemas.object(Map.of("text", JsonSchemas.string())));
        server.reply(Reply.json("{\"text\":\"ok\"}"));
        client.chat(server.endpoint(ServerRole.CHAT), request, TokenSink.NONE, CancellationToken.NONE);
        JsonNode body = server.lastChatRequest();

        JsonNode content = body.path("messages").get(0).path("content");
        assertThat(content.get(0).path("type").asText()).isEqualTo("image_url");
        assertThat(content.get(0).path("image_url").path("url").asText())
                .isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(png));
        assertThat(content.get(1).path("text").asText()).isEqualTo("Read this");
        JsonNode format = body.path("response_format");
        assertThat(format.path("type").asText()).isEqualTo("json_schema");
        assertThat(format.path("json_schema").path("name").asText()).isEqualTo("page");
        assertThat(format.path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(format.path("json_schema").path("schema").path("properties").has("text")).isTrue();
    }

    @Test
    void reportsATruncatedReply() {
        server.reply(Reply.truncated("cut"));
        ChatResult result = client.chat(server.endpoint(ServerRole.CHAT), hello(), TokenSink.NONE, CancellationToken.NONE);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void aWrongKeyMeansTheAssistantIsStillStarting() {
        ServerEndpoint wrong = server.endpointWithKey(ServerRole.CHAT, "stale-key");
        assertThatThrownBy(() -> client.chat(wrong, hello(), TokenSink.NONE, CancellationToken.NONE))
                .isInstanceOf(UnavailableException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(UserMessage.ASSISTANT_STARTING);
    }

    @Test
    void aPromptOverTheContextIsTooLongAndTheServerTextNeverReachesTheUser() {
        server.reply(Reply.error(400, "the request exceeds the available context size"));
        assertThatThrownBy(() -> client.chat(server.endpoint(ServerRole.CHAT), hello(), TokenSink.NONE, CancellationToken.NONE))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> {
                    UserMessage message = ((LearnSphereException) e).userMessage();
                    assertThat(message).isEqualTo(AiMessages.TOO_LONG);
                    assertThat(message.text()).doesNotContainIgnoringCase("context");
                });
    }

    @Test
    void otherServerErrorsBecomeAShortRetryMessage() {
        server.reply(Reply.error(500, "llama_decode failed"));
        assertThatThrownBy(() -> client.chat(server.endpoint(ServerRole.CHAT), hello(), TokenSink.NONE, CancellationToken.NONE))
                .isInstanceOf(LearnSphereException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(AiMessages.REQUEST_FAILED);
    }

    @Test
    void aStreamThatEndsWithoutFinishingFails() {
        server.reply(Reply.text("half").unfinished());
        assertThatThrownBy(() -> client.chat(server.endpoint(ServerRole.CHAT), hello(), TokenSink.NONE, CancellationToken.NONE))
                .isInstanceOf(LearnSphereException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(AiMessages.REQUEST_FAILED);
    }

    @Test
    void aClosedPortIsUnreachable() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        ServerEndpoint nobody = new ServerEndpoint(ServerRole.CHAT, URI.create("http://127.0.0.1:" + port + "/"), "k", false);
        assertThatThrownBy(() -> client.chat(nobody, hello(), TokenSink.NONE, CancellationToken.NONE))
                .isInstanceOf(ServerUnreachableException.class);
        assertThatThrownBy(() -> client.embed(nobody, List.of("x"), Duration.ofSeconds(2), CancellationToken.NONE))
                .isInstanceOf(ServerUnreachableException.class);
    }

    @Test
    void cancellingMidStreamAbortsTheRequestPromptly() throws Exception {
        List<String> many = new ArrayList<>(Collections.nCopies(200, "word "));
        server.reply(new Reply(200, null, many, "stop", 0, 50));
        CancellationToken token = CancellationToken.create();
        CountDownLatch twoPieces = new CountDownLatch(2);
        List<String> received = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<ChatResult> running = CompletableFuture.supplyAsync(() ->
                client.chat(server.endpoint(ServerRole.CHAT), hello(), piece -> {
                    received.add(piece);
                    twoPieces.countDown();
                }, token));
        assertThat(twoPieces.await(10, TimeUnit.SECONDS)).isTrue();
        long cancelledAt = System.nanoTime();
        token.cancel();

        assertThatThrownBy(() -> running.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(CancellationException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - cancelledAt)).isLessThan(Duration.ofSeconds(2));
        assertThat(received.size()).isLessThan(200);
        // The connection is closed, so the server stops writing (and generating).
        assertThat(server.disconnected().await(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void anAlreadyCancelledTokenSendsNothing() {
        CancellationToken token = CancellationToken.create();
        token.cancel();
        assertThatThrownBy(() -> client.chat(server.endpoint(ServerRole.CHAT), hello(), TokenSink.NONE, token))
                .isInstanceOf(CancellationException.class);
        assertThat(server.chatRequests()).isEmpty();
    }

    @Test
    void noFirstTokenInTimeIsATimeout() {
        server.reply(Reply.text("late").slow(3_000, 0));
        ChatRequest quick = hello().withTimeouts(Duration.ofMillis(400), Duration.ofSeconds(5), Duration.ofSeconds(10));
        assertThatThrownBy(() -> client.chat(server.endpoint(ServerRole.CHAT), quick, TokenSink.NONE, CancellationToken.NONE))
                .isInstanceOf(AiTimeoutException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(AiMessages.TIMED_OUT);
    }

    @Test
    void aLongSilenceMidStreamIsATimeout() {
        server.reply(new Reply(200, null, List.of("a", "b", "c"), "stop", 0, 1_500));
        ChatRequest quick = hello().withTimeouts(Duration.ofSeconds(5), Duration.ofMillis(500), Duration.ofSeconds(20));
        // The first piece arrives after 1.5 s (within the first-token wait), the second 1.5 s later: too slow.
        assertThatThrownBy(() -> client.chat(server.endpoint(ServerRole.CHAT), quick, TokenSink.NONE, CancellationToken.NONE))
                .isInstanceOf(AiTimeoutException.class);
    }

    @Test
    void embedsInInputOrder() {
        List<float[]> vectors = client.embed(server.endpoint(ServerRole.EMBED), List.of("a", "bbb"), Duration.ofSeconds(5),
                CancellationToken.NONE);
        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)[0]).isEqualTo(2f);
        assertThat(vectors.get(1)[0]).isEqualTo(4f);
        assertThat(server.embedRequests().getFirst().path("encoding_format").asText()).isEqualTo("float");
    }

    @Test
    void reranksInDocumentOrder() {
        double[] scores = client.rerank(server.endpoint(ServerRole.RERANK), "paging basics",
                List.of("Locks and mutexes", "Paging splits memory", "Deadlock"), Duration.ofSeconds(5),
                CancellationToken.NONE);
        assertThat(scores).containsExactly(-4.0, 4.0, -4.0);
        assertThat(server.rerankRequests().getFirst().path("top_n").asInt()).isEqualTo(3);
    }

    @Test
    void parsesServerSentEventLines() {
        assertThat(OpenAiClient.ssePayload("data: {\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(OpenAiClient.ssePayload("data: [DONE]")).isEqualTo("[DONE]");
        assertThat(OpenAiClient.ssePayload(": keep-alive")).isNull();
        assertThat(OpenAiClient.ssePayload("")).isNull();
        assertThat(OpenAiClient.ssePayload("error: {\"code\":500,\"message\":\"x\"}"))
                .isEqualTo("{\"error\":{\"code\":500,\"message\":\"x\"}}");
    }
}
