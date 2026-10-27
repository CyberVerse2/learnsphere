package edu.learnsphere.app.ui.studio.review;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.ItemType;
import java.util.List;
import org.junit.jupiter.api.Test;

class SupportingTextTest {

    private static final String PASSAGE = "The cell membrane controls what enters and leaves the cell. "
            + "The nucleus holds the cell's chromosomes. Mitochondria release energy from glucose during respiration, "
            + "and cells that need a lot of energy have many of them.";

    @Test
    void marksTheSentenceThatCarriesTheAnswer() {
        String evidence = SupportingText.evidence(ItemType.MCQ, "Which part releases energy from glucose?",
                "Mitochondria release energy from glucose");

        SupportingText.Range range = SupportingText.find(PASSAGE, evidence).orElseThrow();

        assertThat(PASSAGE.substring(range.start(), range.end())).startsWith("Mitochondria release energy")
                .endsWith("many of them.");
    }

    @Test
    void readsTheStatementOfATrueOrFalseQuestionAndFillsTheBlankOfASentence() {
        assertThat(SupportingText.evidence(ItemType.TRUE_FALSE, "The nucleus holds the chromosomes.", "true"))
                .isEqualTo("The nucleus holds the chromosomes.");
        assertThat(SupportingText.evidence(ItemType.CLOZE, "The ____ holds the chromosomes.", "nucleus"))
                .isEqualTo("The  nucleus  holds the chromosomes.");

        SupportingText.Range range = SupportingText.find(PASSAGE,
                SupportingText.evidence(ItemType.CLOZE, "The ____ holds the cell's chromosomes.", "nucleus")).orElseThrow();
        assertThat(PASSAGE.substring(range.start(), range.end())).isEqualTo("The nucleus holds the cell's chromosomes.");
    }

    @Test
    void usesTheQuestionToo_whenTheAnswerIsASingleWord() {
        String evidence = SupportingText.evidence(ItemType.MCQ, "What controls what enters and leaves the cell?", "Membrane");

        SupportingText.Range range = SupportingText.find(PASSAGE, evidence).orElseThrow();

        assertThat(PASSAGE.substring(range.start(), range.end()))
                .isEqualTo("The cell membrane controls what enters and leaves the cell.");
    }

    @Test
    void marksNothingWhenNoSentenceClearlyCarriesTheAnswer() {
        assertThat(SupportingText.find(PASSAGE, "Photosynthesis happens in chloroplasts of plant leaves")).isEmpty();
        assertThat(SupportingText.find(PASSAGE, "")).isEmpty();
        assertThat(SupportingText.find("", "membrane")).isEmpty();
    }

    @Test
    void foldsPluralsAndIgnoresCase() {
        assertThat(SupportingText.terms("Cells and CHROMOSOMES, the mitochondria's energies"))
                .contains("cell", "chromosome", "energy")
                .doesNotContain("and", "the");
    }

    @Test
    void splitsSentencesAtStopsAndLineBreaks() {
        List<SupportingText.Range> sentences = SupportingText.sentences("One. Two? Three\nFour 3.5 five");

        assertThat(sentences).extracting(r -> "One. Two? Three\nFour 3.5 five".substring(r.start(), r.end()))
                .containsExactly("One.", "Two?", "Three", "Four 3.5 five");
    }

    @Test
    void cutsALongPassageToWholeSentencesAroundTheMark() {
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            text.append("Sentence number ").append(i).append(" says something about topic ").append(i).append(". ");
        }
        String passage = text.toString().strip();
        int at = passage.indexOf("Sentence number 20 ");
        SupportingText.Range focus = new SupportingText.Range(at, passage.indexOf('.', at) + 1);

        SupportingText.Range cut = SupportingText.excerpt(passage, focus, 300);

        assertThat(cut.length()).isLessThanOrEqualTo(300);
        assertThat(cut.start()).isLessThanOrEqualTo(focus.start());
        assertThat(cut.end()).isGreaterThanOrEqualTo(focus.end());
        assertThat(passage.substring(cut.start(), cut.end())).startsWith("Sentence").endsWith(".");
    }

    @Test
    void keepsAShortPassageWhole() {
        assertThat(SupportingText.excerpt(PASSAGE, null, 1_000)).isEqualTo(new SupportingText.Range(0, PASSAGE.length()));
    }
}
