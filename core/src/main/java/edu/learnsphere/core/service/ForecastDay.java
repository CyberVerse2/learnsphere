package edu.learnsphere.core.service;

import java.time.LocalDate;
import java.util.Objects;

/** Reviews falling due on one day (the first day includes everything already overdue). */
public record ForecastDay(LocalDate date, int dueCount) {

    public ForecastDay {
        Objects.requireNonNull(date, "date");
    }
}
