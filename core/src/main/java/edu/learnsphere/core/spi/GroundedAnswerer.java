package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;
import java.util.List;

/**
 * The study assistant's side of a conversation about a course (implemented in the ai module, priority
 * {@link ChatPriority#INTERACTIVE}): it replies to whatever the learner writes (a question, a greeting, "teach me
 * something", "quiz me", an answer to its own quiz question) and takes every fact about the subject from the
 * numbered passages, citing them as {@code [n]}. When the passages don't cover what was asked, it says so in its
 * own words. Returns {@link GroundedAnswer#insufficientEvidence()} when it has no passages or writes nothing.
 */
public interface GroundedAnswerer {

    /**
     * Streams the reply to {@code sink} and returns the complete result. Blocks until done.
     *
     * @throws java.util.concurrent.CancellationException when {@code token} is cancelled
     */
    GroundedAnswer answer(AnswerRequest request, TokenSink sink, CancellationToken token);

    /** Replies to a single question with no conversation around it. */
    default GroundedAnswer answer(String question, List<Passage> passages, TokenSink sink, CancellationToken token) {
        return answer(AnswerRequest.question(question, passages), sink, token);
    }
}
