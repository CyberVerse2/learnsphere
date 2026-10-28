package edu.learnsphere.core.service.impl.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TopicsTest {

    @Test
    void numberingIsRemoved() {
        assertThat(Topics.clean("2.2 Conjunction")).contains("Conjunction");
        assertThat(Topics.clean("Chapter 3 Predicate logic")).contains("Predicate logic");
        assertThat(Topics.clean("IV. Results of the survey")).contains("Results of the survey");
        assertThat(Topics.clean("3) the importance of proof")).contains("The importance of proof");
    }

    @Test
    void wordsThatLookLikeRomanNumeralsStay() {
        assertThat(Topics.clean("Civil engineering")).contains("Civil engineering");
        assertThat(Topics.clean("Mix design")).contains("Mix design");
    }

    @Test
    void headingsThatAreNotTopicsAreLeftOut() {
        assertThat(Topics.clean("Example 2.3 First, consider the two truth tables:")).isEmpty();
        assertThat(Topics.clean("Exercises")).isEmpty();
        assertThat(Topics.clean("Figure 4")).isEmpty();
        assertThat(Topics.clean("Introduction")).isEmpty();
        assertThat(Topics.clean("1 Introduction")).isEmpty();
        assertThat(Topics.clean("References")).isEmpty();
        assertThat(Topics.clean("4.2")).isEmpty();
        assertThat(Topics.clean("A heading that runs on far too long to be offered as a topic chip")).isEmpty();
        assertThat(Topics.clean(null)).isEmpty();
    }

    @Test
    void repeatsAreDroppedKeepingTheFirstSpelling() {
        assertThat(Topics.distinct(List.of("Propositional Logic", "2.1 Propositional logic", "Example 2.2", "2.2 Conjunction")))
                .containsExactly("Propositional Logic", "Conjunction");
    }

    @Test
    void aLongListIsSpreadAcrossTheCourse() {
        List<String> topics = List.of("a1", "b2", "c3", "d4", "e5", "f6", "g7", "h8", "i9", "j10");
        assertThat(Topics.spread(topics, 3)).containsExactly("a1", "d4", "g7");
        assertThat(Topics.spread(topics.subList(0, 2), 3)).containsExactly("a1", "b2");
        assertThat(Topics.spread(topics, 0)).isEmpty();
    }
}
