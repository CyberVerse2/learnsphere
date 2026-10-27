package edu.learnsphere.app.ui.progress;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.ForecastDay;
import edu.learnsphere.core.service.ItemStats;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The sentences the Progress screen writes from the numbers, and the order of a creator's questions. */
class ProgressWordingTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

    @Test
    void paceIsGivenInSecondsOrMinutesAnAnswer() {
        assertThat(Ledger.pace(97 * 26_000L, 97)).isEqualTo("About 26 seconds an answer");
        assertThat(Ledger.pace(1_000, 1)).isEqualTo("About 1 second an answer");
        assertThat(Ledger.pace(3 * 180_000L, 3)).isEqualTo("About 3 minutes an answer");
        assertThat(Ledger.pace(0, 0)).isEqualTo("Time spent answering");
    }

    @Test
    void theForecastSummaryNamesTheBusiestDay() {
        assertThat(TrendSections.forecastSummary(forecast(0, 0, 0))).isEqualTo("Nothing due in the next 3 days.");
        assertThat(TrendSections.forecastSummary(forecast(7, 3, 0))).isEqualTo("10 reviews in the next 3 days. Today is the busiest day.");
        assertThat(TrendSections.forecastSummary(forecast(1, 5, 2))).isEqualTo("8 reviews in the next 3 days. The busiest day is tomorrow.");
        assertThat(TrendSections.forecastSummary(forecast(0, 0, 0, 4, 1))).isEqualTo("5 reviews in the next 5 days. The busiest day is Friday.");
        assertThat(TrendSections.forecastSummary(forecast(0, 0, 0, 0, 0, 0, 0, 0, 0, 6, 1)))
                .isEqualTo("7 reviews in the next 11 days. The busiest day is Thursday 5 November.");
        assertThat(TrendSections.forecastSummary(forecast(0, 1))).as("one busy day is not news")
                .isEqualTo("1 review in the next 2 days.");
    }

    @Test
    void theCourseLineSaysWhatTheCourseHoldsAndWhatIsDue() {
        assertThat(ProgressPage.courseLine(summary(true, 4, 48, 12))).isEqualTo("A course you made · 4 modules · 48 questions · 12 due now");
        assertThat(ProgressPage.courseLine(summary(false, 1, 1, 0))).isEqualTo("1 module · 1 question · Nothing due now");
        assertThat(ProgressPage.courseLine(null)).isEmpty();
    }

    @Test
    void aCreatorsQuestionsComeHardestFirstPreferringTheClasssAnswers() {
        ItemStats mine = stats("Mine only", 4, 1, 0, null);
        ItemStats classHard = stats("The class finds it hard", 10, 9, 6, 0.4);
        ItemStats unanswered = stats("Not answered", 0, 0, 0, null);
        ItemStats easy = stats("Easy", 5, 5, 0, null);

        List<CreatorSection.Answered> answered = CreatorSection.answered(List.of(easy, unanswered, mine, classHard));

        assertThat(answered).extracting(a -> a.stats().prompt())
                .containsExactly("Mine only", "The class finds it hard", "Easy");
        assertThat(answered.get(1).fromClass()).isTrue();
        assertThat(answered.get(1).accuracy()).isEqualTo(0.4);
        assertThat(answered.get(0).fromClass()).isFalse();
    }

    @Test
    void eachQuestionSaysWhoAnsweredIt() {
        assertThat(CreatorSection.answeredLine(stats("q", 8, 3, 0, null))).isEqualTo("You: 3 of 8 right");
        assertThat(CreatorSection.answeredLine(stats("q", 8, 3, 6, 0.52))).isEqualTo("You: 3 of 8 right · Class: 52% right, 6 learners");
        assertThat(CreatorSection.answeredLine(stats("q", 0, 0, 1, 0.5))).isEqualTo("Class: 50% right, 1 learner");
        assertThat(CreatorSection.answeredLine(stats("q", 0, 0, 0, null))).isEqualTo("Not answered yet");
        assertThat(CreatorSection.typeName(ItemType.CLOZE)).isEqualTo("Fill in the blank");
    }

    private static List<ForecastDay> forecast(int... due) {
        List<ForecastDay> days = new ArrayList<>();
        for (int i = 0; i < due.length; i++) {
            days.add(new ForecastDay(TODAY.plusDays(i), due[i]));
        }
        return days;
    }

    private static CourseSummary summary(boolean createdHere, int modules, int items, int due) {
        return new CourseSummary("c1", "Biology", "", "", 210, CourseOrigin.LOCAL, CourseStatus.DRAFT, Visibility.PRIVATE, 0,
                createdHere, true, modules, 0, 0, 0, items, 0, 0, due, 0, 0.5, null, 0);
    }

    private static ItemStats stats(String prompt, int attempts, int correct, int classLearners, Double classAccuracy) {
        return new ItemStats(prompt, ItemType.MCQ, prompt, ItemStatus.APPROVED, attempts, correct,
                attempts == 0 ? null : correct / (double) attempts, null, null, null, null, null, null, 0, 0,
                classLearners, classAccuracy);
    }
}
