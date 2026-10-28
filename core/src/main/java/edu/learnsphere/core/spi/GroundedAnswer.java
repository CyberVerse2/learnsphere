package edu.learnsphere.core.spi;

import java.util.Objects;

/**
 * The finished result of {@link GroundedAnswerer#answer}: everything that was streamed, plus whether the
 * answerer had nothing to say.
 *
 * @param text         the full reply with {@code [n]} markers (empty when insufficient)
 * @param insufficient true when the answerer had no passages or wrote nothing
 */
public record GroundedAnswer(String text, boolean insufficient) {

    public GroundedAnswer {
        Objects.requireNonNull(text, "text");
    }

    /** The answerer had no passages or wrote nothing. */
    public static GroundedAnswer insufficientEvidence() {
        return new GroundedAnswer("", true);
    }
}
