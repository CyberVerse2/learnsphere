package edu.learnsphere.ai.client;

import edu.learnsphere.ai.AiMessages;
import edu.learnsphere.ai.runtime.EndpointProvider;
import edu.learnsphere.ai.runtime.ServerEndpoint;
import edu.learnsphere.ai.runtime.ServerRole;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.util.CancellationToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link Embedder} over the embedding server (Qwen3-Embedding with last-token pooling). Queries get the
 * instruction prefix {@link #QUERY_PREFIX}; passages are embedded as they are. Texts go to the server in
 * batches of {@value #BATCH}, each cut to {@value #MAX_CHARS} characters, and every vector is
 * L2-normalized. Thread-safe; requests run in parallel with chat.
 */
public final class LlamaEmbedder implements Embedder {

    /** Prefix that tells Qwen3-Embedding a text is a question looking for passages. */
    public static final String QUERY_PREFIX =
            "Instruct: Given a student's question, retrieve passages from course material that answer it\nQuery: ";
    /** Texts per request. */
    public static final int BATCH = 16;
    /** Longest text sent, in characters (well inside the server's 8192-token context). */
    public static final int MAX_CHARS = 20_000;

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final EndpointProvider endpoints;
    private final OpenAiClient client;
    private final int dimensions;
    private final String modelName;

    /**
     * @param dimensions vector length of the embedding model
     * @param modelName  name stored with each vector
     */
    public LlamaEmbedder(EndpointProvider endpoints, OpenAiClient client, int dimensions, String modelName) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.client = Objects.requireNonNull(client, "client");
        this.modelName = Objects.requireNonNull(modelName, "modelName");
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions must be >= 1: " + dimensions);
        }
        this.dimensions = dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts, EmbedKind kind, CancellationToken token) {
        Objects.requireNonNull(texts, "texts");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += BATCH) {
            List<String> batch = new ArrayList<>();
            for (String text : texts.subList(from, Math.min(texts.size(), from + BATCH))) {
                batch.add(prepare(text, kind));
            }
            vectors.addAll(embedBatch(batch, token));
        }
        return vectors;
    }

    private List<float[]> embedBatch(List<String> batch, CancellationToken token) {
        for (int attempt = 0; ; attempt++) {
            ServerEndpoint endpoint = endpoints.await(ServerRole.EMBED, token);
            try {
                List<float[]> raw = client.embed(endpoint, batch, TIMEOUT, token);
                List<float[]> normalized = new ArrayList<>(raw.size());
                for (float[] vector : raw) {
                    if (vector.length != dimensions) {
                        throw new LearnSphereException(AiMessages.REQUEST_FAILED, "Expected " + dimensions
                                + "-dimensional embeddings from " + modelName + " but got " + vector.length);
                    }
                    normalized.add(normalize(vector));
                }
                return normalized;
            } catch (ServerUnreachableException e) {
                endpoints.reportUnresponsive(endpoint);
                if (attempt >= LocalChat.UNREACHABLE_RETRIES) {
                    throw e;
                }
            }
        }
    }

    static String prepare(String text, EmbedKind kind) {
        String body = text == null ? "" : text.strip();
        if (body.length() > MAX_CHARS) {
            body = body.substring(0, MAX_CHARS);
        }
        if (kind == EmbedKind.QUERY) {
            return QUERY_PREFIX + body;
        }
        // The server rejects empty input; a single space embeds as "nothing".
        return body.isEmpty() ? " " : body;
    }

    /** {@code vector} scaled to length 1 (a zero vector becomes a fixed unit vector). */
    static float[] normalize(float[] vector) {
        double sum = 0;
        for (float x : vector) {
            sum += (double) x * x;
        }
        float[] result = vector.clone();
        if (sum == 0 || Double.isNaN(sum)) {
            java.util.Arrays.fill(result, 0f);
            result[0] = 1f;
            return result;
        }
        float scale = (float) (1 / Math.sqrt(sum));
        for (int i = 0; i < result.length; i++) {
            result[i] *= scale;
        }
        return result;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String modelName() {
        return modelName;
    }
}
