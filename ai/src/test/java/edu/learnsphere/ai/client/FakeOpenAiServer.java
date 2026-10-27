package edu.learnsphere.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.learnsphere.ai.runtime.EndpointProvider;
import edu.learnsphere.ai.runtime.ServerEndpoint;
import edu.learnsphere.ai.runtime.ServerRole;
import edu.learnsphere.core.util.Json;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * An in-process stand-in for llama-server's OpenAI-compatible endpoints. Chat replies are scripted per
 * request ({@link #onChat}); embeddings and rerank answers are computed from the request. Every request body
 * is recorded. Requests without the right bearer key get 401, like the real server.
 */
final class FakeOpenAiServer implements AutoCloseable {

    static final String KEY = "test-key";

    /**
     * A scripted chat reply.
     *
     * @param status      HTTP status; anything but 200 sends {@code errorJson} as the body
     * @param pieces      streamed content pieces
     * @param finish      finish reason sent with the last piece, or null to end the stream without one
     * @param headerDelay pause before the response headers, in milliseconds
     * @param gap         pause before each piece, in milliseconds
     */
    record Reply(int status, String errorJson, List<String> pieces, String finish, long headerDelay, long gap) {

        static Reply text(String... pieces) {
            return new Reply(200, null, List.of(pieces), "stop", 0, 0);
        }

        static Reply truncated(String... pieces) {
            return new Reply(200, null, List.of(pieces), "length", 0, 0);
        }

        static Reply json(String json) {
            return text(json);
        }

        static Reply error(int status, String message) {
            return new Reply(status, "{\"error\":{\"code\":" + status + ",\"type\":\"invalid_request_error\","
                    + "\"message\":\"" + message + "\"}}", List.of(), null, 0, 0);
        }

        Reply slow(long headerDelay, long gap) {
            return new Reply(status, errorJson, pieces, finish, headerDelay, gap);
        }

        Reply unfinished() {
            return new Reply(status, errorJson, pieces, null, headerDelay, gap);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<JsonNode> chatRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> embedRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> rerankRequests = new CopyOnWriteArrayList<>();
    private volatile Function<JsonNode, Reply> chat = body -> Reply.text("Hello", " there.");
    private volatile int dimensions = 4;
    private volatile CountDownLatch disconnected = new CountDownLatch(1);
    private final AtomicInteger piecesSent = new AtomicInteger();

    FakeOpenAiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        server.createContext("/v1/chat/completions", this::chat);
        server.createContext("/v1/embeddings", this::embeddings);
        server.createContext("/v1/rerank", this::rerank);
        server.start();
    }

    // ------------------------------------------------------------------ setup

    /** Scripts the chat replies. */
    FakeOpenAiServer onChat(Function<JsonNode, Reply> handler) {
        this.chat = handler;
        return this;
    }

    /** Scripts every chat reply to be {@code reply}. */
    FakeOpenAiServer reply(Reply reply) {
        return onChat(body -> reply);
    }

    /** Scripts chat replies in turn (the last one repeats). */
    FakeOpenAiServer replies(Reply... replies) {
        List<Reply> queue = new ArrayList<>(List.of(replies));
        return onChat(body -> {
            synchronized (queue) {
                return queue.size() > 1 ? queue.removeFirst() : queue.getFirst();
            }
        });
    }

    FakeOpenAiServer dimensions(int dimensions) {
        this.dimensions = dimensions;
        return this;
    }

    // ------------------------------------------------------------------ access

    ServerEndpoint endpoint(ServerRole role) {
        return new ServerEndpoint(role, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), KEY, true);
    }

    ServerEndpoint endpointWithKey(ServerRole role, String key) {
        return new ServerEndpoint(role, endpoint(role).baseUri(), key, true);
    }

    EndpointProvider endpoints() {
        return EndpointProvider.fixed(endpoint(ServerRole.CHAT), endpoint(ServerRole.EMBED), endpoint(ServerRole.RERANK));
    }

    /** A chat client over this server with a fresh gate. */
    LocalChat localChat(OpenAiClient client) {
        return new LocalChat(endpoints(), client, new ChatGate());
    }

    List<JsonNode> chatRequests() {
        return chatRequests;
    }

    JsonNode lastChatRequest() {
        return chatRequests.getLast();
    }

    List<JsonNode> embedRequests() {
        return embedRequests;
    }

    List<JsonNode> rerankRequests() {
        return rerankRequests;
    }

    /** Chat content pieces written to clients so far. */
    int piecesSent() {
        return piecesSent.get();
    }

    /** Counts down when a streamed reply could not be written because the client went away. */
    CountDownLatch disconnected() {
        return disconnected;
    }

    // ------------------------------------------------------------------ handlers

    private static boolean authorized(HttpExchange exchange) throws IOException {
        if (("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            return true;
        }
        respond(exchange, 401, "{\"error\":{\"code\":401,\"message\":\"Invalid API Key\",\"type\":\"authentication_error\"}}");
        return false;
    }

    private static JsonNode body(HttpExchange exchange) throws IOException {
        return Json.mapper().readTree(exchange.getRequestBody().readAllBytes());
    }

    private void chat(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        JsonNode body = body(exchange);
        chatRequests.add(body);
        Reply reply = chat.apply(body);
        sleep(reply.headerDelay());
        if (reply.status() != 200) {
            respond(exchange, reply.status(), reply.errorJson());
            return;
        }
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        exchange.sendResponseHeaders(200, 0);
        try (OutputStream out = exchange.getResponseBody()) {
            for (int i = 0; i < reply.pieces().size(); i++) {
                sleep(reply.gap());
                ObjectNode chunk = Json.mapper().createObjectNode();
                ObjectNode choice = chunk.putArray("choices").addObject();
                choice.putObject("delta").put("content", reply.pieces().get(i));
                if (i == reply.pieces().size() - 1 && reply.finish() != null) {
                    choice.put("finish_reason", reply.finish());
                } else {
                    choice.putNull("finish_reason");
                }
                writeEvent(out, Json.write(chunk));
                piecesSent.incrementAndGet();
            }
            if (reply.pieces().isEmpty() && reply.finish() != null) {
                writeEvent(out, "{\"choices\":[{\"delta\":{},\"finish_reason\":\"" + reply.finish() + "\"}]}");
            }
            if (reply.finish() != null) {
                writeEvent(out, "{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":"
                        + reply.pieces().size() + "}}");
                writeEvent(out, "[DONE]");
            }
        } catch (IOException e) {
            disconnected.countDown();
        }
    }

    private static void writeEvent(OutputStream out, String data) throws IOException {
        out.write(("data: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private void embeddings(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        JsonNode body = body(exchange);
        embedRequests.add(body);
        ObjectNode reply = Json.mapper().createObjectNode();
        ArrayNode data = reply.putArray("data");
        int index = 0;
        for (JsonNode input : body.path("input")) {
            ObjectNode item = data.addObject();
            item.put("index", index++);
            ArrayNode vector = item.putArray("embedding");
            String text = input.asText();
            for (int d = 0; d < dimensions; d++) {
                // Deterministic and never all zero: length and character sums.
                vector.add(d == 0 ? text.length() + 1 : text.chars().filter(c -> c % dimensions == 0).count() + d);
            }
        }
        respond(exchange, 200, Json.write(reply));
    }

    private void rerank(HttpExchange exchange) throws IOException {
        if (!authorized(exchange)) {
            return;
        }
        JsonNode body = body(exchange);
        rerankRequests.add(body);
        String query = body.path("query").asText().toLowerCase();
        ObjectNode reply = Json.mapper().createObjectNode();
        ArrayNode results = reply.putArray("results");
        List<JsonNode> documents = new ArrayList<>();
        body.path("documents").forEach(documents::add);
        // Reverse order, like the server (sorted by score); a raw logit: positive when the document shares
        // the query's first word.
        String first = query.split("\\s+")[0];
        for (int i = documents.size() - 1; i >= 0; i--) {
            boolean match = documents.get(i).asText().toLowerCase().contains(first);
            results.addObject().put("index", i).put("relevance_score", match ? 4.0 : -4.0);
        }
        respond(exchange, 200, Json.write(reply));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
