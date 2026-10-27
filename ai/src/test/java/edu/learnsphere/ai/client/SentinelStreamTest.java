package edu.learnsphere.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SentinelStreamTest {

    private static final String SENTINEL = "NOT_IN_MATERIAL";

    private record Run(SentinelStream stream, List<String> passed) {
        String passedText() {
            return String.join("", passed);
        }
    }

    private static Run feed(String... pieces) {
        List<String> passed = new ArrayList<>();
        SentinelStream stream = new SentinelStream(SENTINEL, passed::add);
        for (String piece : pieces) {
            stream.accept(piece);
        }
        stream.finish();
        return new Run(stream, passed);
    }

    @Test
    void passesAnOrdinaryAnswerThroughUnchanged() {
        Run run = feed("A process ", "is a program ", "in execution [1].");
        assertThat(run.stream().refused()).isFalse();
        assertThat(run.passedText()).isEqualTo("A process is a program in execution [1].");
        assertThat(run.stream().text()).isEqualTo(run.passedText());
    }

    @Test
    void streamsAsSoonAsTheStartCannotBeTheSentinel() {
        List<String> passed = new ArrayList<>();
        SentinelStream stream = new SentinelStream(SENTINEL, passed::add);
        stream.accept("No");
        assertThat(passed).isEmpty(); // "NO" could still become NOT_IN_MATERIAL
        stream.accept("w the");
        assertThat(String.join("", passed)).isEqualTo("Now the");
    }

    @Test
    void aReplyThatIsTheSentinelIsARefusalAndNothingIsStreamed() {
        Run run = feed("NOT", "_IN", "_MAT", "ERIAL");
        assertThat(run.stream().refused()).isTrue();
        assertThat(run.passed()).isEmpty();
        assertThat(run.stream().text()).isEmpty();
    }

    @Test
    void recognisesTheSentinelInOtherSpellings() {
        assertThat(feed("**Not in material**").stream().refused()).isTrue();
        assertThat(feed("not_in_", "material.").stream().refused()).isTrue();
        assertThat(feed("  NOT IN MATERIAL").stream().refused()).isTrue();
    }

    @Test
    void removesTheSentinelWhenItFollowsAnAnswer() {
        Run run = feed("Paging uses frames [1]. ", "NOT_IN", "_MATERIAL");
        assertThat(run.stream().refused()).isFalse();
        assertThat(run.stream().sentinelRemoved()).isTrue();
        assertThat(run.passedText()).isEqualTo("Paging uses frames [1]. ");
        assertThat(run.passed()).noneMatch(piece -> piece.contains("NOT"));
    }

    @Test
    void releasesAHeldTailThatTurnsOutNotToBeTheSentinel() {
        Run run = feed("It is ", "NOT", " shared [2].");
        assertThat(run.passedText()).isEqualTo("It is NOT shared [2].");
        assertThat(run.stream().sentinelRemoved()).isFalse();
    }

    @Test
    void aShortAnswerThatCouldStartTheSentinelIsPassedOnAtTheEnd() {
        Run run = feed("No.");
        assertThat(run.stream().refused()).isFalse();
        assertThat(run.passedText()).isEqualTo("No.");
    }
}
