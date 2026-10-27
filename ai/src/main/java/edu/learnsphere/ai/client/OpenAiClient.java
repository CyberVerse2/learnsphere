package edu.learnsphere.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.learnsphere.ai.AiMessages;
import edu.learnsphere.ai.runtime.ServerEndpoint;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Json;
import edu.learnsphere.core.util.Subscription;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for llama-server's OpenAI-compatible endpoints: {@code /v1/chat/completions} (always streamed as
 * server-sent events, with images as base64 data URLs and optional JSON-schema output),
 * {@code /v1/embeddings} and {@code /v1/rerank}.
 *
 * <p>Cancelling a token aborts the HTTP exchange (the connection is closed, which also stops generation
 * on the server) and the call throws {@link CancellationException}. Time limits throw
 * {@link AiTimeoutException}; a server that cannot be reached throws {@link ServerUnreachableException};
 * other failures throw a {@link LearnSphereException} whose message never contains server text.
 *
 * <p>Thread-safe; one instance serves every request.
 */
public final class OpenAiClient implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(OpenAiClient.class);
    private static final ObjectMapper MAPPER = Json.mapper();
    private static final int MAX_ERROR_BODY = 4096;

    private final HttpClient http;
    private final ScheduledExecutorService watchdogs;

    /** A client with a 5-second connect timeout. */
    public OpenAiClient() {
        this(Duration.ofSeconds(5));
    }

    public OpenAiClient(Duration connectTimeout) {
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.watchdogs = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("learnsphere-ai-watchdog").factory());
    }

    // ------------------------------------------------------------------ chat

    /**
     * Runs a chat completion, passing each piece of generated text to {@code sink} as it arrives.
     *
     * @throws CancellationException when {@code token} is cancelled
     */
    public ChatResult chat(ServerEndpoint endpoint, ChatRequest request, TokenSink sink, CancellationToken token) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(sink, "sink");
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        String body = Json.write(chatBody(request));
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint.resolve("/v1/chat/completions"))
                .header("Authorization", "Bearer " + endpoint.apiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .timeout(request.firstTokenWait())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        long started = System.nanoTime();
        AtomicLong lastActivity = new AtomicLong(started);
        AtomicLong firstToken = new AtomicLong(-1);
        AtomicReference<String> abortReason = new AtomicReference<>();
        AtomicReference<InputStream> stream = new AtomicReference<>();
        CompletableFuture<HttpResponse<InputStream>> future =
                http.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
        Runnable abort = () -> {
            future.cancel(true);
            closeQuietly(stream.get());
        };
        Subscription cancelled = token.onCancel(() -> {
            abortReason.compareAndSet(null, "cancelled");
            abort.run();
        });
        ScheduledFuture<?> watchdog = watchdogs.scheduleWithFixedDelay(() -> {
            long now = System.nanoTime();
            String reason = null;
            if (now - started > request.total().toNanos()) {
                reason = "no reply within " + request.total();
            } else if (firstToken.get() < 0 && now - started > request.firstTokenWait().toNanos()) {
                reason = "no first token within " + request.firstTokenWait();
            } else if (firstToken.get() >= 0 && now - lastActivity.get() > request.tokenGap().toNanos()) {
                reason = "no token for " + request.tokenGap();
            }
            if (reason != null && abortReason.compareAndSet(null, "timeout: " + reason)) {
                abort.run();
            }
        }, 250, 250, TimeUnit.MILLISECONDS);

        StringBuilder text = new StringBuilder();
        String finishReason = null;
        int promptTokens = 0;
        int completionTokens = 0;
        boolean receivedAny = false;
        try {
            HttpResponse<InputStream> response = future.get();
            stream.set(response.body());
            if (abortReason.get() != null) {
                closeQuietly(response.body());
            }
            if (response.statusCode() != 200) {
                throw httpError(endpoint, response.statusCode(), readBounded(response.body()));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                boolean done = false;
                while (!done && (line = reader.readLine()) != null) {
                    lastActivity.set(System.nanoTime());
                    receivedAny = true;
                    String payload = ssePayload(line);
                    if (payload == null) {
                        continue;
                    }
                    if (payload.equals("[DONE]")) {
                        done = true;
                        continue;
                    }
                    JsonNode chunk = MAPPER.readTree(payload);
                    if (chunk.hasNonNull("error")) {
                        throw serverError(endpoint, chunk.get("error"));
                    }
                    JsonNode usage = chunk.get("usage");
                    if (usage != null && usage.isObject()) {
                        promptTokens = usage.path("prompt_tokens").asInt(promptTokens);
                        completionTokens = usage.path("completion_tokens").asInt(completionTokens);
                    }
                    JsonNode choice = chunk.path("choices").path(0);
                    if (choice.isMissingNode()) {
                        continue;
                    }
                    JsonNode content = choice.path("delta").path("content");
                    if (content.isTextual() && !content.asText().isEmpty()) {
                        firstToken.compareAndSet(-1, System.nanoTime());
                        text.append(content.asText());
                        token.throwIfCancelled();
                        sink.accept(content.asText());
                    }
                    JsonNode finish = choice.path("finish_reason");
                    if (finish.isTextual()) {
                        finishReason = finish.asText();
                    }
                }
            }
            checkAborted(abortReason, token);
            if (finishReason == null) {
                throw new LearnSphereException(AiMessages.REQUEST_FAILED,
                        "The chat reply from " + endpoint + " ended without finishing");
            }
        } catch (CancellationException e) {
            checkAborted(abortReason, token);
            throw e;
        } catch (ExecutionException e) {
            checkAborted(abortReason, token);
            throw transportError(endpoint, e.getCause(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abort.run();
            throw new CancellationException("Interrupted");
        } catch (IOException e) {
            checkAborted(abortReason, token);
            throw transportError(endpoint, e, receivedAny);
        } finally {
            cancelled.close();
            watchdog.cancel(false);
            closeQuietly(stream.get());
        }
        long now = System.nanoTime();
        long first = firstToken.get() < 0 ? now : firstToken.get();
        return new ChatResult(text.toString(), finishReason, promptTokens, completionTokens,
                Duration.ofNanos(first - started), Duration.ofNanos(now - started));
    }

    static ObjectNode chatBody(ChatRequest request) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode node = messages.addObject();
            node.put("role", message.role().apiName());
            if (message.parts().size() == 1 && message.parts().getFirst() instanceof ChatMessage.Text only) {
                node.put("content", only.text());
                continue;
            }
            ArrayNode content = node.putArray("content");
            for (ChatMessage.Part part : message.parts()) {
                switch (part) {
                    case ChatMessage.Text t -> content.addObject().put("type", "text").put("text", t.text());
                    case ChatMessage.Image image -> content.addObject().put("type", "image_url")
                            .putObject("image_url").put("url", "data:" + image.mediaType() + ";base64,"
                                    + Base64.getEncoder().encodeToString(image.bytes()));
                }
            }
        }
        body.put("stream", true);
        body.putObject("stream_options").put("include_usage", true);
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        body.put("cache_prompt", true);
        body.putObject("chat_template_kwargs").put("enable_thinking", false);
        if (request.jsonSchema() != null) {
            ObjectNode format = body.putObject("response_format");
            format.put("type", "json_schema");
            ObjectNode schema = format.putObject("json_schema");
            schema.put("name", request.schemaName());
            schema.put("strict", true);
            schema.set("schema", request.jsonSchema());
        }
        return body;
    }

    /** The payload of an SSE {@code data:} line, or null for other lines (comments, blank lines, event names). */
    static String ssePayload(String line) {
        if (line.startsWith("data:")) {
            return line.substring(5).strip();
        }
        if (line.startsWith("error:")) {
            // Some server versions report a failure mid-stream as an "error:" line with a JSON body.
            String json = line.substring(6).strip();
            return json.startsWith("{") && !json.contains("\"error\"") ? "{\"error\":" + json + "}" : json;
        }
        return null;
    }

    // ------------------------------------------------------------------ embeddings and rerank

    /**
     * Embeds {@code inputs} in one request.
     *
     * @return one vector per input, in input order
     */
    public List<float[]> embed(ServerEndpoint endpoint, List<String> inputs, Duration timeout, CancellationToken token) {
        ObjectNode body = MAPPER.createObjectNode();
        ArrayNode input = body.putArray("input");
        inputs.forEach(input::add);
        body.put("encoding_format", "float");
        JsonNode response = postJson(endpoint, "/v1/embeddings", body, timeout, token);
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() != inputs.size()) {
            throw badResponse(endpoint, "expected " + inputs.size() + " embeddings, got " + data.size());
        }
        float[][] vectors = new float[inputs.size()][];
        for (JsonNode item : data) {
            int index = item.path("index").asInt(-1);
            JsonNode values = item.path("embedding");
            if (index < 0 || index >= vectors.length || !values.isArray() || values.isEmpty()) {
                throw badResponse(endpoint, "malformed embedding entry");
            }
            // Some server versions nest the vector one level deeper for pooled outputs.
            if (values.get(0).isArray()) {
                values = values.get(0);
            }
            float[] vector = new float[values.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) values.get(i).asDouble();
            }
            vectors[index] = vector;
        }
        List<float[]> result = new ArrayList<>(vectors.length);
        for (float[] vector : vectors) {
            if (vector == null) {
                throw badResponse(endpoint, "missing embedding");
            }
            result.add(vector);
        }
        return result;
    }

    /**
     * Scores {@code documents} against {@code query}.
     *
     * @return the server's relevance score per document, in document order
     */
    public double[] rerank(ServerEndpoint endpoint, String query, List<String> documents, Duration timeout,
                           CancellationToken token) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("query", query);
        ArrayNode docs = body.putArray("documents");
        documents.forEach(docs::add);
        body.put("top_n", documents.size());
        JsonNode response = postJson(endpoint, "/v1/rerank", body, timeout, token);
        JsonNode results = response.has("results") ? response.get("results") : response.path("data");
        if (!results.isArray()) {
            throw badResponse(endpoint, "rerank reply has no results");
        }
        double[] scores = new double[documents.size()];
        boolean[] seen = new boolean[documents.size()];
        for (JsonNode item : results) {
            int index = item.path("index").asInt(-1);
            JsonNode score = item.has("relevance_score") ? item.get("relevance_score") : item.path("score");
            if (index < 0 || index >= scores.length || !score.isNumber()) {
                throw badResponse(endpoint, "malformed rerank entry");
            }
            scores[index] = score.asDouble();
            seen[index] = true;
        }
        for (int i = 0; i < seen.length; i++) {
            if (!seen[i]) {
                throw badResponse(endpoint, "rerank reply misses document " + i);
            }
        }
        return scores;
    }

    private JsonNode postJson(ServerEndpoint endpoint, String path, JsonNode body, Duration timeout,
                              CancellationToken token) {
        token.throwIfCancelled();
        HttpRequest request = HttpRequest.newBuilder(endpoint.resolve(path))
                .header("Authorization", "Bearer " + endpoint.apiKey())
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> future =
                http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        Subscription cancelled = token.onCancel(() -> future.cancel(true));
        try {
            HttpResponse<String> response = future.get();
            if (response.statusCode() != 200) {
                throw httpError(endpoint, response.statusCode(), response.body());
            }
            return MAPPER.readTree(response.body());
        } catch (CancellationException e) {
            token.throwIfCancelled();
            throw e;
        } catch (ExecutionException e) {
            token.throwIfCancelled();
            throw transportError(endpoint, e.getCause(), false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new CancellationException("Interrupted");
        } catch (IOException e) {
            throw badResponse(endpoint, "unreadable reply: " + e.getMessage());
        } finally {
            cancelled.close();
        }
    }

    // ------------------------------------------------------------------ errors

    private static void checkAborted(AtomicReference<String> abortReason, CancellationToken token) {
        String reason = abortReason.get();
        if (token.isCancelled() || "cancelled".equals(reason)) {
            throw new CancellationException("Cancelled");
        }
        if (reason != null) {
            throw new AiTimeoutException("Chat request abandoned: " + reason);
        }
    }

    private static RuntimeException transportError(ServerEndpoint endpoint, Throwable cause, boolean midStream) {
        if (cause instanceof HttpTimeoutException && !(cause instanceof HttpConnectTimeoutException)) {
            return new AiTimeoutException(endpoint + " did not answer in time");
        }
        if (cause instanceof LearnSphereException e) {
            return e;
        }
        if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException || !midStream) {
            return new ServerUnreachableException(endpoint, endpoint + " could not be reached: " + cause, cause);
        }
        return new LearnSphereException(AiMessages.REQUEST_FAILED, endpoint + " broke off its reply: " + cause, cause);
    }

    private static LearnSphereException httpError(ServerEndpoint endpoint, int status, String body) {
        String type = "";
        String message = body == null ? "" : body;
        try {
            JsonNode error = MAPPER.readTree(body).path("error");
            if (error.isObject()) {
                type = error.path("type").asText("");
                message = error.path("message").asText(message);
            }
        } catch (IOException | RuntimeException e) {
            // Not JSON; keep the raw text for the log.
        }
        String detail = endpoint + " answered HTTP " + status + " " + type + ": " + abbreviate(message);
        LOG.debug(detail);
        String lower = (type + " " + message).toLowerCase(Locale.ROOT);
        if (status == 400 && (lower.contains("context") || lower.contains("too long") || lower.contains("too large"))) {
            return new InvalidInputException(AiMessages.TOO_LONG, detail);
        }
        if (status == 401 || status == 403 || status == 503) {
            return new UnavailableException(UserMessage.ASSISTANT_STARTING, detail);
        }
        return new LearnSphereException(AiMessages.REQUEST_FAILED, detail);
    }

    private static LearnSphereException serverError(ServerEndpoint endpoint, JsonNode error) {
        int code = error.path("code").asInt(500);
        return httpError(endpoint, code, Json.write(MAPPER.createObjectNode().set("error", error)));
    }

    private static LearnSphereException badResponse(ServerEndpoint endpoint, String detail) {
        return new LearnSphereException(AiMessages.REQUEST_FAILED, endpoint + ": " + detail);
    }

    private static String readBounded(InputStream body) {
        try (InputStream in = body) {
            byte[] bytes = in.readNBytes(MAX_ERROR_BODY);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static String abbreviate(String text) {
        return text.length() > 300 ? text.substring(0, 300) + "…" : text;
    }

    private static void closeQuietly(InputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException | RuntimeException e) {
            // Aborting anyway.
        }
    }

    @Override
    public void close() {
        watchdogs.shutdownNow();
        http.close();
    }
}
