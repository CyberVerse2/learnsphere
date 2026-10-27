package edu.learnsphere.core.ask;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.spi.Passage;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CitationValidatorTest {

    private static Passage passage(int number, String text) {
        return new Passage(number, "chunk-" + number, "doc-" + number, "Biology notes", number * 2, number * 2 + 1,
                "Section", text, 0.9);
    }

    private static final List<Passage> PASSAGES = List.of(
            passage(1, "Cells are the basic unit of life. Mitochondria produce most of the cell's ATP through respiration. "
                    + "Ribosomes build proteins."),
            passage(2, "Photosynthesis happens in chloroplasts. It converts light energy into chemical energy stored in glucose."),
            passage(3, "The nucleus stores genetic material."));

    @Test
    void keepsValidMarkersAndBuildsCitations() {
        CitationValidator.Result result = CitationValidator.validate(
                "Mitochondria produce ATP [1]. Chloroplasts capture light energy [2].", PASSAGES);

        assertThat(result.text()).isEqualTo("Mitochondria produce ATP [1]. Chloroplasts capture light energy [2].");
        assertThat(result.hasCitations()).isTrue();
        assertThat(result.invalidNumbers()).isEmpty();
        assertThat(result.citations()).extracting(Citation::number).containsExactly(1, 2);
        Citation first = result.citations().getFirst();
        assertThat(first.chunkId()).isEqualTo("chunk-1");
        assertThat(first.documentTitle()).isEqualTo("Biology notes");
        assertThat(first.pageStart()).isEqualTo(2);
        assertThat(first.pageEnd()).isEqualTo(3);
        assertThat(first.snippet()).startsWith("Mitochondria produce most of the cell's ATP");
    }

    @Test
    void removesMarkersThatAreNotPassages() {
        CitationValidator.Result result = CitationValidator.validate("ATP comes from mitochondria [7]. Also [1].", PASSAGES);
        assertThat(result.text()).isEqualTo("ATP comes from mitochondria. Also [1].");
        assertThat(result.invalidNumbers()).containsExactly(7);
        assertThat(result.citations()).extracting(Citation::number).containsExactly(1);
    }

    @Test
    void normalizesGroupsAndRanges() {
        assertThat(CitationValidator.validate("Both [1, 3].", PASSAGES).text()).isEqualTo("Both [1][3].");
        assertThat(CitationValidator.validate("All [1-3].", PASSAGES).text()).isEqualTo("All [1][2][3].");
        assertThat(CitationValidator.validate("Some [2; 9].", PASSAGES).text()).isEqualTo("Some [2].");
        assertThat(CitationValidator.validate("Twice [1][1, 1].", PASSAGES).citations()).hasSize(1);
    }

    @Test
    void answersWithoutValidMarkersHaveNoCitations() {
        assertThat(CitationValidator.validate("I think so.", PASSAGES).hasCitations()).isFalse();
        assertThat(CitationValidator.validate("Maybe [4].", PASSAGES).hasCitations()).isFalse();
        assertThat(CitationValidator.validate(null, PASSAGES).text()).isEmpty();
        assertThat(CitationValidator.validate("Cited [1].", List.of()).hasCitations()).isFalse();
    }

    @Test
    void parsesMarkerNumbers() {
        assertThat(CitationValidator.numbers("1, 3")).containsExactly(1, 3);
        assertThat(CitationValidator.numbers("2-4")).containsExactly(2, 3, 4);
        assertThat(CitationValidator.numbers("4-2")).containsExactly(4);
        assertThat(CitationValidator.numbers("1-90")).containsExactly(1);
    }

    @Test
    void picksTheBestMatchingSentenceAndExtendsShortOnes() {
        String text = "Short one. Glucose stores chemical energy made during photosynthesis in leaves. Final sentence here.";
        assertThat(CitationValidator.bestSnippet(text, Set.of("glucose", "photosynthesis")))
                .isEqualTo("Glucose stores chemical energy made during photosynthesis in leaves.");
        assertThat(CitationValidator.bestSnippet(text, Set.of()))
                .isEqualTo("Short one. Glucose stores chemical energy made during photosynthesis in leaves.");
    }

    @Test
    void capsLongSnippets() {
        String longSentence = "Word ".repeat(120).strip() + ".";
        String snippet = CitationValidator.bestSnippet(longSentence, Set.of("word"));
        assertThat(snippet.length()).isLessThanOrEqualTo(CitationValidator.MAX_SNIPPET + 1);
        assertThat(snippet).endsWith("…");
    }

    @Test
    void ignoresStopwordsAndShortWords() {
        assertThat(CitationValidator.contentWords("The cell is an ATP factory, and it works."))
                .containsExactlyInAnyOrder("cell", "atp", "factory", "works");
    }
}
