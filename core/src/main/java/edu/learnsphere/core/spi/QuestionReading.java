package edu.learnsphere.core.spi;

import java.util.Objects;

/**
 * What a learner's message asks, as {@link QuestionReader#read} understood it.
 *
 * @param kind     whether the message is a question to answer from the material
 * @param question for {@link Kind#QUESTION}: the question written to stand on its own (a follow-up such as
 *                 "give an example" becomes "Give an example of a context switch"); otherwise the message
 */
public record QuestionReading(Kind kind, String question) {

    /** The kinds of message the ask pipeline treats differently. */
    public enum Kind {
        /** A question about the course: searched in the material and answered with citations. */
        QUESTION,
        /** A greeting or a question about the assistant itself ("hello", "what can you do?"). */
        GREETING,
        /** A request that names no subject ("tell me about this topic") with nothing earlier to say which. */
        VAGUE
    }

    public QuestionReading {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(question, "question");
    }

    /** The message as a question, unchanged. */
    public static QuestionReading asIs(String message) {
        return new QuestionReading(Kind.QUESTION, message);
    }
}
