package edu.learnsphere.testbench.metrics;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Ranking metrics for retrieval. A ranked result list is given as, for each rank, the relevant targets that
 * result covers (an empty set for an irrelevant result); a question's targets are the pages that answer it.
 */
public final class Ranking {

    private Ranking() {
    }

    /**
     * Recall at {@code k}: the share of {@code targets} covered by the first {@code k} results. With a single
     * target this is 1 when any of the first {@code k} results covers it and 0 otherwise (the hit rate).
     *
     * @return a value in [0, 1]; 1 when there are no targets
     */
    public static <T> double recallAtK(List<? extends Collection<T>> ranked, Set<T> targets, int k) {
        if (k < 1) {
            throw new IllegalArgumentException("k must be >= 1: " + k);
        }
        if (targets.isEmpty()) {
            return 1;
        }
        Set<T> found = new HashSet<>();
        for (int i = 0; i < Math.min(k, ranked.size()); i++) {
            for (T covered : ranked.get(i)) {
                if (targets.contains(covered)) {
                    found.add(covered);
                }
            }
        }
        return found.size() / (double) targets.size();
    }

    /**
     * The reciprocal rank: 1 / the (1-based) rank of the first result covering any target, or 0 when none does.
     */
    public static <T> double reciprocalRank(List<? extends Collection<T>> ranked, Set<T> targets) {
        for (int i = 0; i < ranked.size(); i++) {
            for (T covered : ranked.get(i)) {
                if (targets.contains(covered)) {
                    return 1.0 / (i + 1);
                }
            }
        }
        return 0;
    }

    /** The (1-based) rank of the first result covering any target, or 0 when none does. */
    public static <T> int firstRelevantRank(List<? extends Collection<T>> ranked, Set<T> targets) {
        double reciprocal = reciprocalRank(ranked, targets);
        return reciprocal == 0 ? 0 : (int) Math.round(1 / reciprocal);
    }

    /** Mean reciprocal rank over questions; 0 for no questions. */
    public static double meanReciprocalRank(List<Double> reciprocalRanks) {
        return Stats.mean(reciprocalRanks);
    }
}
