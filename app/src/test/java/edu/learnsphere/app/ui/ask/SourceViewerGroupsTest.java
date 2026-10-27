package edu.learnsphere.app.ui.ask;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.spi.NormalizedRect;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The margin marks beside a highlighted passage: one per run of lines that follow each other. */
class SourceViewerGroupsTest {

    @Test
    void linesThatFollowEachOtherShareOneMark() {
        List<NormalizedRect> lines = List.of(
                new NormalizedRect(0.1, 0.30, 0.8, 0.02),
                new NormalizedRect(0.1, 0.10, 0.8, 0.02),
                new NormalizedRect(0.1, 0.125, 0.6, 0.02));

        List<NormalizedRect> groups = SourceViewer.groups(lines);

        assertThat(groups).hasSize(2);
        assertThat(groups.getFirst().y()).isEqualTo(0.10);
        assertThat(groups.getFirst().height()).isCloseTo(0.045, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(groups.get(1)).isEqualTo(lines.getFirst());
    }

    @Test
    void columnsSideBySideKeepTheirOwnMarks() {
        List<NormalizedRect> lines = List.of(
                new NormalizedRect(0.05, 0.20, 0.40, 0.02),
                new NormalizedRect(0.55, 0.21, 0.40, 0.02));

        assertThat(SourceViewer.groups(lines)).hasSize(2);
    }

    @Test
    void noRectanglesNoMarks() {
        assertThat(SourceViewer.groups(List.of())).isEmpty();
    }
}
