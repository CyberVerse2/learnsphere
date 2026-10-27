package edu.learnsphere.app.ui.components;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import org.junit.jupiter.api.Test;

class FormatsTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/London");
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 27, 15, 0, 0, 0, ZONE);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(), ZONE);

    @Test
    void writesPercentagesCountsAndPages() {
        assertThat(Formats.percent(0.623)).isEqualTo("62%");
        assertThat(Formats.percent(1.4)).isEqualTo("100%");
        assertThat(Formats.count(1, "question", "questions")).isEqualTo("1 question");
        assertThat(Formats.count(1240, "question", "questions")).isEqualTo("1,240 questions");
        assertThat(Formats.pages(12, 12)).isEqualTo("p. 12");
        assertThat(Formats.pages(12, 13)).isEqualTo("pp. 12–13");
    }

    @Test
    void writesStudyTimes() {
        assertThat(Formats.duration(20_000)).isEqualTo("under a minute");
        assertThat(Formats.duration(45 * 60_000)).isEqualTo("45 min");
        assertThat(Formats.duration(80 * 60_000)).isEqualTo("1 h 20 min");
        assertThat(Formats.hours(6 * 3_600_000 + 30 * 60_000)).isEqualTo("6.5");
        assertThat(Formats.hours(19 * 3_600_000)).isEqualTo("19");
    }

    @Test
    void saysWhenSomethingHappened() {
        long now = NOW.toInstant().toEpochMilli();
        assertThat(Formats.ago(now - 20_000, CLOCK)).isEqualTo("just now");
        assertThat(Formats.ago(now - 5 * 60_000, CLOCK)).isEqualTo("5 min ago");
        assertThat(Formats.ago(now - 3_600_000, CLOCK)).isEqualTo("an hour ago");
        assertThat(Formats.ago(NOW.minusDays(1).toInstant().toEpochMilli(), CLOCK)).isEqualTo("yesterday");
        assertThat(Formats.ago(NOW.minusDays(3).toInstant().toEpochMilli(), CLOCK)).isEqualTo("3 days ago");
        assertThat(Formats.ago(NOW.minusDays(60).toInstant().toEpochMilli(), CLOCK)).isEqualTo("28 Aug");
    }

    @Test
    void saysWhenAReviewComesBack() {
        assertThat(Formats.nextReview(NOW.plusHours(2).toInstant().toEpochMilli(), CLOCK))
                .isEqualTo("You'll see this again later today");
        assertThat(Formats.inTime(NOW.plusDays(1).toInstant().toEpochMilli(), CLOCK)).isEqualTo("tomorrow");
        assertThat(Formats.inTime(NOW.plusDays(4).toInstant().toEpochMilli(), CLOCK)).isEqualTo("in 4 days");
        assertThat(Formats.inTime(NOW.plusDays(21).toInstant().toEpochMilli(), CLOCK)).isEqualTo("in 3 weeks");
        assertThat(Formats.inTime(NOW.plusDays(31).toInstant().toEpochMilli(), CLOCK)).isEqualTo("in 4 weeks");
        assertThat(Formats.inTime(NOW.plusDays(95).toInstant().toEpochMilli(), CLOCK)).isEqualTo("in 3 months");
    }

    @Test
    void namesDays() {
        LocalDate today = LocalDate.of(2026, 10, 27);
        assertThat(Formats.longDate(today)).isEqualTo("Tuesday 27 October");
        assertThat(Formats.weekdayInitial(today)).isEqualTo("T");
        assertThat(Formats.day(today, today)).isEqualTo("Today");
        assertThat(Formats.day(today.plusDays(1), today)).isEqualTo("Tomorrow");
    }

    @Test
    void shortensAtAWordBoundary() {
        assertThat(Formats.shorten("A context switch saves the state of the running process", 30))
                .isEqualTo("A context switch saves the…");
        assertThat(Formats.shorten("  short   text ", 30)).isEqualTo("short text");
    }
}
