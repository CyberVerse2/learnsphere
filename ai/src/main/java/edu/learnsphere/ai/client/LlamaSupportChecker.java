package edu.learnsphere.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import edu.learnsphere.ai.prompt.JsonSchemas;
import edu.learnsphere.ai.prompt.PassageFormat;
import edu.learnsphere.ai.prompt.Prompts;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.SupportChecker;
import edu.learnsphere.core.spi.SupportVerdict;
import edu.learnsphere.core.util.CancellationToken;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link SupportChecker} on the chat model at {@link ChatPriority#USER_WAITING}. The JSON reply makes the
 * model copy the deciding sentence from the passage before giving its verdict
 * ({@code support-system.txt}), which keeps verdicts tied to the text. Thread-safe.
 */
public final class LlamaSupportChecker implements SupportChecker {

    /** Passage text budget, in characters. */
    public static final int PASSAGE_BUDGET = 12_000;

    private static final String NO_SOURCE = "There's no source passage for this item.";

    private final LocalChat chat;

    public LlamaSupportChecker(LocalChat chat) {
        this.chat = Objects.requireNonNull(chat, "chat");
    }

    @Override
    public SupportVerdict check(String prompt, String answer, Passage passage, CancellationToken token) {
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        if (passage == null || passage.text().isBlank()) {
            return new SupportVerdict(false, NO_SOURCE);
        }
        Passage fitted = PassageFormat.fit(List.of(passage), PASSAGE_BUDGET).getFirst();
        String user = Prompts.SUPPORT_USER.render(Map.of(
                "source", source(fitted),
                "passage", fitted.text().strip(),
                "type", typeOf(answer, prompt),
                "prompt", TextCleanup.oneLine(prompt),
                "answer", TextCleanup.oneLine(answer)));
        JsonNode reply = StructuredChat.complete(chat, ChatPriority.USER_WAITING,
                List.of(ChatMessage.system(Prompts.SUPPORT_SYSTEM.render()), ChatMessage.user(user)),
                "support", JsonSchemas.support(), 500, 0.0, token);
        boolean supported = "supported".equals(reply.path("verdict").asText());
        String note = TextCleanup.sentence(reply.path("note").asText(""));
        if (note.isEmpty()) {
            note = supported ? "The source backs this answer." : "The source doesn't back this answer.";
        }
        return new SupportVerdict(supported, note);
    }

    /** A plain description of the item kind, inferred from its content (the interface takes no type). */
    static String typeOf(String answer, String prompt) {
        String a = answer == null ? "" : answer.strip();
        if (a.equalsIgnoreCase("true") || a.equalsIgnoreCase("false")) {
            return "true/false statement";
        }
        if (prompt != null && prompt.contains("____")) {
            return "fill in the blank (____)";
        }
        return "question";
    }

    private static String source(Passage passage) {
        String title = passage.documentTitle().isBlank() ? "" : passage.documentTitle().strip() + ", ";
        return title + passage.pageLabel();
    }
}
