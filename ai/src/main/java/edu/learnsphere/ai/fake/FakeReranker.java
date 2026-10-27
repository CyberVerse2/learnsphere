package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.Reranker;
import edu.learnsphere.core.util.CancellationToken;
import java.util.List;
import java.util.Set;

/** Scores each passage by the fraction of the query's content words it contains (0–1). */
public final class FakeReranker implements Reranker {

    @Override
    public double[] score(String query, List<String> passages, CancellationToken token) {
        Set<String> queryWords = FakeText.contentWords(query);
        double[] scores = new double[passages.size()];
        for (int i = 0; i < scores.length; i++) {
            token.throwIfCancelled();
            if (queryWords.isEmpty()) {
                continue;
            }
            Set<String> passageWords = FakeText.contentWords(passages.get(i));
            long hits = queryWords.stream().filter(passageWords::contains).count();
            scores[i] = (double) hits / queryWords.size();
        }
        return scores;
    }
}
