package edu.learnsphere.core.spi;

import java.util.Objects;

/**
 * The finished result of {@link GroundedAnswerer#answer}: everything that was streamed, plus whether the
 * answerer said the passages do not contain the answer.
 *
 * @param text         the full answer text with {@code [n]} markers (empty when insufficient)
 * @param insufficient true when the passages do not answer the question
 */
public record GroundedAnswer(String text, boolean insufficient) {

    public GroundedAnswer {
        Objects.requireNonNull(text, "text");
    }

    /** The answerer found no answer in the passages. */
    public static GroundedAnswer insufficientEvidence() {
        return new GroundedAnswer("", true);
    }
}
