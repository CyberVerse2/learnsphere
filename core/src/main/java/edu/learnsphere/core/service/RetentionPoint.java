package edu.learnsphere.core.service;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Share of reviews recalled in one week, for the retention trend line.
 *
 * @param weekStart first day of the week
 * @param retention reviews rated Hard or better / reviews, 0–1
 * @param reviews   reviews that week
 */
public record RetentionPoint(LocalDate weekStart, double retention, int reviews) {

    public RetentionPoint {
        Objects.requireNonNull(weekStart, "weekStart");
    }
}
