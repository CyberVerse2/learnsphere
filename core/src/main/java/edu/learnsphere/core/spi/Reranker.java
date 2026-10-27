package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;
import java.util.List;

/** Scores how well passages answer a query (implemented in the ai module). Thread-safe. */
public interface Reranker {

    /** Relevance 0–1 for each passage, in the same order as {@code passages}. */
    double[] score(String query, List<String> passages, CancellationToken token);
}
