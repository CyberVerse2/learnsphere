package edu.learnsphere.ai.client;

import edu.learnsphere.ai.runtime.EndpointProvider;
import edu.learnsphere.ai.runtime.ServerEndpoint;
import edu.learnsphere.ai.runtime.ServerRole;
import edu.learnsphere.core.spi.Reranker;
import edu.learnsphere.core.util.CancellationToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * {@link Reranker} over the reranking server (Qwen3-Reranker). Scores are the server's relevance
 * probabilities, kept within 0–1 (a raw logit is passed through the logistic function). Passages are cut
 * to {@value #MAX_CHARS} characters and sent {@value #BATCH} at a time. Thread-safe.
 */
public final class LlamaReranker implements Reranker {

    /** Longest passage sent, in characters. */
    public static final int MAX_CHARS = 6_000;
    /** Longest query sent, in characters. */
    public static final int MAX_QUERY_CHARS = 2_000;
    /** Passages per request. */
    public static final int BATCH = 32;

    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final EndpointProvider endpoints;
    private final OpenAiClient client;

    public LlamaReranker(EndpointProvider endpoints, OpenAiClient client) {
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.client = Objects.requireNonNull(client, "client");
    }

    @Override
    public double[] score(String query, List<String> passages, CancellationToken token) {
        Objects.requireNonNull(passages, "passages");
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        double[] scores = new double[passages.size()];
        String q = cut(query == null ? "" : query.strip(), MAX_QUERY_CHARS);
        if (q.isEmpty()) {
            return scores;
        }
        for (int from = 0; from < passages.size(); from += BATCH) {
            List<String> batch = new ArrayList<>();
            List<Integer> positions = new ArrayList<>();
            for (int i = from; i < Math.min(passages.size(), from + BATCH); i++) {
                String text = passages.get(i) == null ? "" : passages.get(i).strip();
                if (!text.isEmpty()) {
                    batch.add(cut(text, MAX_CHARS));
                    positions.add(i);
                }
            }
            if (batch.isEmpty()) {
                continue;
            }
            double[] batchScores = scoreBatch(q, batch, token);
            for (int j = 0; j < batchScores.length; j++) {
                scores[positions.get(j)] = toProbability(batchScores[j]);
            }
        }
        return scores;
    }

    private double[] scoreBatch(String query, List<String> batch, CancellationToken token) {
        for (int attempt = 0; ; attempt++) {
            ServerEndpoint endpoint = endpoints.await(ServerRole.RERANK, token);
            try {
                return client.rerank(endpoint, query, batch, TIMEOUT, token);
            } catch (ServerUnreachableException e) {
                endpoints.reportUnresponsive(endpoint);
                if (attempt >= LocalChat.UNREACHABLE_RETRIES) {
                    throw e;
                }
            }
        }
    }

    static double toProbability(double score) {
        if (Double.isNaN(score)) {
            return 0;
        }
        if (score >= 0 && score <= 1) {
            return score;
        }
        return 1 / (1 + Math.exp(-score));
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
