package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.GroundedAnswer;
import edu.learnsphere.core.spi.GroundedAnswerer;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.LockSupport;

/**
 * Answers with the first sentence of the first passage, cited as {@code [n]}, streamed word by word.
 * Returns insufficient evidence when there are no passages. An optional per-word delay makes streaming
 * visible in demo mode.
 */
public final class FakeGroundedAnswerer implements GroundedAnswerer {

    private final Duration wordDelay;

    public FakeGroundedAnswerer() {
        this(Duration.ZERO);
    }

    public FakeGroundedAnswerer(Duration wordDelay) {
        this.wordDelay = wordDelay;
    }

    @Override
    public GroundedAnswer answer(String question, List<Passage> passages, TokenSink sink, CancellationToken token) {
        token.throwIfCancelled();
        if (passages.isEmpty()) {
            return GroundedAnswer.insufficientEvidence();
        }
        Passage first = passages.getFirst();
        String sentence = FakeText.firstSentence(first.text());
        if (sentence.isEmpty()) {
            return GroundedAnswer.insufficientEvidence();
        }
        String body = sentence.substring(0, sentence.length() - 1);
        String text = "Your material says: " + body + " [" + first.number() + "]" + sentence.charAt(sentence.length() - 1);

        String[] pieces = text.split("(?<= )");
        for (String piece : pieces) {
            token.throwIfCancelled();
            sink.accept(piece);
            if (!wordDelay.isZero()) {
                LockSupport.parkNanos(wordDelay.toNanos());
                if (Thread.interrupted()) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Interrupted");
                }
            }
        }
        return new GroundedAnswer(text, false);
    }
}
