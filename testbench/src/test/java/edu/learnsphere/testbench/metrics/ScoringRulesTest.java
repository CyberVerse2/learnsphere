package edu.learnsphere.testbench.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import edu.learnsphere.core.model.ItemType;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScoringRulesTest {

    @Test
    void citationMarkersExpandListsAndRanges() {
        assertThat(Citations.markerNumbers("FCFS is simple [1]. It suffers from convoys [2, 3] and [4-6]; see [7 and 8]."))
                .containsExactly(1, 2, 3, 4, 5, 6, 7, 8);
        assertThat(Citations.markerNumbers("No markers, just [brackets] and [ ].")).isEmpty();
        assertThat(Citations.markerNumbers(null)).isEmpty();
    }

    @Test
    void citationValidityCountsNumbersInRange() {
        List<Integer> numbers = Citations.markerNumbers("A [1]. B [2]. C [9].");
        assertThat(Citations.validCount(numbers, 6)).isEqualTo(2);
    }

    @Test
    void keyTermsAcceptAlternativesAndIgnoreCase() {
        assertThat(KeyTerms.present("zero|0", "fork() returns 0 in the child")).isTrue();
        assertThat(KeyTerms.present("task_struct", "Linux calls it the TASK_STRUCT.")).isTrue();
        assertThat(KeyTerms.coverage(List.of("mutual exclusion", "hold and wait", "circular wait"),
                "Mutual exclusion and circular wait.")).isCloseTo(2 / 3.0, within(1e-9));
        assertThat(KeyTerms.coverage(List.of(), "anything")).isEqualTo(1.0);
    }

    @Test
    void numericKeyTermsMatchWholeNumbersOnly() {
        assertThat(KeyTerms.present("12", "The offset takes 12 bits.")).isTrue();
        assertThat(KeyTerms.present("12", "It needs 120 entries.")).isFalse();
        assertThat(KeyTerms.present("12", "about 0.12 ms")).isFalse();
        assertThat(KeyTerms.present("420939|420,939", "up to 420,939 inclusive")).isTrue();
    }

    @Test
    void statsSummariseMeasurements() {
        List<Double> values = List.of(4.0, 1.0, 3.0, 2.0);
        assertThat(Stats.mean(values)).isEqualTo(2.5);
        assertThat(Stats.median(values)).isEqualTo(2.5);
        assertThat(Stats.percentile(values, 0)).isEqualTo(1.0);
        assertThat(Stats.percentile(values, 100)).isEqualTo(4.0);
        assertThat(Stats.percentile(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), 90)).isCloseTo(9.1, within(1e-9));
        assertThat(Stats.max(values)).isEqualTo(4.0);
        assertThat(Stats.standardDeviation(List.of(2, 4, 4, 4, 5, 5, 7, 9))).isCloseTo(2.138, within(1e-3));
        assertThat(Stats.median(List.<Double>of())).isZero();
        assertThat(Stats.share(1, 4)).isEqualTo(0.25);
        assertThat(Stats.share(1, 0)).isZero();
    }

    @Test
    void typeRulesAcceptWellFormedItems() {
        assertThat(TypeRules.violation(ItemType.MCQ, "Which algorithm can starve?", "SJF", List.of("FCFS", "Round robin", "None")))
                .isEmpty();
        assertThat(TypeRules.violation(ItemType.TRUE_FALSE, "FCFS is preemptive.", "false", List.of())).isEmpty();
        assertThat(TypeRules.violation(ItemType.CLOZE, "The PCB in Linux is called ____.", "task_struct", List.of())).isEmpty();
        assertThat(TypeRules.violation(ItemType.FLASHCARD, "Thrashing", "More time paging than executing", List.of())).isEmpty();
    }

    @Test
    void typeRulesNameTheBrokenRule() {
        assertThat(TypeRules.violation(ItemType.MCQ, "Q?", "A", List.of("B"))).contains("1 wrong options");
        assertThat(TypeRules.violation(ItemType.MCQ, "Q?", "A", List.of("B", "a"))).contains("repeated option \"a\"");
        assertThat(TypeRules.violation(ItemType.TRUE_FALSE, "Is FCFS preemptive?", "false", List.of()))
                .contains("statement is a question");
        assertThat(TypeRules.violation(ItemType.TRUE_FALSE, "FCFS is preemptive.", "no", List.of()))
                .contains("answer \"no\" is not true or false");
        assertThat(TypeRules.violation(ItemType.CLOZE, "____ and ____", "a", List.of())).contains("2 blanks");
        assertThat(TypeRules.violation(ItemType.CLOZE, "The task_struct is ____.", "task_struct", List.of()))
                .contains("prompt contains the answer");
        assertThat(TypeRules.violation(ItemType.SHORT_ANSWER, "According to the passage, what is a PCB?", "A record", List.of()))
                .contains("prompt refers to its source");
        assertThat(TypeRules.violation(ItemType.FLASHCARD, " ", "x", List.of())).contains("blank prompt");
    }
}
