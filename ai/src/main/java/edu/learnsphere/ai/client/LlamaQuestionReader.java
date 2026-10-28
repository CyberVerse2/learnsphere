package edu.learnsphere.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import edu.learnsphere.ai.prompt.JsonSchemas;
import edu.learnsphere.ai.prompt.Prompts;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.QuestionReader;
import edu.learnsphere.core.spi.QuestionReading;
import edu.learnsphere.core.util.CancellationToken;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * {@link QuestionReader} on the chat model at {@link ChatPriority#INTERACTIVE}: one short JSON reply
 * ({@code read-system.txt}) that says whether the message is a question, a greeting or a request naming no
 * subject, and for a question gives it as one that stands on its own. Thread-safe.
 */
public final class LlamaQuestionReader implements QuestionReader {

    /** Room for the kind and a rewritten question. */
    static final int MAX_TOKENS = 200;

    private final LocalChat chat;

    public LlamaQuestionReader(LocalChat chat) {
        this.chat = Objects.requireNonNull(chat, "chat");
    }

    @Override
    public QuestionReading read(String message, List<Turn> earlier, CancellationToken token) {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(earlier, "earlier");
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        String user = Prompts.READ_USER.render(Map.of(
                "earlier", earlier.isEmpty() ? "(none)" : earlier.stream()
                        .map(turn -> "Student: " + TextCleanup.oneLine(turn.question()) + "\nAssistant: "
                                + TextCleanup.oneLine(turn.answer()))
                        .collect(Collectors.joining("\n\n")),
                "message", TextCleanup.oneLine(message)));
        JsonNode reply = StructuredChat.complete(chat, ChatPriority.INTERACTIVE,
                List.of(ChatMessage.system(Prompts.READ_SYSTEM.render()), ChatMessage.user(user)),
                "reading", JsonSchemas.reading(), MAX_TOKENS, 0.0, token);
        QuestionReading.Kind kind = switch (reply.path("kind").asText()) {
            case "greeting" -> QuestionReading.Kind.GREETING;
            case "vague" -> QuestionReading.Kind.VAGUE;
            default -> QuestionReading.Kind.QUESTION;
        };
        String question = TextCleanup.oneLine(reply.path("question").asText(""));
        return new QuestionReading(kind, kind == QuestionReading.Kind.QUESTION && !question.isBlank() ? question : message);
    }
}
