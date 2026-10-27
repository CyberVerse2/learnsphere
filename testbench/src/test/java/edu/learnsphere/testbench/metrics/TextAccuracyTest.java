package edu.learnsphere.testbench.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class TextAccuracyTest {

    @Test
    void levenshteinCountsSingleCharacterEdits() {
        assertThat(TextAccuracy.levenshtein("kitten", "sitting")).isEqualTo(3);
        assertThat(TextAccuracy.levenshtein("", "abc")).isEqualTo(3);
        assertThat(TextAccuracy.levenshtein("abc", "")).isEqualTo(3);
        assertThat(TextAccuracy.levenshtein("same", "same")).isZero();
        assertThat(TextAccuracy.levenshtein("flaw", "lawn")).isEqualTo(2);
    }

    @Test
    void levenshteinOnWordsCountsWholeWords() {
        assertThat(TextAccuracy.levenshtein(List.of("the", "cat", "sat"), List.of("the", "bat", "sat", "down"))).isEqualTo(2);
    }

    @Test
    void identicalTextIsFullyAccurate() {
        assertThat(TextAccuracy.characterAccuracy("Sampling frames matter.", "Sampling frames matter.")).isEqualTo(1.0);
        assertThat(TextAccuracy.wordAccuracy("Sampling frames matter.", "Sampling frames matter.")).isEqualTo(1.0);
    }

    @Test
    void characterAccuracyIsOneMinusTheCharacterErrorRate() {
        // One substitution in a 10-character reference.
        assertThat(TextAccuracy.characterAccuracy("abcdefghij", "abcdefghiX")).isCloseTo(0.9, within(1e-9));
        // Two words of five wrong.
        assertThat(TextAccuracy.wordAccuracy("one two three four five", "one too three for five")).isCloseTo(0.6, within(1e-9));
    }

    @Test
    void accuracyNeverDropsBelowZero() {
        assertThat(TextAccuracy.characterAccuracy("abc", "a completely different and much longer text")).isZero();
        assertThat(TextAccuracy.characterAccuracy("abc", "")).isZero();
        assertThat(TextAccuracy.characterAccuracy("", "")).isEqualTo(1.0);
    }

    @Test
    void markdownStructureDoesNotCountAsErrors() {
        String reference = """
                4.4 Sampling

                The population is the whole group.

                Simple random sampling: every member has an equal chance.
                Systematic sampling: every kth member.""";
        String transcription = """
                ## 4.4 Sampling

                The population is the **whole** group.

                - Simple random sampling: every member has an equal chance.
                * Systematic sampling: every kth member.
                """;
        assertThat(TextAccuracy.characterAccuracy(reference, transcription)).isEqualTo(1.0);
    }

    @Test
    void markdownTablesCompareCellByCell() {
        String reference = "Table 4.1: Threats\nThreat Type\nSelection bias Internal";
        String transcription = """
                Table 4.1: Threats

                | Threat | Type |
                |---|:---:|
                | Selection bias | Internal |
                """;
        assertThat(TextAccuracy.normalize(transcription)).isEqualTo(TextAccuracy.normalize(reference));
    }

    @Test
    void typographicQuotesAndDashesMatchTheirPlainForms() {
        assertThat(TextAccuracy.normalize("“strongly agree” – it’s fine"))
                .isEqualTo("\"strongly agree\" - it's fine");
    }
}
