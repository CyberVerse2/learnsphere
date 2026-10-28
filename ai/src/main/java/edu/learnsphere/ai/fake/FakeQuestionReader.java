package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.QuestionReader;
import edu.learnsphere.core.spi.QuestionReading;
import edu.learnsphere.core.util.CancellationToken;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Greetings by their first word; a message with "quiz", "practise" or "test me" asks to practise; a short message that points at a topic ("this topic", "it") without
 * naming one is vague in a new conversation, and in a running one becomes a question about the previous
 * question. Everything else is a question as typed.
 */
public final class FakeQuestionReader implements QuestionReader {

    private static final Set<String> GREETINGS = Set.of("hi", "hello", "hey", "thanks", "thank", "good", "yo");
    private static final Set<String> POINTERS = Set.of("this", "that", "it", "these", "those");

    @Override
    public QuestionReading read(String message, List<Turn> earlier, CancellationToken token) {
        token.throwIfCancelled();
        String[] words = message.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").strip().split("\\s+");
        if (words.length <= 4 && GREETINGS.contains(words[0])) {
            return new QuestionReading(QuestionReading.Kind.GREETING, message);
        }
        String text = String.join(" ", words);
        if (text.matches(".*\\b(quiz|practise|practice|test me)\\b.*")) {
            return new QuestionReading(QuestionReading.Kind.PRACTICE, message);
        }
        boolean points = words.length <= 8 && List.of(words).stream().anyMatch(POINTERS::contains);
        if (points && earlier.isEmpty()) {
            return new QuestionReading(QuestionReading.Kind.VAGUE, message);
        }
        if (points) {
            return QuestionReading.asIs(message.strip() + " (about: " + earlier.getLast().question().strip() + ")");
        }
        return QuestionReading.asIs(message);
    }
}
