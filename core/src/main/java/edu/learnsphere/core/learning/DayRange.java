package edu.learnsphere.core.learning;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A stretch of local calendar time in epoch millis, {@code [start, end)}: one day or one week in the
 * learner's time zone. Building ranges from the zone (instead of fixed 24-hour blocks) keeps days right
 * across daylight-saving changes.
 *
 * @param first the first day of the range
 */
public record DayRange(LocalDate first, long start, long end) {

    public DayRange {
        Objects.requireNonNull(first, "first");
        if (end < start) {
            throw new IllegalArgumentException("end before start");
        }
    }

    /** True when {@code time} falls inside. */
    public boolean contains(long time) {
        return time >= start && time < end;
    }

    /** The start of {@code date} in {@code zone}, in epoch millis. */
    public static long startOf(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** {@code count} consecutive days starting with {@code first}. */
    public static List<DayRange> days(LocalDate first, int count, ZoneId zone) {
        List<DayRange> days = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LocalDate date = first.plusDays(i);
            days.add(new DayRange(date, startOf(date, zone), startOf(date.plusDays(1), zone)));
        }
        return days;
    }

    /** {@code count} consecutive weeks (Monday to Sunday), the last one containing {@code today}. */
    public static List<DayRange> weeksEndingWith(LocalDate today, int count, ZoneId zone) {
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate first = monday.minusWeeks(count - 1L);
        List<DayRange> weeks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LocalDate start = first.plusWeeks(i);
            weeks.add(new DayRange(start, startOf(start, zone), startOf(start.plusWeeks(1), zone)));
        }
        return weeks;
    }
}
