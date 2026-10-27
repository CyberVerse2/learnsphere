package edu.learnsphere.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import edu.learnsphere.ai.prompt.JsonSchemas;
import edu.learnsphere.ai.prompt.Prompts;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.FigureDescriber;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link FigureDescriber} on the vision chat model at {@link ChatPriority#BACKGROUND}: one or two
 * sentences per figure, in reading order, as JSON ({@code figures-system.txt}); empty when the page has no
 * meaningful figure. Thread-safe.
 */
public final class LlamaFigureDescriber implements FigureDescriber {

    /** Most figures described per page. */
    public static final int MAX_FIGURES = 6;

    private final LocalChat chat;

    public LlamaFigureDescriber(LocalChat chat) {
        this.chat = Objects.requireNonNull(chat, "chat");
    }

    @Override
    public List<String> describe(byte[] pngImage, int pageNumber, CancellationToken token) {
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        if (pngImage == null || pngImage.length == 0) {
            return List.of();
        }
        JsonNode reply = StructuredChat.complete(chat, ChatPriority.BACKGROUND, List.of(
                        ChatMessage.system(Prompts.FIGURES_SYSTEM.render()),
                        ChatMessage.userWithImage(ChatMessage.Image.png(pngImage),
                                Prompts.FIGURES_USER.render(Map.of("page", String.valueOf(pageNumber))))),
                "figures", JsonSchemas.figures(MAX_FIGURES), 900, 0.0, token);
        List<String> descriptions = new ArrayList<>();
        for (JsonNode figure : reply.path("figures")) {
            String text = TextCleanup.sentence(figure.path("description").asText(""));
            if (!text.isEmpty() && !descriptions.contains(text)) {
                descriptions.add(text);
            }
        }
        return descriptions;
    }
}
