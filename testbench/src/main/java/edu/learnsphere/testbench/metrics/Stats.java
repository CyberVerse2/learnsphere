package edu.learnsphere.testbench.metrics;

import java.util.Collection;
import java.util.List;

/** Summary statistics over measurements. Empty input gives 0 rather than failing, so a report can still be written. */
public final class Stats {

    private Stats() {
    }

    /** Arithmetic mean. */
    public static double mean(Collection<? extends Number> values) {
        return values.stream().mapToDouble(Number::doubleValue).average().orElse(0);
    }

    /** Median (mean of the two middle values for an even count). */
    public static double median(Collection<? extends Number> values) {
        return percentile(values, 50);
    }

    /**
     * Percentile by linear interpolation between closest ranks (the method spreadsheets call PERCENTILE.INC).
     *
     * @param percent 0–100
     */
    public static double percentile(Collection<? extends Number> values, double percent) {
        if (percent < 0 || percent > 100) {
            throw new IllegalArgumentException("percent must be 0-100: " + percent);
        }
        List<Double> sorted = values.stream().map(Number::doubleValue).sorted().toList();
        if (sorted.isEmpty()) {
            return 0;
        }
        double position = percent / 100 * (sorted.size() - 1);
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        double fraction = position - lower;
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * fraction;
    }

    /** Largest value. */
    public static double max(Collection<? extends Number> values) {
        return values.stream().mapToDouble(Number::doubleValue).max().orElse(0);
    }

    /** Sample standard deviation (n - 1 in the denominator); 0 for fewer than two values. */
    public static double standardDeviation(Collection<? extends Number> values) {
        if (values.size() < 2) {
            return 0;
        }
        double mean = mean(values);
        double sum = values.stream().mapToDouble(v -> (v.doubleValue() - mean) * (v.doubleValue() - mean)).sum();
        return Math.sqrt(sum / (values.size() - 1));
    }

    /** {@code part / whole}, or 0 when {@code whole} is 0. */
    public static double share(long part, long whole) {
        return whole == 0 ? 0 : part / (double) whole;
    }
}
