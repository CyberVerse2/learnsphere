package edu.learnsphere.search;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal rank fusion of a keyword ranking and a vector ranking: each result scores
 * {@code 1 / (k + rank)} for every ranking it appears in (ranks are 1-based), summed.
 *
 * <p>The order is total and does not depend on Lucene document numbers, so two indexes with the same
 * passages rank them the same way: score (higher first), then the better of the two ranks, then the keyword
 * rank, then the vector rank. Two different results never share a keyword rank or a vector rank, so these
 * keys always separate them.
 */
final class ReciprocalRankFusion {

    /**
     * One fused result.
     *
     * @param doc          Lucene document number
     * @param keywordRank  1-based rank among keyword results, or null
     * @param vectorRank   1-based rank among vector results, or null
     */
    record Fused(int doc, double score, Integer keywordRank, Integer vectorRank) {

        int bestRank() {
            return Math.min(keywordRank == null ? Integer.MAX_VALUE : keywordRank,
                    vectorRank == null ? Integer.MAX_VALUE : vectorRank);
        }
    }

    static final Comparator<Fused> ORDER = Comparator.comparingDouble(Fused::score).reversed()
            .thenComparingInt(Fused::bestRank)
            .thenComparing(Fused::keywordRank, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Fused::vectorRank, Comparator.nullsLast(Comparator.naturalOrder()));

    private ReciprocalRankFusion() {
    }

    /**
     * Fuses two rankings of document numbers, best first.
     *
     * @param keywordDocs keyword results, best first (a repeated document keeps its first rank)
     * @param vectorDocs  vector results, best first
     * @param k           the fusion constant (60 is the usual choice)
     * @param limit       how many fused results to keep
     */
    static List<Fused> fuse(int[] keywordDocs, int[] vectorDocs, int k, int limit) {
        if (k <= 0 || limit <= 0) {
            throw new IllegalArgumentException("k and limit must be positive");
        }
        Map<Integer, int[]> ranks = new LinkedHashMap<>();
        for (int i = 0; i < keywordDocs.length; i++) {
            int[] pair = ranks.computeIfAbsent(keywordDocs[i], d -> new int[2]);
            if (pair[0] == 0) {
                pair[0] = i + 1;
            }
        }
        for (int i = 0; i < vectorDocs.length; i++) {
            int[] pair = ranks.computeIfAbsent(vectorDocs[i], d -> new int[2]);
            if (pair[1] == 0) {
                pair[1] = i + 1;
            }
        }
        List<Fused> fused = new ArrayList<>(ranks.size());
        ranks.forEach((doc, pair) -> {
            double score = 0;
            if (pair[0] > 0) {
                score += 1.0 / (k + pair[0]);
            }
            if (pair[1] > 0) {
                score += 1.0 / (k + pair[1]);
            }
            fused.add(new Fused(doc, score, pair[0] > 0 ? pair[0] : null, pair[1] > 0 ? pair[1] : null));
        });
        fused.sort(ORDER);
        return fused.size() <= limit ? fused : List.copyOf(fused.subList(0, limit));
    }
}
