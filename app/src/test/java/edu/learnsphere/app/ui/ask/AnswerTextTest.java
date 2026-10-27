package edu.learnsphere.app.ui.ask;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Citation;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerTextTest {

    private static final Citation ROUND_ROBIN = new Citation(1, "c1", "d1", "Lecture 2 – CPU Scheduling", 12, 12, "snippet");
    private static final Citation SWITCHING = new Citation(2, "c2", "d2", "Lecture 1 – Processes", 9, 10, "snippet");

    @Test
    void thePreviewLeavesOutMarkersAndOneStillBeingWritten() {
        assertThat(AnswerText.preview("Switching costs time [1]. It saves state [1][2] and [1, 2] more [2"))
                .isEqualTo("Switching costs time. It saves state and more");
        assertThat(AnswerText.preview("Lists [")).isEqualTo("Lists");
        assertThat(AnswerText.preview("")).isEmpty();
        assertThat(AnswerText.preview(null)).isEmpty();
    }

    @Test
    void theClipboardGetsPagesInPlaceOfMarkersAndTheSourcesAtTheEnd() {
        String copied = AnswerText.forClipboard("A small quantum adds overhead [1][2]. **Pick** wisely [9].",
                List.of(ROUND_ROBIN, SWITCHING));

        assertThat(copied).isEqualTo("""
                A small quantum adds overhead (p. 12; pp. 9–10). **Pick** wisely.

                Sources
                - Lecture 2 – CPU Scheduling, p. 12
                - Lecture 1 – Processes, pp. 9–10""");
    }

    @Test
    void anAnswerWithoutCitationsIsCopiedAsItIs() {
        assertThat(AnswerText.forClipboard("Nothing cited here.", List.of())).isEqualTo("Nothing cited here.");
    }

    @Test
    void findsCitationsByNumberAndNamesUntitledConversations() {
        assertThat(AnswerText.find(List.of(ROUND_ROBIN, SWITCHING), 2)).isEqualTo(SWITCHING);
        assertThat(AnswerText.find(List.of(ROUND_ROBIN), 3)).isNull();
        assertThat(AnswerText.title("  ")).isEqualTo("New conversation");
        assertThat(AnswerText.title(" Paging ")).isEqualTo("Paging");
    }
}
