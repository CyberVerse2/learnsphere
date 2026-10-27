package edu.learnsphere.search;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.error.UserMessage;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.List;
import java.util.Locale;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.IndexFormatTooNewException;
import org.apache.lucene.index.IndexFormatTooOldException;
import org.junit.jupiter.api.Test;

class SearchFailuresTest {

    private static final UserMessage FALLBACK = SearchFailures.SAVE_FAILED;

    /** Wraps {@code cause} the way failures reach the index: inside other exceptions, several levels deep. */
    private static Exception nested(Throwable cause) {
        return new RuntimeException("outer", new UncheckedIOException(new IOException("middle", cause)));
    }

    @Test
    void damagedMissingAndUnreadableFilesAreDamage() {
        List<Throwable> damage = List.of(
                new CorruptIndexException("checksum failed", "_0.cfs"),
                new IndexFormatTooOldException("_0.si", "written by an old version"),
                new IndexFormatTooNewException("_0.si", 99, 1, 10),
                new NoSuchFileException("_0.si"),
                new FileNotFoundException("_0.cfe"),
                new EOFException("read past end of file"));

        for (Throwable cause : damage) {
            assertThat(SearchFailures.isDamage(cause)).as(cause.toString()).isTrue();
            assertThat(SearchFailures.isDamage(nested(cause))).as("nested " + cause).isTrue();
            assertThat(SearchFailures.messageFor(nested(cause), FALLBACK)).isEqualTo(SearchFailures.NEEDS_RESTART);
        }
    }

    @Test
    void otherFailuresAreNotDamage() {
        assertThat(SearchFailures.isDamage(null)).isFalse();
        assertThat(SearchFailures.isDamage(new IOException("Input/output error"))).isFalse();
        assertThat(SearchFailures.isDamage(nested(new AccessDeniedException("_0.cfs")))).isFalse();
        assertThat(SearchFailures.isDamage(new IllegalStateException("closed"))).isFalse();
    }

    @Test
    void fullDisksAreRecognisedInEveryPlatformsWording() {
        List<String> wordings = List.of(
                "No space left on device",                   // macOS and Linux
                "There is not enough space on the disk",     // Windows
                "Disk full");

        for (String wording : wordings) {
            assertThat(SearchFailures.isDiskFull(new IOException(wording))).as(wording).isTrue();
            assertThat(SearchFailures.isDiskFull(nested(new IOException(wording.toUpperCase(Locale.ROOT)))))
                    .as("nested " + wording).isTrue();
            assertThat(SearchFailures.messageFor(nested(new IOException(wording)), FALLBACK)).isEqualTo(UserMessage.DISK_FULL);
        }
        assertThat(SearchFailures.isDiskFull(null)).isFalse();
        assertThat(SearchFailures.isDiskFull(new IOException())).isFalse();
        assertThat(SearchFailures.isDiskFull(new IOException("Input/output error"))).isFalse();
    }

    @Test
    void aFullDiskWinsOverDamageItCaused() {
        // A write cut short by a full disk can leave a short file behind; the learner can act on the space.
        IOException cause = new IOException("No space left on device", new EOFException("read past end of file"));

        assertThat(SearchFailures.messageFor(cause, FALLBACK)).isEqualTo(UserMessage.DISK_FULL);
    }

    @Test
    void anythingElseGetsTheSentenceOfTheOperation() {
        assertThat(SearchFailures.messageFor(new IOException("Input/output error"), SearchFailures.ADD_FAILED))
                .isEqualTo(SearchFailures.ADD_FAILED);
        assertThat(SearchFailures.messageFor(nested(new AccessDeniedException("_0.cfs")), SearchFailures.REMOVE_FAILED))
                .isEqualTo(SearchFailures.REMOVE_FAILED);
    }

    @Test
    void aCauseChainThatLoopsStillEnds() {
        IOException first = new IOException("first");
        IOException second = new IOException("second", first);
        first.initCause(second);

        assertThat(SearchFailures.isDamage(first)).isFalse();
        assertThat(SearchFailures.isDiskFull(first)).isFalse();
        assertThat(SearchFailures.messageFor(first, FALLBACK)).isEqualTo(FALLBACK);
    }

    @Test
    void sentencesUseTheLearnersWordsNotTheImplementations() {
        List<UserMessage> sentences = List.of(SearchFailures.SEARCH_FAILED, SearchFailures.ADD_FAILED,
                SearchFailures.REMOVE_FAILED, SearchFailures.SAVE_FAILED, SearchFailures.REBUILD_FAILED,
                SearchFailures.NEEDS_RESTART);
        List<String> implementationWords = List.of("search", "index", "lucene", "chunk", "embedding", "vector",
                "model", "server", "file", "database", "update", "commit", "error");

        for (UserMessage sentence : sentences) {
            String lower = sentence.text().toLowerCase(Locale.ROOT);
            assertThat(implementationWords).as(sentence.text()).noneMatch(lower::contains);
            assertThat(sentence.text()).endsWith(".");
        }
    }
}
