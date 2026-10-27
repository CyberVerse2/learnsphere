package edu.learnsphere.core.learning;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class InterleavingTest {

    private static boolean sameTopic(String a, String b) {
        return a.charAt(0) == b.charAt(0);
    }

    @Test
    void spreadsItemsOfOneTopicApart() {
        List<String> spread = Interleaving.spread(List.of("a1", "a2", "a3", "b1", "b2", "c1"), InterleavingTest::sameTopic,
                Interleaving.LOOK_AHEAD);
        assertThat(spread).containsExactly("a1", "b1", "a2", "b2", "a3", "c1");
    }

    @Test
    void keepsTheOrderWhenNothingElseIsWithinReach() {
        List<String> spread = Interleaving.spread(List.of("a1", "a2", "a3", "a4", "a5", "a6", "b1"),
                InterleavingTest::sameTopic, 2);
        assertThat(spread).containsExactly("a1", "a2", "a3", "a4", "b1", "a5", "a6");
        assertThat(Interleaving.spread(List.of("a1", "a2"), InterleavingTest::sameTopic, 4)).containsExactly("a1", "a2");
    }

    @Test
    void mergesNewItemsEvenlyAndInOrder() {
        assertThat(Interleaving.merge(List.of("r1", "r2", "r3", "r4"), List.of("n1", "n2")))
                .containsExactly("r1", "n1", "r2", "r3", "n2", "r4");
        assertThat(Interleaving.merge(List.of("r1"), List.of("n1", "n2", "n3")))
                .containsExactly("r1", "n1", "n2", "n3");
        assertThat(Interleaving.merge(List.of(), List.of("n1", "n2"))).containsExactly("n1", "n2");
        assertThat(Interleaving.merge(List.of("r1", "r2"), List.of())).containsExactly("r1", "r2");
    }
}
