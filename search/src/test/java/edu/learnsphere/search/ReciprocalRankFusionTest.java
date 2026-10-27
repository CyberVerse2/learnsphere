package edu.learnsphere.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import edu.learnsphere.search.ReciprocalRankFusion.Fused;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTest {

    @Test
    void sumsReciprocalRanksOfBothRankings() {
        List<Fused> fused = ReciprocalRankFusion.fuse(new int[] {10, 20, 30}, new int[] {30, 40}, 60, 10);

        // 20 and 40 tie on score and best rank; the keyword result comes first.
        assertThat(fused).extracting(Fused::doc).containsExactly(30, 10, 20, 40);
        Fused both = fused.getFirst();
        assertThat(both.keywordRank()).isEqualTo(3);
        assertThat(both.vectorRank()).isEqualTo(1);
        assertThat(both.score()).isCloseTo(1.0 / 63 + 1.0 / 61, within(1e-15));
        assertThat(fused.get(1)).isEqualTo(new Fused(10, 1.0 / 61, 1, null));
        assertThat(fused.get(2)).isEqualTo(new Fused(20, 1.0 / 62, 2, null));
        assertThat(fused.get(3)).isEqualTo(new Fused(40, 1.0 / 62, null, 2));
    }

    @Test
    void breaksTiesByBestRankThenKeywordThenVector() {
        // 1 and 2 tie on score (ranks 1+3 vs 3+1); 5 and 6 tie on rank 2 in different rankings.
        List<Fused> fused = ReciprocalRankFusion.fuse(new int[] {1, 5, 2}, new int[] {2, 6, 1}, 60, 10);

        assertThat(fused).extracting(Fused::doc).containsExactly(1, 2, 5, 6);
    }

    @Test
    void keepsTheLimitAndTheFirstRankOfARepeatedResult() {
        List<Fused> fused = ReciprocalRankFusion.fuse(new int[] {7, 7, 8}, new int[] {}, 60, 1);

        assertThat(fused).containsExactly(new Fused(7, 1.0 / 61, 1, null));
        assertThat(ReciprocalRankFusion.fuse(new int[0], new int[0], 60, 5)).isEmpty();
        assertThatThrownBy(() -> ReciprocalRankFusion.fuse(new int[0], new int[0], 0, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSmallerKWeighsTopRanksMore() {
        Fused topKeyword = ReciprocalRankFusion.fuse(new int[] {1}, new int[0], 1, 1).getFirst();
        assertThat(topKeyword.score()).isEqualTo(0.5);
    }
}
