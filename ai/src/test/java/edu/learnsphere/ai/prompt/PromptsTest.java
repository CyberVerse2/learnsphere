package edu.learnsphere.ai.prompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.ai.AiMessages;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.spi.Passage;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PromptsTest {

    private static Passage passage(int number, String text) {
        return new Passage(number, "c" + number, "d", "Notes", number, number, "Section " + number, text, 0);
    }

    @Test
    void rendersPlaceholdersLiterally() {
        PromptTemplate template = PromptTemplate.of("t", "Q: {{question}}\nP: {{passages}}");
        assertThat(template.placeholders()).containsExactlyInAnyOrder("question", "passages");
        assertThat(template.render(Map.of("question", "What is $1 {{x}}?", "passages", "[1] a")))
                .isEqualTo("Q: What is $1 {{x}}?\nP: [1] a");
    }

    @Test
    void aMissingValueIsAnError() {
        PromptTemplate template = PromptTemplate.of("t", "{{a}} {{b}}");
        assertThatThrownBy(() -> template.render(Map.of("a", "1"))).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("{{b}}");
    }

    @Test
    void everyPromptLoadsWithTheExpectedPlaceholders() throws IllegalAccessException {
        Map<PromptTemplate, Set<String>> expected = Map.ofEntries(
                Map.entry(Prompts.ANSWER_SYSTEM, Set.of()),
                Map.entry(Prompts.ANSWER_USER, Set.of("passages", "question")),
                Map.entry(Prompts.CONCEPTS_SYSTEM, Set.of("min", "max")),
                Map.entry(Prompts.CONCEPTS_USER, Set.of("module", "passages")),
                Map.entry(Prompts.ITEMS_SYSTEM, Set.of()),
                Map.entry(Prompts.ITEMS_USER, Set.of("passages", "concepts", "avoid", "plan")),
                Map.entry(Prompts.SUPPORT_SYSTEM, Set.of()),
                Map.entry(Prompts.SUPPORT_USER, Set.of("source", "passage", "type", "prompt", "answer")),
                Map.entry(Prompts.STATEMENT_SYSTEM, Set.of()),
                Map.entry(Prompts.STATEMENT_USER, Set.of("source", "passage", "statement")),
                Map.entry(Prompts.GRADE_SYSTEM, Set.of()),
                Map.entry(Prompts.GRADE_USER, Set.of("question", "reference", "context", "answer")),
                Map.entry(Prompts.PAGE_SYSTEM, Set.of()),
                Map.entry(Prompts.PAGE_USER, Set.of("page")),
                Map.entry(Prompts.FIGURES_SYSTEM, Set.of()),
                Map.entry(Prompts.FIGURES_USER, Set.of("page")));
        expected.forEach((template, placeholders) ->
                assertThat(template.placeholders()).as(template.name()).isEqualTo(placeholders));
        for (Field field : Prompts.class.getFields()) {
            if (field.getType() == PromptTemplate.class && Modifier.isStatic(field.getModifiers())) {
                assertThat(((PromptTemplate) field.get(null)).text()).as(field.getName()).isNotBlank();
            }
        }
    }

    @Test
    void theAnswerPromptTeachesTheSentinelAndCitations() {
        assertThat(Prompts.ANSWER_SYSTEM.text()).contains(Prompts.NOT_IN_MATERIAL).contains("[");
        assertThat(Prompts.ANSWER_EXAMPLE_ANSWER.text()).containsPattern("\\[\\d]");
    }

    @Test
    void formatsPassagesWithNumberSourceAndTidyText() {
        Passage p = new Passage(3, "c", "d", "OS notes", 4, 5, "Paging", "Pages   and\n\n\n\nframes  ", 0);
        assertThat(PassageFormat.format(p)).isEqualTo("[3] OS notes · pp. 4–5 · Paging\nPages and\n\nframes");
        Passage untitled = new Passage(1, "c", "d", "", 2, 2, null, "x", 0);
        assertThat(PassageFormat.format(untitled)).isEqualTo("[1] p. 2\nx");
    }

    @Test
    void fittingKeepsAnEvenSpreadAndTheOriginalNumbers() {
        List<Passage> passages = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            passages.add(passage(i, "x".repeat(960)));
        }
        List<Passage> all = PassageFormat.fit(passages, 100_000);
        assertThat(all).hasSize(10);
        List<Passage> some = PassageFormat.fit(passages, 5_000);
        assertThat(some).hasSizeBetween(2, 5);
        assertThat(some.getFirst().number()).isEqualTo(1);
        assertThat(some.getLast().number()).isGreaterThan(5);
        assertThat(some.stream().mapToInt(Passage::number)).isSorted();
    }

    @Test
    void fittingCutsAnOverlongPassage() {
        List<Passage> fitted = PassageFormat.fit(List.of(passage(1, "word ".repeat(2_000))), 1_000);
        assertThat(fitted).hasSize(1);
        assertThat(fitted.getFirst().text().length()).isLessThanOrEqualTo(1_000);
        assertThat(fitted.getFirst().text()).endsWith("…");
    }

    @Test
    void schemasListPropertiesInOrderAndAllowNothingElse() {
        var schema = JsonSchemas.support();
        assertThat(schema.path("required")).extracting(n -> n.asText()).containsExactly("evidence", "verdict", "note");
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        var tuple = JsonSchemas.tuple(List.of(JsonSchemas.string(), JsonSchemas.constant("x")));
        assertThat(tuple.path("prefixItems")).hasSize(2);
        assertThat(tuple.path("minItems").asInt()).isEqualTo(2);
        assertThat(tuple.path("maxItems").asInt()).isEqualTo(2);
        assertThat(tuple.has("items")).isFalse();
    }

    @Test
    void userFacingMessagesAvoidTechnicalWords() throws IllegalAccessException {
        List<String> texts = new ArrayList<>();
        for (Field field : AiMessages.class.getFields()) {
            if (field.getType() == UserMessage.class) {
                texts.add(((UserMessage) field.get(null)).text());
            }
        }
        assertThat(texts).isNotEmpty();
        for (String text : texts) {
            assertThat(text.toLowerCase()).as(text)
                    .doesNotContain("model", "server", "llama", "token", "index", "embedding", "chunk", "http",
                            "error", "runtime", "process", "gguf", "context");
        }
    }
}
