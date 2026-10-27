package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;
import java.util.List;

/**
 * Answers a question only from numbered passages, citing them as {@code [n]} (implemented in the ai
 * module, priority {@link ChatPriority#INTERACTIVE}). Never called with an empty passage list by the
 * ask pipeline, but must return {@link GroundedAnswer#insufficientEvidence()} if it is.
 */
public interface GroundedAnswerer {

    /**
     * Streams the answer to {@code sink} and returns the complete result. Blocks until done.
     *
     * @throws java.util.concurrent.CancellationException when {@code token} is cancelled
     */
    GroundedAnswer answer(String question, List<Passage> passages, TokenSink sink, CancellationToken token);
}
