package edu.learnsphere.app.ui.studio.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ShortAnswerTextTest {

    @Test
    void keepsKeyPointsAfterTheModelAnswer() {
        ShortAnswerText text = new ShortAnswerText(" Water moves across a membrane. ", List.of("water moves", " ", "towards more solute"));

        assertThat(text.compose()).isEqualTo("Water moves across a membrane.\n\nKey points:\n• water moves\n• towards more solute");
        assertThat(ShortAnswerText.parse(text.compose())).isEqualTo(text);
    }

    @Test
    void anAnswerWithoutKeyPointsIsAllModelAnswer() {
        ShortAnswerText text = ShortAnswerText.parse("Oxygen is more electronegative than hydrogen.");

        assertThat(text.modelAnswer()).isEqualTo("Oxygen is more electronegative than hydrogen.");
        assertThat(text.keyPoints()).isEmpty();
        assertThat(text.compose()).isEqualTo("Oxygen is more electronegative than hydrogen.");
    }

    @Test
    void keyPointsAloneLeaveTheModelAnswerEmpty() {
        ShortAnswerText text = ShortAnswerText.parse(new ShortAnswerText(" ", List.of("a point")).compose());

        assertThat(text.modelAnswer()).isEmpty();
        assertThat(text.keyPoints()).containsExactly("a point");
    }

    @Test
    void readsOtherBulletsToo() {
        ShortAnswerText text = ShortAnswerText.parse("Because.\r\n\r\nKey points:\r\n- one\r\n* two\r\nthree");

        assertThat(text.modelAnswer()).isEqualTo("Because.");
        assertThat(text.keyPoints()).containsExactly("one", "two", "three");
    }
}
