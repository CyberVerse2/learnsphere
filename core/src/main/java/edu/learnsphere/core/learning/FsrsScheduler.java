package edu.learnsphere.core.learning;

import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * The FSRS-6 spaced-repetition scheduler: turns an item's {@link ReviewState} and a {@link Rating} into its
 * next state and due time. Pure computation, immutable and thread-safe (with fuzzing on, as thread-safe as the
 * random generator it was given).
 *
 * <p>The formulas and the state machine follow the open-spaced-repetition reference implementation py-fsrs
 * 6.3.2 ({@code Scheduler.review_card} in
 * <a href="https://github.com/open-spaced-repetition/py-fsrs/blob/main/fsrs/scheduler.py">fsrs/scheduler.py</a>),
 * checked against the FSRS-6 section of
 * <a href="https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm">The Algorithm</a> and
 * fsrs-rs {@code src/model_v6.rs}:
 *
 * <pre>
 * R(t, S)        = (1 + factor · t / S)^decay,  decay = -w20,  factor = 0.9^(1/decay) - 1
 * interval(S)    = round(S / factor · (r^(1/decay) - 1)), at least 1 day, at most the maximum interval
 * S0(G)          = w[G-1]
 * D0(G)          = clamp(w4 - e^(w5·(G-1)) + 1, 1, 10)
 * D'(D, G)       = clamp(w7 · D0(Easy) + (1 - w7) · (D + (10 - D) · (-w6 · (G - 3)) / 9), 1, 10)
 * S'recall       = S · (1 + e^w8 · (11 - D) · S^-w9 · (e^((1 - R) · w10) - 1) · hardPenalty · easyBonus)
 * S'forget       = min(w11 · D^-w12 · ((S + 1)^w13 - 1) · e^((1 - R) · w14),  S / e^(w17 · w18))
 * S'same-day     = S · e^(w17 · (G - 3 + w18)) · S^-w19, never below S for Hard, Good and Easy
 * </pre>
 *
 * Phases: a {@link ReviewPhase#NEW new} item behaves like py-fsrs's fresh card (learning, step 0, no memory
 * state). Learning and relearning walk short steps (1 and 10 minutes, then 10 minutes, the reference defaults);
 * Again in review is a lapse and moves to relearning. Reviews less than a whole day after the previous one use
 * the same-day stability formula; elapsed days are counted in whole days, as the reference does.
 *
 * <p>Fuzzing (a small random spread of review intervals of 3 days or more) is off unless a random generator is
 * given, so schedules are reproducible.
 */
public final class FsrsScheduler {

    /** Smallest difficulty. */
    public static final double MIN_DIFFICULTY = 1.0;
    /** Largest difficulty. */
    public static final double MAX_DIFFICULTY = 10.0;
    /** Learning steps of the reference implementation: 1 minute, then 10 minutes. */
    public static final List<Duration> DEFAULT_LEARNING_STEPS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(10));
    /** Relearning steps of the reference implementation: 10 minutes. */
    public static final List<Duration> DEFAULT_RELEARNING_STEPS = List.of(Duration.ofMinutes(10));
    /** Longest review interval, in days (100 years, as the reference). */
    public static final int DEFAULT_MAXIMUM_INTERVAL = 36_500;
    /** Desired retention of the reference implementation. */
    public static final double DEFAULT_DESIRED_RETENTION = 0.9;

    static final long DAY_MS = 86_400_000L;

    private final FsrsParameters parameters;
    private final double desiredRetention;
    private final List<Duration> learningSteps;
    private final List<Duration> relearningSteps;
    private final int maximumInterval;
    private final RandomGenerator fuzz;
    private final double decay;
    private final double factor;

    private FsrsScheduler(Builder b) {
        this.parameters = b.parameters;
        this.desiredRetention = b.desiredRetention;
        this.learningSteps = List.copyOf(b.learningSteps);
        this.relearningSteps = List.copyOf(b.relearningSteps);
        this.maximumInterval = b.maximumInterval;
        this.fuzz = b.fuzz;
        this.decay = parameters.decay();
        this.factor = parameters.factor();
    }

    /** Default parameters, steps and maximum interval, no fuzzing, with this desired retention. */
    public static FsrsScheduler withRetention(double desiredRetention) {
        return builder().desiredRetention(desiredRetention).build();
    }

    /** The reference defaults (retention 0.9, no fuzzing). */
    public static FsrsScheduler defaults() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public FsrsParameters parameters() {
        return parameters;
    }

    public double desiredRetention() {
        return desiredRetention;
    }

    public List<Duration> learningSteps() {
        return learningSteps;
    }

    public List<Duration> relearningSteps() {
        return relearningSteps;
    }

    public int maximumInterval() {
        return maximumInterval;
    }

    /** True when review intervals are randomly spread. */
    public boolean fuzzing() {
        return fuzz != null;
    }

    // ---- reviewing ----------------------------------------------------------------------------

    /**
     * The item's state after a review rated {@code rating} at {@code reviewedAt}: new stability, difficulty,
     * phase, step, due time, interval and counters. {@code state} itself is not changed.
     */
    public ReviewState review(ReviewState state, Rating rating, long reviewedAt) {
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(rating, "rating");
        Long last = state.lastReviewAt();
        boolean sameDay = last != null && Math.floorDiv(reviewedAt - last, DAY_MS) < 1;
        boolean fresh = state.phase() == ReviewPhase.NEW || state.stability() <= 0 || state.difficulty() <= 0;

        double stability;
        double difficulty;
        if (fresh) {
            stability = initialStability(rating);
            difficulty = initialDifficulty(rating);
        } else {
            stability = sameDay
                    ? shortTermStability(state.stability(), rating)
                    : nextStability(state.difficulty(), state.stability(), wholeDayRetrievability(state, reviewedAt), rating);
            difficulty = nextDifficulty(state.difficulty(), rating);
        }

        Step next = switch (state.phase()) {
            case NEW -> stepThrough(learningSteps, 0, ReviewPhase.LEARNING, rating, stability);
            case LEARNING -> stepThrough(learningSteps, state.step(), ReviewPhase.LEARNING, rating, stability);
            case RELEARNING -> stepThrough(relearningSteps, state.step(), ReviewPhase.RELEARNING, rating, stability);
            case REVIEW -> reviewStep(rating, stability);
        };

        long intervalMs = next.intervalMs();
        int scheduledDays = 0;
        if (next.phase() == ReviewPhase.REVIEW) {
            scheduledDays = fuzz == null ? next.days() : fuzzedDays(next.days());
            intervalMs = scheduledDays * DAY_MS;
        }
        int lapses = state.lapses() + (state.phase() == ReviewPhase.REVIEW && rating == Rating.AGAIN ? 1 : 0);
        return new ReviewState(state.itemId(), next.phase(), stability, difficulty, reviewedAt + intervalMs, reviewedAt,
                state.reps() + 1, lapses, scheduledDays, next.step(), state.createdAt(), reviewedAt);
    }

    /** Where an item goes next: its phase, step, and either a short interval or a number of days. */
    private record Step(ReviewPhase phase, int step, long intervalMs, int days) {

        static Step shortStep(ReviewPhase phase, int step, long intervalMs) {
            return new Step(phase, step, intervalMs, 0);
        }

        static Step review(int days) {
            return new Step(ReviewPhase.REVIEW, 0, days * DAY_MS, days);
        }
    }

    private Step stepThrough(List<Duration> steps, int step, ReviewPhase phase, Rating rating, double stability) {
        // An item scheduled with more steps than these graduates on any passing rating.
        if (steps.isEmpty() || (step >= steps.size() && rating != Rating.AGAIN)) {
            return Step.review(nextIntervalDays(stability));
        }
        return switch (rating) {
            case AGAIN -> Step.shortStep(phase, 0, steps.getFirst().toMillis());
            case HARD -> {
                long ms;
                if (step == 0 && steps.size() == 1) {
                    ms = steps.getFirst().toMillis() * 3 / 2;
                } else if (step == 0) {
                    ms = (steps.get(0).toMillis() + steps.get(1).toMillis()) / 2;
                } else {
                    ms = steps.get(Math.min(step, steps.size() - 1)).toMillis();
                }
                yield Step.shortStep(phase, step, ms);
            }
            case GOOD -> step + 1 >= steps.size()
                    ? Step.review(nextIntervalDays(stability))
                    : Step.shortStep(phase, step + 1, steps.get(step + 1).toMillis());
            case EASY -> Step.review(nextIntervalDays(stability));
        };
    }

    private Step reviewStep(Rating rating, double stability) {
        if (rating == Rating.AGAIN && !relearningSteps.isEmpty()) {
            return Step.shortStep(ReviewPhase.RELEARNING, 0, relearningSteps.getFirst().toMillis());
        }
        return Step.review(nextIntervalDays(stability));
    }

    // ---- memory model -------------------------------------------------------------------------

    /**
     * Probability of recalling an item {@code elapsedDays} after its last review given its stability.
     * Exactly 0.9 when {@code elapsedDays == stability}.
     */
    public double retrievability(double elapsedDays, double stability) {
        if (stability <= 0) {
            return 0;
        }
        return Math.pow(1 + factor * Math.max(0, elapsedDays) / stability, decay);
    }

    /**
     * Current probability of recalling an item at {@code at}, with elapsed time in fractional days (for
     * ordering and mastery). 0 for an item that was never reviewed.
     */
    public double retrievability(ReviewState state, long at) {
        if (state == null || state.phase() == ReviewPhase.NEW || state.lastReviewAt() == null || state.stability() <= 0) {
            return 0;
        }
        return retrievability((double) (at - state.lastReviewAt()) / DAY_MS, state.stability());
    }

    private double wholeDayRetrievability(ReviewState state, long at) {
        if (state.lastReviewAt() == null) {
            return 0;
        }
        return retrievability(Math.max(0, Math.floorDiv(at - state.lastReviewAt(), DAY_MS)), state.stability());
    }

    /** Days until retrievability falls to the desired retention, rounded, between 1 and the maximum interval. */
    public int nextIntervalDays(double stability) {
        double days = stability / factor * (Math.pow(desiredRetention, 1 / decay) - 1);
        // Math.rint rounds halves to even, like Python's round() in the reference.
        long rounded = (long) Math.rint(days);
        return (int) Math.min(Math.max(rounded, 1), maximumInterval);
    }

    /** Stability after the first review. */
    public double initialStability(Rating rating) {
        return Math.max(parameters.w(rating.value() - 1), FsrsParameters.STABILITY_MIN);
    }

    /** Difficulty after the first review, clamped to 1–10. */
    public double initialDifficulty(Rating rating) {
        return clampDifficulty(rawInitialDifficulty(rating.value()));
    }

    private double rawInitialDifficulty(int grade) {
        return parameters.w(4) - Math.exp(parameters.w(5) * (grade - 1)) + 1;
    }

    /** Difficulty after a review: linear damping towards 10, then mean reversion towards the Easy default. */
    public double nextDifficulty(double difficulty, Rating rating) {
        double delta = -parameters.w(6) * (rating.value() - 3);
        double damped = difficulty + (10.0 - difficulty) * delta / 9.0;
        double reverted = parameters.w(7) * rawInitialDifficulty(Rating.EASY.value()) + (1 - parameters.w(7)) * damped;
        return clampDifficulty(reverted);
    }

    /** Stability after a review on the same day as the previous one. */
    public double shortTermStability(double stability, Rating rating) {
        double increase = Math.exp(parameters.w(17) * (rating.value() - 3 + parameters.w(18)))
                * Math.pow(stability, -parameters.w(19));
        if (rating.isPass()) {
            increase = Math.max(increase, 1.0);
        }
        return clampStability(stability * increase);
    }

    /** Stability after a review a day or more after the previous one, at retrievability {@code r}. */
    public double nextStability(double difficulty, double stability, double r, Rating rating) {
        double next = rating == Rating.AGAIN
                ? nextForgetStability(difficulty, stability, r)
                : nextRecallStability(difficulty, stability, r, rating);
        return clampStability(next);
    }

    double nextRecallStability(double difficulty, double stability, double r, Rating rating) {
        double hardPenalty = rating == Rating.HARD ? parameters.w(15) : 1;
        double easyBonus = rating == Rating.EASY ? parameters.w(16) : 1;
        return stability * (1 + Math.exp(parameters.w(8)) * (11 - difficulty) * Math.pow(stability, -parameters.w(9))
                * (Math.exp((1 - r) * parameters.w(10)) - 1) * hardPenalty * easyBonus);
    }

    double nextForgetStability(double difficulty, double stability, double r) {
        double longTerm = parameters.w(11) * Math.pow(difficulty, -parameters.w(12))
                * (Math.pow(stability + 1, parameters.w(13)) - 1) * Math.exp((1 - r) * parameters.w(14));
        double shortTerm = stability / Math.exp(parameters.w(17) * parameters.w(18));
        return Math.min(longTerm, shortTerm);
    }

    private static double clampDifficulty(double d) {
        return Math.min(Math.max(d, MIN_DIFFICULTY), MAX_DIFFICULTY);
    }

    private static double clampStability(double s) {
        return Math.max(s, FsrsParameters.STABILITY_MIN);
    }

    /**
     * Spreads an interval of 3 days or more over a small range around it (the reference's fuzz ranges), so
     * items learned together do not all fall due on the same day.
     */
    int fuzzedDays(int days) {
        if (days < 2.5) {
            return days;
        }
        double delta = 1.0;
        delta += 0.15 * Math.max(Math.min(days, 7.0) - 2.5, 0.0);
        delta += 0.10 * Math.max(Math.min(days, 20.0) - 7.0, 0.0);
        delta += 0.05 * Math.max(days - 20.0, 0.0);
        int min = (int) Math.rint(days - delta);
        int max = (int) Math.rint(days + delta);
        min = Math.max(2, min);
        max = Math.min(max, maximumInterval);
        min = Math.min(min, max);
        int fuzzed = (int) Math.floor(fuzz.nextDouble() * (max - min + 1) + min);
        return Math.min(Math.min(fuzzed, max), maximumInterval);
    }

    /** Builds a scheduler; every setting starts at the reference default. */
    public static final class Builder {

        private FsrsParameters parameters = FsrsParameters.DEFAULT;
        private double desiredRetention = DEFAULT_DESIRED_RETENTION;
        private List<Duration> learningSteps = DEFAULT_LEARNING_STEPS;
        private List<Duration> relearningSteps = DEFAULT_RELEARNING_STEPS;
        private int maximumInterval = DEFAULT_MAXIMUM_INTERVAL;
        private RandomGenerator fuzz;

        private Builder() {
        }

        public Builder parameters(FsrsParameters value) {
            this.parameters = Objects.requireNonNull(value, "parameters");
            return this;
        }

        /** @throws IllegalArgumentException outside (0, 1) */
        public Builder desiredRetention(double value) {
            if (!(value > 0 && value < 1)) {
                throw new IllegalArgumentException("desired retention must be between 0 and 1: " + value);
            }
            this.desiredRetention = value;
            return this;
        }

        public Builder learningSteps(List<Duration> value) {
            this.learningSteps = checkSteps(value);
            return this;
        }

        public Builder relearningSteps(List<Duration> value) {
            this.relearningSteps = checkSteps(value);
            return this;
        }

        /** @throws IllegalArgumentException below 1 */
        public Builder maximumInterval(int days) {
            if (days < 1) {
                throw new IllegalArgumentException("maximum interval must be >= 1 day: " + days);
            }
            this.maximumInterval = days;
            return this;
        }

        /** Spreads review intervals randomly with {@code random}; null (the default) turns fuzzing off. */
        public Builder fuzz(RandomGenerator random) {
            this.fuzz = random;
            return this;
        }

        public FsrsScheduler build() {
            return new FsrsScheduler(this);
        }

        private static List<Duration> checkSteps(List<Duration> steps) {
            Objects.requireNonNull(steps, "steps");
            for (Duration step : steps) {
                if (step == null || step.isNegative() || step.isZero()) {
                    throw new IllegalArgumentException("steps must be positive: " + steps);
                }
            }
            return List.copyOf(steps);
        }
    }
}
