package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;
import java.util.List;
import java.util.Objects;

/**
 * Reads a learner's message before the ask pipeline searches: tells questions from greetings and from
 * requests that name no subject, and rewrites a follow-up into a question that stands on its own, using the
 * conversation so far (implemented in the ai module, priority {@link ChatPriority#INTERACTIVE}).
 */
public interface QuestionReader {

    /** Takes every message as a question, unchanged (when no study assistant reads messages). */
    QuestionReader AS_IS = (message, earlier, token) -> QuestionReading.asIs(message);

    /**
     * One earlier question of the conversation and the answer it got.
     *
     * @param answer the answer text without citation markers, possibly shortened
     */
    record Turn(String question, String answer) {

        public Turn {
            Objects.requireNonNull(question, "question");
            Objects.requireNonNull(answer, "answer");
        }
    }

    /**
     * Reads {@code message}. Blocks until done.
     *
     * @param earlier the conversation's latest answered questions, oldest first; empty for a first question
     * @throws java.util.concurrent.CancellationException when {@code token} is cancelled
     */
    QuestionReading read(String message, List<Turn> earlier, CancellationToken token);
}
