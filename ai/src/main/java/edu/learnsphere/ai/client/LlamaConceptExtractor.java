package edu.learnsphere.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import edu.learnsphere.ai.prompt.JsonSchemas;
import edu.learnsphere.ai.prompt.PassageFormat;
import edu.learnsphere.ai.prompt.Prompts;
import edu.learnsphere.core.spi.ChatPriority;
import edu.learnsphere.core.spi.ConceptDraft;
import edu.learnsphere.core.spi.ConceptExtractor;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * {@link ConceptExtractor} on the chat model at {@link ChatPriority#USER_WAITING}: asks for
 * {@value #MIN_CONCEPTS}–{@value #MAX_CONCEPTS} concepts as JSON ({@code concepts-system.txt}), then drops
 * blank and duplicate names. A module with little text may yield fewer. Thread-safe.
 */
public final class LlamaConceptExtractor implements ConceptExtractor {

    public static final int MIN_CONCEPTS = 5;
    public static final int MAX_CONCEPTS = 15;
    /** Passage text budget per prompt, in characters (about 9,000 tokens). */
    public static final int PASSAGE_BUDGET = 36_000;
    /** Below this much passage text, fewer than {@value #MIN_CONCEPTS} concepts may be returned. */
    static final int SMALL_MODULE_CHARS = 1_500;

    private final LocalChat chat;

    public LlamaConceptExtractor(LocalChat chat) {
        this.chat = Objects.requireNonNull(chat, "chat");
    }

    @Override
    public List<ConceptDraft> extract(String moduleTitle, List<Passage> passages, CancellationToken token) {
        Objects.requireNonNull(passages, "passages");
        Objects.requireNonNull(token, "token");
        token.throwIfCancelled();
        if (passages.isEmpty()) {
            return List.of();
        }
        List<Passage> fitted = PassageFormat.fit(passages, PASSAGE_BUDGET);
        int chars = fitted.stream().mapToInt(p -> p.text().length()).sum();
        int min = chars < SMALL_MODULE_CHARS ? 1 : MIN_CONCEPTS;
        String system = Prompts.CONCEPTS_SYSTEM.render(Map.of(
                "min", String.valueOf(min), "max", String.valueOf(MAX_CONCEPTS)));
        String user = Prompts.CONCEPTS_USER.render(Map.of(
                "module", moduleTitle == null || moduleTitle.isBlank() ? "Untitled module" : moduleTitle.strip(),
                "passages", PassageFormat.format(fitted)));
        JsonNode reply = StructuredChat.complete(chat, ChatPriority.USER_WAITING,
                List.of(ChatMessage.system(system), ChatMessage.user(user)), "concepts",
                JsonSchemas.concepts(min, MAX_CONCEPTS), 1500, 0.2, token);
        return parse(reply);
    }

    static List<ConceptDraft> parse(JsonNode reply) {
        List<ConceptDraft> concepts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode node : reply.path("concepts")) {
            String name = TextCleanup.oneLine(node.path("name").asText("")).replaceAll("[.:;]+$", "");
            String key = TextCleanup.key(name);
            if (name.isEmpty() || name.length() > 80 || !seen.add(key)) {
                continue;
            }
            concepts.add(new ConceptDraft(TextCleanup.capitalize(name),
                    TextCleanup.sentence(node.path("description").asText(""))));
            if (concepts.size() == MAX_CONCEPTS) {
                break;
            }
        }
        return concepts;
    }
}
