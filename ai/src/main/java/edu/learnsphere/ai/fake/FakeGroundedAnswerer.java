package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.AnswerRequest;
import edu.learnsphere.core.spi.GroundedAnswer;
import edu.learnsphere.core.spi.GroundedAnswerer;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.locks.LockSupport;

/**
 * Replies by rule, streamed word by word: a greeting gets a greeting, "quiz me" gets a question about the first
 * passage, a message that shares a word with a passage gets that passage's first sentence cited as {@code [n]},
 * and anything else gets a sentence saying the course doesn't cover it. Returns insufficient evidence when there
 * are no passages. An optional per-word delay makes streaming visible in demo mode.
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
    public GroundedAnswer answer(AnswerRequest request, TokenSink sink, CancellationToken token) {
        token.throwIfCancelled();
        if (request.passages().isEmpty()) {
            return GroundedAnswer.insufficientEvidence();
        }
        String text = reply(request);
        if (text.isEmpty()) {
            return GroundedAnswer.insufficientEvidence();
        }

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

    private static String reply(AnswerRequest request) {
        String message = request.message().toLowerCase(Locale.ROOT);
        String course = request.course().isBlank() ? "this course" : request.course();
        if (message.matches("(hi|hello|hey)\\b.*")) {
            return "Hi! What would you like to go over in " + course + "?";
        }
        Passage first = request.passages().getFirst();
        if (message.contains("quiz") || message.contains("test me")) {
            String topic = first.sectionTitle() == null ? course : first.sectionTitle();
            return "Here's one: what does your course say about " + topic + "?";
        }
        Set<String> words = new HashSet<>();
        for (String word : message.split("[^a-z]+")) {
            if (word.length() > 4) {
                words.add(word);
            }
        }
        Passage match = request.passages().stream()
                .filter(p -> Arrays.stream(p.text().toLowerCase(Locale.ROOT).split("[^a-z]+")).anyMatch(words::contains))
                .findFirst().orElse(null);
        if (match == null) {
            return "Your course material doesn't cover that.";
        }
        String sentence = FakeText.firstSentence(match.text());
        if (sentence.isEmpty()) {
            return "";
        }
        String body = sentence.substring(0, sentence.length() - 1);
        return "Your material says: " + body + " [" + match.number() + "]" + sentence.charAt(sentence.length() - 1);
    }
}
