package edu.learnsphere.testbench.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RankingTest {

    private static final Set<Integer> NONE = Set.of();

    @Test
    void recallAtKIsTheShareOfTargetsCoveredInTheTopK() {
        List<Set<Integer>> ranked = List.of(NONE, Set.of(4), NONE, NONE, Set.of(5), Set.of(6));
        assertThat(Ranking.recallAtK(ranked, Set.of(4), 1)).isZero();
        assertThat(Ranking.recallAtK(ranked, Set.of(4), 2)).isEqualTo(1.0);
        assertThat(Ranking.recallAtK(ranked, Set.of(4, 5), 2)).isEqualTo(0.5);
        assertThat(Ranking.recallAtK(ranked, Set.of(4, 5), 5)).isEqualTo(1.0);
        assertThat(Ranking.recallAtK(ranked, Set.of(4, 6), 5)).isEqualTo(0.5);
    }

    @Test
    void recallCountsATargetOnceEvenWhenSeveralResultsCoverIt() {
        List<Set<Integer>> ranked = List.of(Set.of(9), Set.of(9), Set.of(9, 10));
        assertThat(Ranking.recallAtK(ranked, Set.of(9, 10, 11), 3)).isCloseTo(2 / 3.0, within(1e-9));
    }

    @Test
    void recallWithFewerResultsThanKUsesAllOfThem() {
        assertThat(Ranking.recallAtK(List.of(Set.of(1)), Set.of(1), 5)).isEqualTo(1.0);
        assertThat(Ranking.recallAtK(List.<Set<Integer>>of(), Set.of(1), 5)).isZero();
    }

    @Test
    void recallWithoutTargetsIsComplete() {
        assertThat(Ranking.recallAtK(List.of(NONE), NONE, 5)).isEqualTo(1.0);
    }

    @Test
    void recallRejectsANonPositiveK() {
        assertThatThrownBy(() -> Ranking.recallAtK(List.of(NONE), Set.of(1), 0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reciprocalRankUsesTheFirstRelevantResult() {
        List<Set<Integer>> ranked = List.of(NONE, NONE, Set.of(7), Set.of(3));
        assertThat(Ranking.reciprocalRank(ranked, Set.of(3, 7))).isCloseTo(1 / 3.0, within(1e-9));
        assertThat(Ranking.firstRelevantRank(ranked, Set.of(3, 7))).isEqualTo(3);
        assertThat(Ranking.reciprocalRank(ranked, Set.of(3))).isEqualTo(0.25);
        assertThat(Ranking.reciprocalRank(List.of(Set.of(1)), Set.of(1))).isEqualTo(1.0);
    }

    @Test
    void reciprocalRankIsZeroWhenNothingRelevantIsFound() {
        assertThat(Ranking.reciprocalRank(List.of(NONE, Set.of(2)), Set.of(1))).isZero();
        assertThat(Ranking.firstRelevantRank(List.of(NONE, Set.of(2)), Set.of(1))).isZero();
    }

    @Test
    void meanReciprocalRankAveragesOverQuestions() {
        // Ranks 1, 2 and not found: (1 + 0.5 + 0) / 3.
        assertThat(Ranking.meanReciprocalRank(List.of(1.0, 0.5, 0.0))).isCloseTo(0.5, within(1e-9));
        assertThat(Ranking.meanReciprocalRank(List.of())).isZero();
    }
}
