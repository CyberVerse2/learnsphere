package edu.learnsphere.pdf;

import java.util.HashMap;
import java.util.Map;

/** Finds the most common font size, weighted by character count, at half-point resolution. */
final class SizeHistogram {

    private final Map<Integer, Integer> weights = new HashMap<>();

    void add(float size, int weight) {
        if (weight <= 0 || !(size > 0) || Float.isInfinite(size)) {
            return;
        }
        weights.merge(Math.round(size * 2), weight, Integer::sum);
    }

    /** The most common size (ties go to the smaller one), or 0 when nothing was added. */
    float mode() {
        int bestKey = 0;
        int bestWeight = 0;
        for (Map.Entry<Integer, Integer> entry : weights.entrySet()) {
            int key = entry.getKey();
            int weight = entry.getValue();
            if (weight > bestWeight || (weight == bestWeight && key < bestKey)) {
                bestKey = key;
                bestWeight = weight;
            }
        }
        return bestKey / 2f;
    }
}
