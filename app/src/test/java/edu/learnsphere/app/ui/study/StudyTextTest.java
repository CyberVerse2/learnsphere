package edu.learnsphere.app.ui.study;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.service.Feedback;
import edu.learnsphere.core.service.ForecastDay;
import edu.learnsphere.core.service.SessionSummary;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudyTextTest {

    private static final long MINUTE = 60_000;
    private static final long HOUR = 60 * MINUTE;
    private static final long DAY = 24 * HOUR;
    /** 09:00 on a Tuesday. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-27T09:00:00Z"), ZoneOffset.UTC);
    private static final long NOW = CLOCK.millis();

    private static Feedback feedback(boolean correct, Rating rating, GradedBy by, boolean disputed) {
        return new Feedback("a1", "i1", correct, correct ? 1 : 0, rating, by, "Answer", "Why", null, List.of(), null,
                NOW + DAY, 1, by == GradedBy.AI, disputed);
    }

    private static SessionSummary summary(int items, int answered, double accuracy, long durationMs) {
        return new SessionSummary("s1", SessionKind.STUDY_NEXT, null, items, answered, (int) Math.round(answered * accuracy),
                accuracy, durationMs, 0, List.of(), null, 3);
    }

    @Test
    void anEmptySessionSaysWhatIsReadyElsewhere() {
        assertThat(StudyText.readyToStudy(1, true)).isEqualTo("1 question is ready in this course.");
        assertThat(StudyText.readyToStudy(12, false)).isEqualTo("12 questions are ready across your courses.");
    }

    @Test
    void ratingButtonsSayHowLongUntilTheCardComesBack() {
        assertThat(StudyText.interval(NOW, NOW + 20_000)).isEqualTo("1 min");
        assertThat(StudyText.interval(NOW, NOW + 10 * MINUTE)).isEqualTo("10 min");
        assertThat(StudyText.interval(NOW, NOW + 5 * HOUR)).isEqualTo("5 hours");
        assertThat(StudyText.interval(NOW, NOW + DAY)).isEqualTo("1 day");
        assertThat(StudyText.interval(NOW, NOW + 4 * DAY)).isEqualTo("4 days");
        assertThat(StudyText.interval(NOW, NOW + 21 * DAY)).isEqualTo("3 weeks");
        assertThat(StudyText.interval(NOW, NOW + 61 * DAY)).isEqualTo("2 months");
        assertThat(StudyText.interval(NOW, NOW + 400 * DAY)).isEqualTo("1 year");
    }

    @Test
    void theNextReviewIsInPlainWords() {
        assertThat(StudyText.comesBack(NOW + 4 * DAY, false, CLOCK)).isEqualTo("You'll see this again in 4 days.");
        assertThat(StudyText.comesBack(NOW + DAY, false, CLOCK)).isEqualTo("You'll see this again tomorrow.");
        assertThat(StudyText.comesBack(NOW + 10 * MINUTE, false, CLOCK)).isEqualTo("You'll see this again in 10 minutes.");
        assertThat(StudyText.comesBack(NOW + 2 * HOUR, false, CLOCK)).isEqualTo("You'll see this again in 2 hours.");
        assertThat(StudyText.comesBack(NOW + 9 * HOUR, false, CLOCK)).isEqualTo("You'll see this again later today.");
        assertThat(StudyText.comesBack(NOW + DAY, true, CLOCK)).isEqualTo("You'll see this again later in this session.");
        assertThat(StudyText.nextReview(null, CLOCK)).isNull();
        assertThat(StudyText.nextReview(NOW + 3 * HOUR, CLOCK)).isEqualTo("Your next review is in 3 hours.");
    }

    @Test
    void verdictsNameWhatHappened() {
        assertThat(StudyText.verdict(ItemType.MCQ, feedback(true, Rating.GOOD, GradedBy.RULE, false))).isEqualTo("Correct");
        assertThat(StudyText.verdict(ItemType.MCQ, feedback(false, Rating.AGAIN, GradedBy.RULE, false)))
                .isEqualTo("Not quite");
        assertThat(StudyText.verdict(ItemType.SHORT_ANSWER, feedback(true, Rating.HARD, GradedBy.AI, false)))
                .isEqualTo("Partly right");
        assertThat(StudyText.verdict(ItemType.SHORT_ANSWER, feedback(true, Rating.GOOD, GradedBy.AI, true)))
                .isEqualTo("Counted as correct");
        assertThat(StudyText.verdict(ItemType.FLASHCARD, feedback(true, Rating.EASY, GradedBy.SELF, false)))
                .isEqualTo("Rated Easy");
        assertThat(StudyText.answerText(ItemType.TRUE_FALSE, "false")).isEqualTo("False");
    }

    @Test
    void theSummaryHeadlineFollowsAccuracy() {
        assertThat(StudyText.headline(summary(10, 10, 0.9, 0))).isEqualTo("Excellent work");
        assertThat(StudyText.headline(summary(10, 10, 0.7, 0))).isEqualTo("Good session");
        assertThat(StudyText.headline(summary(10, 10, 0.4, 0))).isEqualTo("Every review counts");
        assertThat(StudyText.headline(summary(10, 0, 0, 0))).isEqualTo("Session ended");
    }

    @Test
    void theRecapCountsWhatWasAnswered() {
        assertThat(StudyText.recap(summary(17, 17, 0.8, 12 * MINUTE))).isEqualTo("You answered 17 questions in 12 min.");
        assertThat(StudyText.recap(summary(17, 5, 0.8, 4 * MINUTE))).isEqualTo("You answered 5 of 17 questions in 4 min.");
        assertThat(StudyText.recap(summary(5, 0, 0, 0)))
                .isEqualTo("You didn't answer any questions this time. They stay ready for your next session.");
    }

    @Test
    void comingUpReadsTheForecast() {
        LocalDate today = LocalDate.now(CLOCK);
        List<ForecastDay> busy = List.of(new ForecastDay(today, 3), new ForecastDay(today.plusDays(1), 4),
                new ForecastDay(today.plusDays(2), 5), new ForecastDay(today.plusDays(3), 6));
        List<ForecastDay> quietTomorrow = List.of(new ForecastDay(today, 3), new ForecastDay(today.plusDays(1), 0),
                new ForecastDay(today.plusDays(4), 6));
        List<ForecastDay> onlyTomorrow = List.of(new ForecastDay(today, 0), new ForecastDay(today.plusDays(1), 1));

        assertThat(StudyText.comingUp(busy, null, CLOCK)).isEqualTo("4 reviews tomorrow, then 11 more this week.");
        assertThat(StudyText.comingUp(quietTomorrow, null, CLOCK)).isEqualTo("Nothing tomorrow, then 6 reviews later this week.");
        assertThat(StudyText.comingUp(onlyTomorrow, null, CLOCK)).isEqualTo("1 review tomorrow.");
        assertThat(StudyText.comingUp(List.of(), NOW + 10 * DAY, CLOCK)).isEqualTo("Your next review is in 10 days.");
        assertThat(StudyText.comingUp(List.of(), null, CLOCK)).isNull();
    }

    @Test
    void timeOnTheSummaryIsANumberWithItsUnit() {
        assertThat(SummaryView.time(20_000)).isEqualTo(new SummaryView.Time("<1", "min"));
        assertThat(SummaryView.time(12 * MINUTE)).isEqualTo(new SummaryView.Time("12", "min"));
        assertThat(SummaryView.time(90 * MINUTE)).isEqualTo(new SummaryView.Time("1.5", "h"));
        assertThat(SummaryView.time(120 * MINUTE)).isEqualTo(new SummaryView.Time("2", "h"));
    }
}
