package edu.learnsphere.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.ai.AiMessages;
import edu.learnsphere.ai.client.FakeOpenAiServer.Reply;
import edu.learnsphere.ai.runtime.EndpointProvider;
import edu.learnsphere.ai.runtime.ServerEndpoint;
import edu.learnsphere.ai.runtime.ServerRole;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(30)
class LocalChatTest {

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

    private static ChatRequest ask(String text) {
        return ChatRequest.text(List.of(ChatMessage.user(text)), 50, 0);
    }

    private static String lastUserText(com.fasterxml.jackson.databind.JsonNode body) {
        return body.path("messages").get(body.path("messages").size() - 1).path("content").asText();
    }

    @Test
    void aPreemptedBackgroundRequestRunsAgainAfterTheInteractiveOne() throws Exception {
        // The first background reply is slow to start, so the interactive request arrives before anything streams.
        AtomicInteger backgroundRequests = new AtomicInteger();
        server.onChat(body -> !lastUserText(body).equals("background") ? Reply.text("answer")
                : backgroundRequests.incrementAndGet() == 1
                        ? new Reply(200, null, List.of("page ", "text"), "stop", 0, 1_500)
                        : Reply.text("page ", "text"));
        LocalChat chat = server.localChat(client);
        List<String> finished = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<ChatResult> background = CompletableFuture.supplyAsync(() -> {
            ChatResult result = chat.complete(ChatPriority.BACKGROUND, ask("background"), TokenSink.NONE,
                    CancellationToken.NONE);
            finished.add("background");
            return result;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (server.chatRequests().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        ChatResult interactive = chat.complete(ChatPriority.INTERACTIVE, ask("interactive"), TokenSink.NONE,
                CancellationToken.NONE);
        finished.add("interactive");

        assertThat(interactive.text()).isEqualTo("answer");
        assertThat(background.get(20, TimeUnit.SECONDS).text()).isEqualTo("page text");
        assertThat(finished).containsExactly("interactive", "background");
        assertThat(server.chatRequests()).extracting(LocalChatTest::lastUserText)
                .containsExactly("background", "interactive", "background");
    }

    @Test
    void aBackgroundRequestPreemptedMidReplyStartsOverAndReturnsTheWholeText() throws Exception {
        // The interactive request arrives after the first background reply has started streaming.
        AtomicInteger backgroundRequests = new AtomicInteger();
        server.onChat(body -> !lastUserText(body).equals("background") ? Reply.text("answer")
                : backgroundRequests.incrementAndGet() == 1
                        ? new Reply(200, null, List.of("page ", "one ", "of ", "the ", "notes"), "stop", 0, 200)
                        : Reply.text("page ", "one ", "of ", "the ", "notes"));
        LocalChat chat = server.localChat(client);

        CompletableFuture<ChatResult> background = CompletableFuture.supplyAsync(() -> chat.complete(
                ChatPriority.BACKGROUND, ask("background"), TokenSink.NONE, CancellationToken.NONE));
        awaitPieces(2);
        ChatResult interactive = chat.complete(ChatPriority.INTERACTIVE, ask("interactive"), TokenSink.NONE,
                CancellationToken.NONE);

        assertThat(interactive.text()).isEqualTo("answer");
        assertThat(background.get(20, TimeUnit.SECONDS).text()).isEqualTo("page one of the notes");
        assertThat(server.chatRequests()).extracting(LocalChatTest::lastUserText)
                .containsExactly("background", "interactive", "background");
    }

    @Test
    void aPreemptedRequestWhoseTextSomeoneSawDoesNotStartOver() throws Exception {
        server.onChat(body -> lastUserText(body).equals("background")
                ? new Reply(200, null, List.of("page ", "one ", "of ", "the ", "notes"), "stop", 0, 200)
                : Reply.text("answer"));
        LocalChat chat = server.localChat(client);
        List<String> seen = Collections.synchronizedList(new ArrayList<>());

        CompletableFuture<ChatResult> background = CompletableFuture.supplyAsync(() -> chat.complete(
                ChatPriority.BACKGROUND, ask("background"), seen::add, CancellationToken.NONE));
        while (seen.isEmpty()) {
            Thread.sleep(10);
        }
        chat.complete(ChatPriority.INTERACTIVE, ask("interactive"), TokenSink.NONE, CancellationToken.NONE);

        assertThatThrownBy(() -> background.get(20, TimeUnit.SECONDS))
                .hasCauseInstanceOf(CancellationException.class);
        assertThat(server.chatRequests()).extracting(LocalChatTest::lastUserText)
                .containsExactly("background", "interactive");
    }

    private void awaitPieces(int pieces) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (server.piecesSent() < pieces && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(server.piecesSent()).isGreaterThanOrEqualTo(pieces);
        // Give the client a moment to read what was written.
        Thread.sleep(50);
    }

    @Test
    void retriesOnceWhenTheServerCouldNotBeReachedAndReportsIt() throws IOException {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        ServerEndpoint dead = new ServerEndpoint(ServerRole.CHAT, URI.create("http://127.0.0.1:" + deadPort + "/"),
                FakeOpenAiServer.KEY, true);
        ServerEndpoint live = server.endpoint(ServerRole.CHAT);
        AtomicInteger reported = new AtomicInteger();
        AtomicInteger awaited = new AtomicInteger();
        EndpointProvider restarting = new EndpointProvider() {
            @Override
            public ServerEndpoint await(ServerRole role, CancellationToken token) {
                // The first request (two awaits: before and after the gate) goes to the dead port.
                return awaited.incrementAndGet() <= 2 ? dead : live;
            }

            @Override
            public void reportUnresponsive(ServerEndpoint endpoint) {
                assertThat(endpoint).isEqualTo(dead);
                reported.incrementAndGet();
            }
        };
        LocalChat chat = new LocalChat(restarting, client, new ChatGate());

        ChatResult result = chat.complete(ChatPriority.INTERACTIVE, ask("hi"), TokenSink.NONE, CancellationToken.NONE);

        assertThat(result.text()).isEqualTo("Hello there.");
        assertThat(reported).hasValue(1);
    }

    @Test
    void givesUpAfterOneRetry() throws IOException {
        int deadPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }
        ServerEndpoint dead = new ServerEndpoint(ServerRole.CHAT, URI.create("http://127.0.0.1:" + deadPort + "/"), "k", true);
        LocalChat chat = new LocalChat(EndpointProvider.fixed(dead), client, new ChatGate());
        assertThatThrownBy(() -> chat.complete(ChatPriority.INTERACTIVE, ask("hi"), TokenSink.NONE, CancellationToken.NONE))
                .isInstanceOf(ServerUnreachableException.class);
    }

    @Test
    void imagesNeedAChatModelThatCanReadThem() {
        ServerEndpoint blind = new ServerEndpoint(ServerRole.CHAT, server.endpoint(ServerRole.CHAT).baseUri(),
                FakeOpenAiServer.KEY, false);
        LocalChat chat = new LocalChat(EndpointProvider.fixed(blind), client, new ChatGate());
        ChatRequest page = ChatRequest.text(List.of(ChatMessage.userWithImage(ChatMessage.Image.png(new byte[] {1}), "Read")),
                10, 0);
        assertThatThrownBy(() -> chat.complete(ChatPriority.BACKGROUND, page, TokenSink.NONE, CancellationToken.NONE))
                .isInstanceOf(UnavailableException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(AiMessages.NO_PAGE_READING);
        assertThat(server.chatRequests()).isEmpty();
    }

    @Test
    void releasesTheGateAfterEveryRequest() {
        LocalChat chat = server.localChat(client);
        server.replies(Reply.error(500, "boom"), Reply.text("ok"));
        assertThatThrownBy(() -> chat.complete(ChatPriority.USER_WAITING, ask("a"), TokenSink.NONE, CancellationToken.NONE))
                .isInstanceOf(LearnSphereException.class);
        assertThat(chat.gate().running()).isZero();
        assertThat(chat.complete(ChatPriority.USER_WAITING, ask("b"), TokenSink.NONE, CancellationToken.NONE).text())
                .isEqualTo("ok");
        assertThat(chat.gate().running()).isZero();
    }
}
