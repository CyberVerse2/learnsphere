package edu.learnsphere.app.ui.studio.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class JobOutcomesTest {

    @Test
    void saysHowManyQuestionsWereDraftedAndWhatWasLeftOut() {
        assertThat(JobOutcomes.draftedSentence(new JobOutcomes.Drafted(12, 2, 0)))
                .isEqualTo("12 questions drafted, 2 left out because they repeated others.");
        assertThat(JobOutcomes.draftedSentence(new JobOutcomes.Drafted(1, 1, 0)))
                .isEqualTo("1 question drafted, 1 left out because it repeated another.");
        assertThat(JobOutcomes.draftedSentence(new JobOutcomes.Drafted(6, 0, 1)))
                .isEqualTo("6 questions drafted. Check 1 closely: the source may not back it.");
        assertThat(JobOutcomes.draftedSentence(new JobOutcomes.Drafted(5, 0, 2)))
                .isEqualTo("5 questions drafted. Check 2 closely: the source may not back them.");
        assertThat(JobOutcomes.draftedSentence(new JobOutcomes.Drafted(0, 3, 0)))
                .isEqualTo("No questions drafted: every one repeated a question you have.");
        assertThat(JobOutcomes.draftedSentence(null)).isEqualTo("Your drafts are ready to review.");
    }

    @Test
    void saysHowManyKeyConceptsWereFoundAndHowManyAreNew() {
        assertThat(JobOutcomes.foundSentence(new JobOutcomes.Found("m", 5, 3, 0))).isEqualTo("Found 5 key concepts, 3 of them new.");
        assertThat(JobOutcomes.foundSentence(new JobOutcomes.Found("m", 4, 4, 0))).isEqualTo("Found 4 key concepts.");
        assertThat(JobOutcomes.foundSentence(new JobOutcomes.Found("m", 4, 0, 0)))
                .isEqualTo("Found 4 key concepts, all ones you had already.");
        assertThat(JobOutcomes.foundSentence(null)).isEqualTo("Key concepts found.");
    }
}
