package edu.learnsphere.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.learnsphere.ai.client.LlamaItemGenerator.Slot;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.spi.ItemDraft;
import edu.learnsphere.core.spi.ItemGenerationRequest;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.util.Json;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LlamaItemGeneratorTest {

    private static final List<Passage> PASSAGES = List.of(LlamaImplementationsTest.PAGING, LlamaImplementationsTest.TLB,
            new Passage(3, "c3", "d1", "OS notes", 6, 6, "Deadlock", "Deadlock needs four conditions.", 0.5));

    private static ItemGenerationRequest request(Set<ItemType> types, int count, Difficulty difficulty, List<String> concepts) {
        return new ItemGenerationRequest(PASSAGES, concepts, types, count, difficulty);
    }

    private static Slot slot(ItemType type) {
        return new Slot(0, type, Difficulty.MEDIUM, type == ItemType.TRUE_FALSE ? Boolean.FALSE : null, 1, null);
    }

    private static Optional<ItemDraft> validate(ItemType type, String json) {
        return LlamaItemGenerator.validate(Json.tree(json), slot(type), PASSAGES, List.of());
    }

    // ------------------------------------------------------------------ plan

    @Test
    void thePlanCyclesTypesDifficultiesPassagesAndConcepts() {
        List<Slot> plan = LlamaItemGenerator.plan(request(EnumSet.allOf(ItemType.class), 10, null, List.of("Paging", "TLB")),
                PASSAGES);

        assertThat(plan).hasSize(10);
        assertThat(plan).extracting(Slot::type).containsExactly(ItemType.MCQ, ItemType.TRUE_FALSE, ItemType.SHORT_ANSWER,
                ItemType.FLASHCARD, ItemType.CLOZE, ItemType.MCQ, ItemType.TRUE_FALSE, ItemType.SHORT_ANSWER,
                ItemType.FLASHCARD, ItemType.CLOZE);
        // Each type gets a different difficulty the second time round.
        assertThat(plan.get(0).difficulty()).isNotEqualTo(plan.get(5).difficulty());
        assertThat(plan.get(1).difficulty()).isNotEqualTo(plan.get(6).difficulty());
        assertThat(plan.stream().map(Slot::difficulty).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder(Difficulty.values());
        // True/false statements alternate between true and false.
        assertThat(plan.get(1).truth()).isTrue();
        assertThat(plan.get(6).truth()).isFalse();
        assertThat(plan.get(0).truth()).isNull();
        // Each concept goes with the passage that explains it.
        assertThat(plan).extracting(Slot::concept).containsExactly("Paging", "TLB", "Paging", "TLB", "Paging", "TLB",
                "Paging", "TLB", "Paging", "TLB");
        assertThat(plan).extracting(Slot::passage).containsExactly(1, 2, 1, 2, 1, 2, 1, 2, 1, 2);
    }

    @Test
    void withoutConceptsPassagesAreTakenInTurn() {
        List<Slot> plan = LlamaItemGenerator.plan(request(EnumSet.of(ItemType.FLASHCARD), 4, null, List.of()), PASSAGES);
        assertThat(plan).extracting(Slot::passage).containsExactly(1, 2, 3, 1);
    }

    @Test
    void conceptsArePairedWithThePassagesThatExplainThem() {
        assertThat(LlamaItemGenerator.passagesAbout("Deadlock conditions", PASSAGES)).extracting(Passage::number)
                .containsExactly(3);
        assertThat(LlamaItemGenerator.passagesAbout("Page-table entries", PASSAGES)).extracting(Passage::number)
                .containsExactly(2);
        assertThat(LlamaItemGenerator.passagesAbout("Scheduling", PASSAGES)).isEmpty();
        // A concept nobody explains falls back to taking passages in turn.
        List<Slot> plan = LlamaItemGenerator.plan(request(EnumSet.of(ItemType.FLASHCARD), 3, null, List.of("Scheduling")),
                PASSAGES);
        assertThat(plan).extracting(Slot::passage).containsExactly(1, 2, 3);
    }

    @Test
    void aSingleTypeStillGetsEveryDifficulty() {
        List<Slot> plan = LlamaItemGenerator.plan(request(EnumSet.of(ItemType.MCQ), 6, null, List.of()), PASSAGES);
        assertThat(plan).extracting(Slot::difficulty).containsExactly(Difficulty.MEDIUM, Difficulty.EASY, Difficulty.HARD,
                Difficulty.MEDIUM, Difficulty.EASY, Difficulty.HARD);
        assertThat(plan).extracting(Slot::concept).containsOnlyNulls();
    }

    @Test
    void aWantedDifficultyAppliesToEverySlot() {
        List<Slot> plan = LlamaItemGenerator.plan(request(EnumSet.of(ItemType.CLOZE, ItemType.MCQ), 4, Difficulty.HARD,
                List.of()), PASSAGES);
        assertThat(plan).extracting(Slot::difficulty).containsOnly(Difficulty.HARD);
    }

    @Test
    void thePlanTextDescribesEachSlotPlainly() {
        List<Slot> plan = LlamaItemGenerator.plan(request(EnumSet.of(ItemType.TRUE_FALSE), 2, Difficulty.EASY,
                List.of("TLB")), PASSAGES);
        assertThat(LlamaItemGenerator.planText(plan)).isEqualTo("""
                1. true_false, easy, a TRUE statement, testing "TLB", from passage [2]
                2. true_false, easy, a FALSE statement, testing "TLB", from passage [2]""");
    }

    @Test
    void theSlotSchemaFixesTypeDifficultyAndTruth() {
        Slot tf = new Slot(0, ItemType.TRUE_FALSE, Difficulty.HARD, Boolean.FALSE, 2, null);
        ObjectNode schema = LlamaItemGenerator.slotSchema(tf, List.of(1, 2, 3), List.of("Paging", "TLB", "Deadlock"));
        JsonNode props = schema.path("properties");

        assertThat(props.path("type").path("const").asText()).isEqualTo("true_false");
        assertThat(props.path("difficulty").path("const").asText()).isEqualTo("hard");
        assertThat(props.path("answer").path("const").asText()).isEqualTo("false");
        assertThat(props.path("passage").path("enum")).extracting(JsonNode::asInt).containsExactly(1, 2, 3);
        assertThat(props.path("concepts").path("maxItems").asInt()).isEqualTo(2);
        assertThat(schema.path("additionalProperties").asBoolean(true)).isFalse();
        // A statement is written after its truth value and the true fact it alters; the passage is named
        // once the item is written.
        List<String> order = new java.util.ArrayList<>();
        props.fieldNames().forEachRemaining(order::add);
        assertThat(order).containsSubsequence("answer", "fact", "prompt", "explanation", "passage", "concepts");
        ObjectNode trueStatement = LlamaItemGenerator.slotSchema(
                new Slot(0, ItemType.TRUE_FALSE, Difficulty.EASY, Boolean.TRUE, 1, null), List.of(1), List.of());
        assertThat(trueStatement.path("properties").path("answer").path("const").asText()).isEqualTo("true");
        assertThat(trueStatement.path("properties").has("fact")).isTrue();

        ObjectNode mcq = LlamaItemGenerator.slotSchema(slot(ItemType.MCQ), List.of(1), List.of());
        assertThat(mcq.path("properties").path("distractors").path("minItems").asInt()).isEqualTo(3);
        assertThat(mcq.path("properties").has("concepts")).isFalse();
    }

    // ------------------------------------------------------------------ validation

    @Test
    void acceptsAGoodMultipleChoiceItem() {
        Optional<ItemDraft> draft = validate(ItemType.MCQ, """
                {"passage":1,"prompt":"What does paging divide physical memory into?","answer":"Frames",
                 "distractors":["Segments","Sectors","Blocks of variable size"],"explanation":"frames"}""");
        assertThat(draft).hasValueSatisfying(d -> {
            assertThat(d.distractors()).containsExactly("Segments", "Sectors", "Blocks of variable size");
            assertThat(d.difficulty()).isEqualTo(Difficulty.MEDIUM);
            assertThat(d.passageNumber()).isEqualTo(1);
        });
    }

    @Test
    void rejectsPromptsThatMentionTheSourceOrEchoTheInstructions() {
        assertThat(validate(ItemType.SHORT_ANSWER,
                "{\"passage\":1,\"prompt\":\"What does the passage say about frames?\",\"answer\":\"x\"}")).isEmpty();
        assertThat(validate(ItemType.SHORT_ANSWER,
                "{\"passage\":1,\"prompt\":\"Why are frames fixed-size [1]?\",\"answer\":\"x\"}")).isEmpty();
        assertThat(validate(ItemType.SHORT_ANSWER,
                "{\"passage\":1,\"prompt\":\"Here is a question: what is a frame?\",\"answer\":\"x\"}")).isEmpty();
        assertThat(validate(ItemType.MCQ, """
                {"passage":1,"prompt":"Multiple-choice: what is a frame?","answer":"A block","distractors":["a","b"]}""")).isEmpty();
    }

    @Test
    void stripsLeadingLabelsAndSourcePhrases() {
        assertThat(LlamaItemGenerator.stripMeta("According to the passage, the TLB caches entries.")).isEqualTo(
                "The TLB caches entries.");
        assertThat(LlamaItemGenerator.stripMeta("Statement: frames have a fixed size.")).isEqualTo("Frames have a fixed size.");
        assertThat(LlamaItemGenerator.stripMeta("Q: What is a frame?")).isEqualTo("What is a frame?");
    }

    @Test
    void multipleChoiceNeedsTwoRealDistractorsAndATextAnswer() {
        assertThat(validate(ItemType.MCQ, """
                {"passage":1,"prompt":"What is a frame?","answer":"A fixed-size block of memory",
                 "distractors":["None of the above","A fixed-size block of memory","A file"]}""")).isEmpty();
        assertThat(validate(ItemType.MCQ, """
                {"passage":1,"prompt":"What is a frame?","answer":"B","distractors":["A file","A disk","A cache"]}""")).isEmpty();
    }

    @Test
    void trueFalseStatementsTakeThePlannedAnswerAndAreNotQuestions() {
        assertThat(validate(ItemType.TRUE_FALSE, "{\"passage\":2,\"prompt\":\"The TLB is stored on disk.\",\"answer\":\"true\"}"))
                .hasValueSatisfying(d -> assertThat(d.answer()).isEqualTo("false"));
        assertThat(validate(ItemType.TRUE_FALSE, "{\"passage\":2,\"prompt\":\"Is the TLB on disk?\",\"answer\":\"false\"}"))
                .isEmpty();
    }

    @Test
    void clozeItemsHaveExactlyOneBlankThatHidesAShortAnswer() {
        assertThat(validate(ItemType.CLOZE, "{\"passage\":2,\"prompt\":\"The [blank] caches page-table entries.\",\"answer\":\"TLB.\"}"))
                .hasValueSatisfying(d -> {
                    assertThat(d.prompt()).isEqualTo("The " + ItemType.CLOZE_BLANK + " caches page-table entries.");
                    assertThat(d.answer()).isEqualTo("TLB");
                });
        assertThat(validate(ItemType.CLOZE, "{\"passage\":2,\"prompt\":\"The ____ caches ____ entries.\",\"answer\":\"TLB\"}"))
                .isEmpty();
        assertThat(validate(ItemType.CLOZE, "{\"passage\":2,\"prompt\":\"The TLB caches entries.\",\"answer\":\"TLB\"}"))
                .isEmpty();
        assertThat(validate(ItemType.CLOZE, "{\"passage\":2,\"prompt\":\"The ____ (TLB) caches entries.\",\"answer\":\"TLB\"}"))
                .isEmpty();
        assertThat(validate(ItemType.CLOZE, """
                {"passage":2,"prompt":"The TLB ____.","answer":"is a small hardware cache of recently used entries"}"""))
                .isEmpty();
    }

    @Test
    void aFalseStatementMustChangeTheFactItIsBuiltFrom() {
        assertThat(validate(ItemType.TRUE_FALSE, """
                {"passage":2,"answer":"false","fact":"The TLB caches page-table entries.",
                 "prompt":"The TLB caches page-table entries.","explanation":"It does."}""")).isEmpty();
        assertThat(validate(ItemType.TRUE_FALSE, """
                {"passage":2,"answer":"false","fact":"The TLB caches page-table entries.",
                 "prompt":"The TLB caches disk blocks.","explanation":""}"""))
                .hasValueSatisfying(d -> {
                    assertThat(d.answer()).isEqualTo("false");
                    assertThat(d.explanation()).isEqualTo("The TLB caches page-table entries.");
                });
    }

    @Test
    void anExplanationAboutWritingTheItemMeansTheModelLostItsWay() {
        assertThat(validate(ItemType.TRUE_FALSE, """
                {"passage":2,"answer":"false","prompt":"The TLB is stored on disk.",
                 "explanation":"The statement is true, so let's rephrase the prompt to be false."}""")).isEmpty();
    }

    @Test
    void explanationsReadWithoutPassageReferences() {
        assertThat(LlamaItemGenerator.cleanExplanation("Passage 1 states that all threads share the address space."))
                .isEqualTo("All threads share the address space.");
        assertThat(LlamaItemGenerator.cleanExplanation("According to passage [8], virtual memory keeps part of a process on disk."))
                .isEqualTo("Virtual memory keeps part of a process on disk.");
        assertThat(LlamaItemGenerator.cleanExplanation("Passage [3] defines the time slice as a quantum [3]."))
                .isEqualTo("The material defines the time slice as a quantum.");
        assertThat(LlamaItemGenerator.cleanExplanation("frames hold pages")).isEqualTo("Frames hold pages.");
    }

    @Test
    void anItemIsCreditedToThePassageItsWordsComeFrom() {
        assertThat(LlamaItemGenerator.sourcePassage("The TLB caches recently used page-table entries in hardware.", 1,
                PASSAGES)).isEqualTo(2);
        // A weak or tied match keeps the label.
        assertThat(LlamaItemGenerator.sourcePassage("Memory", 3, PASSAGES)).isEqualTo(3);
        assertThat(LlamaItemGenerator.sourcePassage("Paging divides memory into frames", 1, PASSAGES)).isEqualTo(1);
        ItemDraft relabelled = validate(ItemType.FLASHCARD, """
                {"passage":1,"prompt":"Deadlock","answer":"Four conditions must hold for deadlock."}""").orElseThrow();
        assertThat(relabelled.passageNumber()).isEqualTo(3);
    }

    @Test
    void aPassageNumberOutsideThePromptIsRejected() {
        assertThat(validate(ItemType.FLASHCARD, "{\"passage\":9,\"prompt\":\"TLB\",\"answer\":\"A cache\"}")).isEmpty();
    }

    @Test
    void conceptTagsMatchKnownConceptsOrFallBackToThePlannedOne() {
        Slot planned = new Slot(0, ItemType.FLASHCARD, Difficulty.EASY, null, 1, "Paging");
        List<String> known = List.of("Paging", "TLB");
        ItemDraft tagged = LlamaItemGenerator.validate(Json.tree(
                "{\"passage\":2,\"prompt\":\"TLB\",\"answer\":\"A cache\",\"concepts\":[\"tlb\",\"Unknown\"]}"),
                planned, PASSAGES, known).orElseThrow();
        assertThat(tagged.conceptNames()).containsExactly("TLB");
        ItemDraft untagged = LlamaItemGenerator.validate(Json.tree(
                "{\"passage\":1,\"prompt\":\"Frame\",\"answer\":\"A block\"}"), planned, PASSAGES, known).orElseThrow();
        assertThat(untagged.conceptNames()).containsExactly("Paging");
    }
}
