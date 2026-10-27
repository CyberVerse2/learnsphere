package edu.learnsphere.core.service;

import java.time.LocalDate;
import java.util.Objects;

/** Reviews done on one day, for the week strip. */
public record DayActivity(LocalDate date, int reviews, boolean today) {

    public DayActivity {
        Objects.requireNonNull(date, "date");
    }
}
