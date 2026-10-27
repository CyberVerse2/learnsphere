package edu.learnsphere.core.learning;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The 21 model weights of FSRS-6 ({@code w0}…{@code w20}). Immutable and thread-safe.
 *
 * <p>The defaults and the allowed range of every weight are copied from the open-spaced-repetition reference
 * implementation py-fsrs 6.3.2, {@code fsrs/scheduler.py} ({@code DEFAULT_PARAMETERS},
 * {@code LOWER_BOUNDS_PARAMETERS}, {@code UPPER_BOUNDS_PARAMETERS}):
 * <a href="https://github.com/open-spaced-repetition/py-fsrs/blob/main/fsrs/scheduler.py">py-fsrs scheduler.py</a>.
 * The same 21 defaults are {@code FSRS6_DEFAULT_PARAMETERS} in fsrs-rs ({@code src/inference_v6.rs}) and are
 * listed in the FSRS-6 section of
 * <a href="https://github.com/open-spaced-repetition/awesome-fsrs/wiki/The-Algorithm">The Algorithm</a>.
 *
 * <ul>
 *   <li>{@code w0}–{@code w3}: initial stability after a first Again, Hard, Good, Easy</li>
 *   <li>{@code w4}, {@code w5}: initial difficulty</li>
 *   <li>{@code w6}: difficulty change per rating; {@code w7}: mean reversion of difficulty</li>
 *   <li>{@code w8}–{@code w10}: stability after a successful review; {@code w15} Hard penalty,
 *       {@code w16} Easy bonus</li>
 *   <li>{@code w11}–{@code w14}: stability after a lapse</li>
 *   <li>{@code w17}–{@code w19}: same-day (short-term) stability</li>
 *   <li>{@code w20}: decay of the forgetting curve</li>
 * </ul>
 */
public final class FsrsParameters {

    /** Number of weights in FSRS-6. */
    public static final int COUNT = 21;

    /** Smallest stability (days) FSRS allows. */
    public static final double STABILITY_MIN = 0.001;

    private static final double INITIAL_STABILITY_MAX = 100.0;

    private static final double[] DEFAULTS = {
        0.212, 1.2931, 2.3065, 8.2956, 6.4133, 0.8334, 3.0194, 0.001, 1.8722, 0.1666, 0.796,
        1.4835, 0.0614, 0.2629, 1.6483, 0.6014, 1.8729, 0.5425, 0.0912, 0.0658, 0.1542,
    };

    private static final double[] LOWER_BOUNDS = {
        STABILITY_MIN, STABILITY_MIN, STABILITY_MIN, STABILITY_MIN, 1.0, 0.001, 0.001, 0.001, 0.0, 0.0, 0.001,
        0.001, 0.001, 0.001, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.1,
    };

    private static final double[] UPPER_BOUNDS = {
        INITIAL_STABILITY_MAX, INITIAL_STABILITY_MAX, INITIAL_STABILITY_MAX, INITIAL_STABILITY_MAX, 10.0, 4.0, 4.0,
        0.75, 4.5, 0.8, 3.5, 5.0, 0.25, 0.9, 4.0, 1.0, 6.0, 2.0, 2.0, 0.8, 0.8,
    };

    /** The published FSRS-6 defaults. */
    public static final FsrsParameters DEFAULT = new FsrsParameters(DEFAULTS.clone());

    private final double[] weights;

    private FsrsParameters(double[] weights) {
        this.weights = weights;
    }

    /**
     * Weights trained for one learner.
     *
     * @throws IllegalArgumentException when there are not exactly {@value #COUNT} weights or one is out of range
     */
    public static FsrsParameters of(double... weights) {
        if (weights == null || weights.length != COUNT) {
            throw new IllegalArgumentException("FSRS-6 needs " + COUNT + " weights, got "
                    + (weights == null ? 0 : weights.length));
        }
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < COUNT; i++) {
            double w = weights[i];
            if (!(w >= LOWER_BOUNDS[i] && w <= UPPER_BOUNDS[i])) {
                problems.add("w" + i + " = " + w + " is outside [" + LOWER_BOUNDS[i] + ", " + UPPER_BOUNDS[i] + "]");
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join("; ", problems));
        }
        return new FsrsParameters(weights.clone());
    }

    /** Weight {@code w<i>}. */
    public double w(int i) {
        return weights[i];
    }

    /** The forgetting curve's exponent, {@code -w20} (negative). */
    public double decay() {
        return -weights[20];
    }

    /** {@code 0.9^(1/decay) - 1}, chosen so that retrievability is exactly 90% when elapsed days equal stability. */
    public double factor() {
        return Math.pow(0.9, 1 / decay()) - 1;
    }

    /** The weights in order. */
    public List<Double> asList() {
        return Arrays.stream(weights).boxed().toList();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof FsrsParameters p && Arrays.equals(weights, p.weights);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(weights);
    }

    @Override
    public String toString() {
        return "FsrsParameters" + Arrays.toString(weights);
    }
}
