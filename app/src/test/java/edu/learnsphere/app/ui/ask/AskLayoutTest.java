package edu.learnsphere.app.ui.ask;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AskLayoutTest {

    @Test
    void aWideScreenShowsTheConversationsBesideTheSources() {
        AskLayout.Widths widths = AskLayout.compute(1208, true, null);

        assertThat(widths.threads()).isTrue();
        assertThat(widths.source()).isEqualTo((double) Math.round(1208 * AskLayout.SOURCE_SHARE));
    }

    @Test
    void closedSourcesGiveTheConversationTheWidth() {
        assertThat(AskLayout.compute(900, false, null)).isEqualTo(new AskLayout.Widths(true, 0));
    }

    @Test
    void theConversationsStepAsideWhenTheSourcesWouldCrowdTheConversation() {
        AskLayout.Widths widths = AskLayout.compute(1000, true, null);

        assertThat(widths.threads()).isFalse();
        assertThat(widths.source()).isEqualTo(AskLayout.SOURCE_MIN + 20);
    }

    @Test
    void theLearnersChoiceWinsWhileTheConversationStillFits() {
        assertThat(AskLayout.compute(1208, false, false).threads()).isFalse();
        assertThat(AskLayout.compute(1000, true, true).threads()).as("380 px left for the conversation").isTrue();
        assertThat(AskLayout.compute(900, true, true).threads()).as("too narrow even when asked for").isFalse();
    }

    @Test
    void theSourceViewerStaysWithinItsSizes() {
        assertThat(AskLayout.sourceWidth(600)).isEqualTo(AskLayout.SOURCE_MIN);
        assertThat(AskLayout.sourceWidth(4000)).isEqualTo(AskLayout.SOURCE_MAX);
    }
}
