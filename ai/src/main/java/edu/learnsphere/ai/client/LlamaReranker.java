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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link Reranker} over the reranking server (Qwen3-Reranker). Scores are relevance probabilities in 0–1.
 * The bundled model's server reports probabilities, which are used as they are. A reranking model that
 * reports raw logits instead is recognised by any score outside 0–1; from then on every score from this
 * reranker goes through the logistic function, so the order of passages and their comparison with fixed
 * thresholds stay consistent. Passages are cut to {@value #MAX_CHARS} characters and sent {@value #BATCH}
 * at a time. Thread-safe.
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
    /** True once the server reported a score outside 0–1: it returns logits, not probabilities. */
    private final AtomicBoolean logits = new AtomicBoolean();

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
        double[] raw = new double[passages.size()];
        boolean[] scored = new boolean[passages.size()];
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
                raw[positions.get(j)] = batchScores[j];
                scored[positions.get(j)] = true;
                if (isLogit(batchScores[j])) {
                    logits.set(true);
                }
            }
        }
        // Decided after every batch is in, so all scores of one call are transformed the same way.
        boolean asLogits = logits.get();
        for (int i = 0; i < raw.length; i++) {
            if (scored[i]) {
                scores[i] = toProbability(raw[i], asLogits);
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

    /** True for a score no probability can have. */
    static boolean isLogit(double score) {
        return !Double.isNaN(score) && (score < 0 || score > 1);
    }

    /** A server score as a probability: the logistic of a logit, or a probability kept within 0–1. */
    static double toProbability(double score, boolean logit) {
        if (Double.isNaN(score)) {
            return 0;
        }
        if (logit) {
            return 1 / (1 + Math.exp(-score));
        }
        return Math.clamp(score, 0.0, 1.0);
    }

    private static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
