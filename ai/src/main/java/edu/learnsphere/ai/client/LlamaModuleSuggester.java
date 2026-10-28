package edu.learnsphere.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import edu.learnsphere.ai.prompt.JsonSchemas;
import edu.learnsphere.ai.prompt.Prompts;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.ModuleSuggester;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@link ModuleSuggester} on the chat model at {@link ChatPriority#INTERACTIVE}: one JSON reply
 * ({@code modules-system.txt}) listing modules by the number of the heading each starts at. Headings are numbered
 * from 1 and grouped under their PDF's title. Thread-safe.
 */
public final class LlamaModuleSuggester implements ModuleSuggester {

    /** Most modules asked for. */
    static final int MAX_MODULES = 12;
    /** Most headings shown; later ones join the last module. */
    static final int MAX_SECTIONS = 300;
    /** Room for every module's title and summary. */
    static final int MAX_TOKENS = 1_000;

    private final LocalChat chat;

    public LlamaModuleSuggester(LocalChat chat) {
        this.chat = Objects.requireNonNull(chat, "chat");
    }

    @Override
    public List<Suggestion> suggest(String course, List<Section> sections, CancellationToken token) {
        Objects.requireNonNull(course, "course");
        Objects.requireNonNull(sections, "sections");
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        if (sections.isEmpty()) {
            return List.of();
        }
        List<Section> shown = sections.subList(0, Math.min(sections.size(), MAX_SECTIONS));
        String user = Prompts.MODULES_USER.render(Map.of(
                "course", TextCleanup.oneLine(course),
                "sections", headings(shown)));
        JsonNode reply = StructuredChat.complete(chat, ChatPriority.INTERACTIVE,
                List.of(ChatMessage.system(Prompts.MODULES_SYSTEM.render(Map.of("max", String.valueOf(MAX_MODULES)))),
                        ChatMessage.user(user)),
                "modules", JsonSchemas.modules(shown.size(), MAX_MODULES), MAX_TOKENS, 0.0, token);
        List<Suggestion> suggestions = new ArrayList<>();
        for (JsonNode module : reply.path("modules")) {
            suggestions.add(new Suggestion(TextCleanup.oneLine(module.path("title").asText("")),
                    TextCleanup.oneLine(module.path("summary").asText("")), module.path("first_section").asInt(0) - 1));
        }
        return suggestions;
    }

    /** The headings numbered from 1, under a "PDF:" line each time the PDF changes. */
    static String headings(List<Section> sections) {
        StringBuilder text = new StringBuilder();
        String document = null;
        for (int i = 0; i < sections.size(); i++) {
            Section section = sections.get(i);
            if (!section.documentTitle().equals(document)) {
                document = section.documentTitle();
                text.append(text.isEmpty() ? "" : "\n").append("PDF: ").append(TextCleanup.oneLine(document)).append('\n');
            }
            text.append(i + 1).append(". ").append(TextCleanup.oneLine(section.heading())).append('\n');
        }
        return text.toString().stripTrailing();
    }
}
