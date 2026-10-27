package edu.learnsphere.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RefusalOpeningFilterTest {

    private final List<String> shown = new ArrayList<>();
    private final RefusalOpeningFilter filter = new RefusalOpeningFilter(shown::add);

    private void feed(String... pieces) {
        for (String piece : pieces) {
            filter.accept(piece);
        }
    }

    @Test
    void anOrdinaryAnswerStreamsOnceItsFirstSentenceIsComplete() {
        feed("Paging splits ", "memory into ");
        assertThat(shown).isEmpty();
        feed("pages [1]");
        assertThat(String.join("", shown)).isEqualTo("Paging splits memory into pages [1]");
        feed(". Frames ", "hold them [1].");
        assertThat(shown).endsWith("hold them [1].");
        filter.finish();
        assertThat(filter.refused()).isFalse();
        assertThat(String.join("", shown)).isEqualTo(filter.text());
    }

    @Test
    void aRefusingOpeningIsHeldUntilTheEndAndNeverShown() {
        feed("Your notes ", "don't explain ", "the banker's algorithm. ", "They focus on paging.");
        filter.finish();
        assertThat(filter.refused()).isTrue();
        assertThat(shown).isEmpty();
    }

    @Test
    void aRefusingOpeningFollowedByACitationIsShownInFull() {
        feed("The notes only describe two algorithms. ", "FCFS runs jobs in order");
        assertThat(shown).isEmpty();
        feed(" [2].");
        assertThat(String.join("", shown)).isEqualTo("The notes only describe two algorithms. FCFS runs jobs in order [2].");
        filter.finish();
        assertThat(filter.refused()).isFalse();
    }

    @Test
    void aLongOpeningWithoutASentenceEndIsJudgedAfterALimit() {
        String words = "word ".repeat(RefusalOpeningFilter.OPENING_LIMIT / 5 + 1);
        feed(words);
        assertThat(String.join("", shown)).isEqualTo(words);
    }

    @Test
    void aShortReplyIsJudgedAtTheEnd() {
        feed("Yes, it does");
        assertThat(shown).isEmpty();
        filter.finish();
        assertThat(String.join("", shown)).isEqualTo("Yes, it does");
    }
}
