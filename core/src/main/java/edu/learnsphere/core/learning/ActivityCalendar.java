package edu.learnsphere.core.learning;

import edu.learnsphere.core.service.DayActivity;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The learner's study habit by calendar day in their time zone (the clock's zone): reviews per day and the
 * streak. Counts every attempt in any course, because a habit is about the learner, not one course.
 * Thread-safe.
 */
public final class ActivityCalendar {

    /** Days per query while walking back through a long streak. */
    static final int STREAK_WINDOW = 60;
    /** Longest streak counted (ten years). */
    static final int MAX_STREAK = 3_660;

    private final StudyQueries queries;
    private final Clock clock;

    public ActivityCalendar(StudyQueries queries, Clock clock) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Today in the learner's time zone. */
    public LocalDate today() {
        return LocalDate.now(clock);
    }

    public ZoneId zone() {
        return clock.getZone();
    }

    /**
     * Consecutive days with at least one review, ending today. A streak that reached yesterday is still
     * running while today has no review yet, so it counts up to yesterday until the day is over.
     */
    public int streakDays() {
        LocalDate today = today();
        int streak = 0;
        LocalDate windowEnd = today;
        boolean first = true;
        while (streak < MAX_STREAK) {
            LocalDate windowStart = windowEnd.minusDays(STREAK_WINDOW - 1L);
            int[] counts = queries.attemptsPerRange(DayRange.days(windowStart, STREAK_WINDOW, zone()));
            for (int i = counts.length - 1; i >= 0; i--) {
                if (counts[i] > 0) {
                    streak++;
                } else if (first && i == counts.length - 1) {
                    // Today without a review yet: the streak may still continue.
                    continue;
                } else {
                    return streak;
                }
            }
            first = false;
            windowEnd = windowStart.minusDays(1);
        }
        return streak;
    }

    /** Reviews on each of the last {@code days} days, oldest first, ending today. */
    public List<DayActivity> lastDays(int days) {
        LocalDate today = today();
        LocalDate first = today.minusDays(days - 1L);
        List<DayRange> ranges = DayRange.days(first, days, zone());
        int[] counts = queries.attemptsPerRange(ranges);
        List<DayActivity> result = new ArrayList<>(days);
        for (int i = 0; i < days; i++) {
            LocalDate date = ranges.get(i).first();
            result.add(new DayActivity(date, counts[i], date.equals(today)));
        }
        return result;
    }

    /** Reviews done today. */
    public int reviewsToday() {
        return queries.attemptsPerRange(DayRange.days(today(), 1, zone()))[0];
    }
}
