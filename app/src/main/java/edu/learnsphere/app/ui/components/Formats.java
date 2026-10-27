package edu.learnsphere.app.ui.components;

import edu.learnsphere.core.model.Citation;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Numbers, dates and times in plain words, the way the interface says them ("in 4 days", "p. 12",
 * "1 h 20 min"). Every screen formats through here so the same thing always reads the same way.
 */
public final class Formats {

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);
    private static final DateTimeFormatter LONG_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);

    private Formats() {
    }

    /** A fraction 0–1 as a whole percentage, e.g. {@code 0.623 → "62%"}. */
    public static String percent(double fraction) {
        return Math.round(Math.max(0, Math.min(1, fraction)) * 100) + "%";
    }

    /** {@code "1 question"}, {@code "3 questions"}. */
    public static String count(long n, String singular, String plural) {
        return String.format(Locale.ENGLISH, "%,d", n) + " " + (n == 1 ? singular : plural);
    }

    /** A whole number with thousands separators, e.g. {@code "1,240"}. */
    public static String number(long n) {
        return String.format(Locale.ENGLISH, "%,d", n);
    }

    /** The page or pages a citation points at: {@code "p. 12"} or {@code "pp. 12–13"}. */
    public static String pages(Citation citation) {
        return pages(citation.pageStart(), citation.pageEnd());
    }

    /** {@code "p. 12"} or {@code "pp. 12–13"}. */
    public static String pages(int start, int end) {
        return end > start ? "pp. " + start + "–" + end : "p. " + start;
    }

    /** A study time, e.g. {@code "45 min"}, {@code "1 h 20 min"}, {@code "under a minute"}. */
    public static String duration(long millis) {
        long minutes = Math.round(millis / 60_000.0);
        if (minutes < 1) {
            return "under a minute";
        }
        if (minutes < 60) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        long rest = minutes % 60;
        return rest == 0 ? hours + " h" : hours + " h " + rest + " min";
    }

    /** Hours with one decimal when under ten, e.g. {@code "6.5"}, {@code "19"}. */
    public static String hours(long millis) {
        double hours = millis / 3_600_000.0;
        if (hours < 10) {
            double rounded = Math.round(hours * 10) / 10.0;
            return rounded == Math.floor(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
        }
        return String.valueOf(Math.round(hours));
    }

    /** The full date as a greeting line shows it, e.g. {@code "Tuesday 27 October"}. */
    public static String longDate(LocalDate date) {
        return date.format(LONG_DATE);
    }

    /** {@code "Today"}, {@code "Tomorrow"}, {@code "Yesterday"}, the weekday within a week, else {@code "12 Mar"}. */
    public static String day(LocalDate date, LocalDate today) {
        long days = ChronoUnit.DAYS.between(today, date);
        if (days == 0) {
            return "Today";
        }
        if (days == 1) {
            return "Tomorrow";
        }
        if (days == -1) {
            return "Yesterday";
        }
        if (Math.abs(days) < 7) {
            return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        }
        return date.format(DAY_MONTH);
    }

    /** One letter for a weekday column, e.g. {@code "M"}. */
    public static String weekdayInitial(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.NARROW, Locale.ENGLISH);
    }

    /** Three letters for a weekday, e.g. {@code "Mon"}. */
    public static String weekdayShort(LocalDate date) {
        return date.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    /** How long ago something happened: {@code "just now"}, {@code "5 min ago"}, {@code "yesterday"}, {@code "12 Mar"}. */
    public static String ago(long then, Clock clock) {
        long now = clock.millis();
        Duration elapsed = Duration.ofMillis(Math.max(0, now - then));
        if (elapsed.toMinutes() < 1) {
            return "just now";
        }
        if (elapsed.toMinutes() < 60) {
            return elapsed.toMinutes() + " min ago";
        }
        ZoneId zone = clock.getZone();
        LocalDate thenDay = Instant.ofEpochMilli(then).atZone(zone).toLocalDate();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long days = ChronoUnit.DAYS.between(thenDay, today);
        if (days == 0) {
            long hours = elapsed.toHours();
            return hours == 1 ? "an hour ago" : hours + " hours ago";
        }
        if (days == 1) {
            return "yesterday";
        }
        if (days < 7) {
            return days + " days ago";
        }
        return thenDay.format(DAY_MONTH);
    }

    /** When something comes back: {@code "later today"}, {@code "tomorrow"}, {@code "in 4 days"}, {@code "in 3 weeks"}. */
    public static String inTime(long at, Clock clock) {
        ZoneId zone = clock.getZone();
        LocalDate day = Instant.ofEpochMilli(at).atZone(zone).toLocalDate();
        LocalDate today = LocalDate.now(clock);
        long days = ChronoUnit.DAYS.between(today, day);
        if (days <= 0) {
            return "later today";
        }
        if (days == 1) {
            return "tomorrow";
        }
        if (days < 14) {
            return "in " + days + " days";
        }
        if (days < 60) {
            return "in " + Math.round(days / 7.0) + " weeks";
        }
        long months = Math.round(days / 30.0);
        return months == 1 ? "in a month" : "in " + months + " months";
    }

    /** The line shown after answering, e.g. {@code "You'll see this again in 4 days"}. */
    public static String nextReview(long dueAt, Clock clock) {
        return "You'll see this again " + inTime(dueAt, clock);
    }

    /** Shortens {@code text} to at most {@code max} characters at a word boundary, ending with an ellipsis. */
    public static String shorten(String text, int max) {
        String s = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        if (s.length() <= max) {
            return s;
        }
        int cut = s.lastIndexOf(' ', max - 1);
        if (cut < max / 2) {
            cut = max - 1;
        }
        return s.substring(0, cut).stripTrailing() + "…";
    }
}
