package edu.learnsphere.core.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * FSRS-6 against the reference implementation py-fsrs 6.3.2: the expected numbers below are the assertions of
 * its {@code tests/test_basic.py} (test_review_card, test_memo_state, test_repeated_correct_reviews and the
 * learning-step tests), which run with the same default parameters.
 */
class FsrsSchedulerTest {

    private static final long MINUTE = 60_000L;
    private static final long DAY = 86_400_000L;
    private static final long T0 = Instant.parse("2022-11-29T12:30:00Z").toEpochMilli();

    private final FsrsScheduler scheduler = FsrsScheduler.defaults();

    private static ReviewState fresh() {
        return ReviewState.newItem("item", T0);
    }

    @Test
    void defaultWeightsAreTheFsrs6Defaults() {
        assertThat(FsrsParameters.DEFAULT.asList()).containsExactly(0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334,
                3.0194, 0.001, 1.8722, 0.1666, 0.796, 1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912,
                0.0658, 0.1542);
        assertThat(scheduler.desiredRetention()).isEqualTo(0.9);
        assertThat(scheduler.learningSteps()).containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(10));
        assertThat(scheduler.relearningSteps()).containsExactly(Duration.ofMinutes(10));
        assertThat(scheduler.maximumInterval()).isEqualTo(36_500);
        assertThat(scheduler.fuzzing()).isFalse();
    }

    @Test
    void intervalsMatchTheReferenceHistory() {
        // py-fsrs test_review_card: Good x6, Again x2, Good x5, each review at the previous due time.
        Rating[] ratings = {Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.AGAIN,
            Rating.AGAIN, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD};
        ReviewState state = fresh();
        long at = T0;
        List<Long> intervals = new ArrayList<>();
        for (Rating rating : ratings) {
            state = scheduler.review(state, rating, at);
            intervals.add(Math.floorDiv(state.dueAt() - state.lastReviewAt(), DAY));
            at = state.dueAt();
        }
        assertThat(intervals).containsExactly(0L, 2L, 11L, 46L, 163L, 498L, 0L, 0L, 2L, 4L, 7L, 12L, 21L);
        assertThat(state.reps()).isEqualTo(13);
        assertThat(state.lapses()).isEqualTo(1);
        assertThat(state.phase()).isEqualTo(ReviewPhase.REVIEW);
        assertThat(state.scheduledDays()).isEqualTo(21);
    }

    @Test
    void memoryStateMatchesTheReference() {
        // py-fsrs test_memo_state: Again, Good, Good, Good, Good, Good after 0, 0, 1, 3, 8 and 21 more days.
        Rating[] ratings = {Rating.AGAIN, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD, Rating.GOOD};
        int[] gaps = {0, 0, 1, 3, 8, 21};
        ReviewState state = fresh();
        long at = T0;
        for (int i = 0; i < ratings.length; i++) {
            at += gaps[i] * DAY;
            state = scheduler.review(state, ratings[i], at);
        }
        assertThat(state.stability()).isCloseTo(53.62691, within(1e-4));
        assertThat(state.difficulty()).isCloseTo(6.3574867, within(1e-4));
    }

    @Test
    void repeatedEasyAnswersBottomOutDifficulty() {
        ReviewState state = fresh();
        for (int i = 0; i < 10; i++) {
            state = scheduler.review(state, Rating.EASY, T0);
        }
        assertThat(state.difficulty()).isEqualTo(1.0);
    }

    @Test
    void firstReviewUsesTheInitialWeights() {
        for (Rating rating : Rating.values()) {
            ReviewState state = scheduler.review(fresh(), rating, T0);
            assertThat(state.stability()).isEqualTo(FsrsParameters.DEFAULT.w(rating.value() - 1));
            double expectedD = 6.4133 - Math.exp(0.8334 * (rating.value() - 1)) + 1;
            assertThat(state.difficulty()).isCloseTo(Math.min(Math.max(expectedD, 1), 10), within(1e-12));
            assertThat(state.reps()).isEqualTo(1);
            assertThat(state.lastReviewAt()).isEqualTo(T0);
        }
    }

    @Test
    void learningStepsFollowTheReference() {
        ReviewState good = scheduler.review(fresh(), Rating.GOOD, T0);
        assertThat(good.phase()).isEqualTo(ReviewPhase.LEARNING);
        assertThat(good.step()).isEqualTo(1);
        assertThat(good.dueAt() - T0).isEqualTo(10 * MINUTE);
        assertThat(good.scheduledDays()).isZero();

        ReviewState again = scheduler.review(fresh(), Rating.AGAIN, T0);
        assertThat(again.phase()).isEqualTo(ReviewPhase.LEARNING);
        assertThat(again.step()).isZero();
        assertThat(again.dueAt() - T0).isEqualTo(MINUTE);

        ReviewState hard = scheduler.review(fresh(), Rating.HARD, T0);
        assertThat(hard.phase()).isEqualTo(ReviewPhase.LEARNING);
        assertThat(hard.step()).isZero();
        assertThat(hard.dueAt() - T0).isEqualTo(330_000L);

        ReviewState easy = scheduler.review(fresh(), Rating.EASY, T0);
        assertThat(easy.phase()).isEqualTo(ReviewPhase.REVIEW);
        assertThat(easy.step()).isZero();
        assertThat(easy.scheduledDays()).isGreaterThanOrEqualTo(1);
        assertThat(easy.dueAt() - T0).isEqualTo(easy.scheduledDays() * DAY);

        ReviewState graduated = scheduler.review(good, Rating.GOOD, good.dueAt());
        assertThat(graduated.phase()).isEqualTo(ReviewPhase.REVIEW);
        assertThat(graduated.dueAt() - good.dueAt()).isGreaterThanOrEqualTo(DAY);
    }

    @Test
    void aLapseGoesToRelearningAndBack() {
        ReviewState state = scheduler.review(fresh(), Rating.GOOD, T0);
        state = scheduler.review(state, Rating.GOOD, state.dueAt());
        state = scheduler.review(state, Rating.GOOD, state.dueAt());
        assertThat(state.phase()).isEqualTo(ReviewPhase.REVIEW);
        double stabilityBefore = state.stability();

        ReviewState lapsed = scheduler.review(state, Rating.AGAIN, state.dueAt());
        assertThat(lapsed.phase()).isEqualTo(ReviewPhase.RELEARNING);
        assertThat(lapsed.step()).isZero();
        assertThat(lapsed.lapses()).isEqualTo(1);
        assertThat(lapsed.dueAt() - lapsed.lastReviewAt()).isEqualTo(10 * MINUTE);
        assertThat(lapsed.stability()).isLessThan(stabilityBefore);

        ReviewState stillLapsed = scheduler.review(lapsed, Rating.AGAIN, lapsed.dueAt());
        assertThat(stillLapsed.phase()).isEqualTo(ReviewPhase.RELEARNING);
        assertThat(stillLapsed.lapses()).as("Again while relearning is not another lapse").isEqualTo(1);
        assertThat(stillLapsed.dueAt() - stillLapsed.lastReviewAt()).isEqualTo(10 * MINUTE);

        ReviewState relearned = scheduler.review(stillLapsed, Rating.GOOD, stillLapsed.dueAt());
        assertThat(relearned.phase()).isEqualTo(ReviewPhase.REVIEW);
        assertThat(relearned.dueAt() - relearned.lastReviewAt()).isGreaterThanOrEqualTo(DAY);
    }

    @Test
    void retrievabilityFollowsThePowerCurve() {
        assertThat(scheduler.retrievability(10, 10)).isCloseTo(0.9, within(1e-12));
        assertThat(scheduler.retrievability(0, 10)).isEqualTo(1.0);
        assertThat(scheduler.retrievability(20, 10)).isBetween(0.8, 0.9);
        assertThat(scheduler.retrievability(5, 0)).isZero();
        double factor = Math.pow(0.9, 1 / -0.1542) - 1;
        assertThat(scheduler.retrievability(3, 7)).isCloseTo(Math.pow(1 + factor * 3 / 7, -0.1542), within(1e-12));

        assertThat(scheduler.retrievability(fresh(), T0 + DAY)).as("never reviewed").isZero();
        ReviewState reviewed = scheduler.review(fresh(), Rating.GOOD, T0);
        assertThat(scheduler.retrievability(reviewed, T0)).isEqualTo(1.0);
        assertThat(scheduler.retrievability(reviewed, T0 + 3 * DAY)).isLessThan(1.0).isGreaterThan(0.0);
    }

    @Test
    void desiredRetentionShortensOrLengthensIntervals() {
        assertThat(scheduler.nextIntervalDays(10)).as("at 90%% the interval equals stability").isEqualTo(10);
        assertThat(scheduler.nextIntervalDays(0.2)).as("at least a day").isEqualTo(1);
        assertThat(scheduler.nextIntervalDays(1e9)).as("capped").isEqualTo(36_500);
        int strict = FsrsScheduler.withRetention(0.97).nextIntervalDays(30);
        int relaxed = FsrsScheduler.withRetention(0.80).nextIntervalDays(30);
        assertThat(strict).isLessThan(30);
        assertThat(relaxed).isGreaterThan(30);
    }

    @Test
    void fuzzSpreadsLongIntervalsWithinTheReferenceRange() {
        FsrsScheduler fuzzy = FsrsScheduler.builder().fuzz(new Random(7)).build();
        for (int i = 0; i < 200; i++) {
            int days = fuzzy.fuzzedDays(50);
            // delta = 1 + 0.15 * 4.5 + 0.1 * 13 + 0.05 * 30 = 4.475 -> [46, 54]
            assertThat(days).isBetween(46, 54);
        }
        assertThat(fuzzy.fuzzedDays(2)).isEqualTo(2);
        assertThat(fuzzy.fuzzing()).isTrue();
    }

    @Test
    void withoutFuzzTheScheduleIsReproducible() {
        ReviewState a = scheduler.review(scheduler.review(fresh(), Rating.GOOD, T0), Rating.GOOD, T0 + 10 * MINUTE);
        ReviewState b = scheduler.review(scheduler.review(fresh(), Rating.GOOD, T0), Rating.GOOD, T0 + 10 * MINUTE);
        assertThat(a).isEqualTo(b);
    }

    @Test
    void sameDayReviewsNeverLowerStabilityOnAPass() {
        double s = 50;
        for (Rating rating : List.of(Rating.HARD, Rating.GOOD, Rating.EASY)) {
            assertThat(scheduler.shortTermStability(s, rating)).isGreaterThanOrEqualTo(s);
        }
        assertThat(scheduler.shortTermStability(s, Rating.AGAIN)).isLessThan(s);
    }

    @Test
    void rejectsWeightsOutOfRange() {
        double[] weights = FsrsParameters.DEFAULT.asList().stream().mapToDouble(Double::doubleValue).toArray();
        assertThat(FsrsParameters.of(weights)).isEqualTo(FsrsParameters.DEFAULT);
        weights[20] = 0.95;
        assertThatThrownBy(() -> FsrsParameters.of(weights)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("w20");
        assertThatThrownBy(() -> FsrsParameters.of(1, 2, 3)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FsrsScheduler.withRetention(1.0)).isInstanceOf(IllegalArgumentException.class);
    }
}
