package edu.learnsphere.ai.fake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import edu.learnsphere.core.ask.CitationValidator;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.spi.AiCapabilities;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.ConceptDraft;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.core.spi.GroundedAnswer;
import edu.learnsphere.core.spi.ItemDraft;
import edu.learnsphere.core.spi.ItemGenerationRequest;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Subscription;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

class FakesTest {

    private static final Passage CELLS = new Passage(1, "chunk-1", "doc-1", "Biology notes", 12, 12, "Cells",
            "Mitochondria produce most of the energy a cell needs. They convert glucose into ATP. "
                    + "Mitochondria have their own DNA.", 0.9);
    private static final Passage PLANTS = new Passage(2, "chunk-2", "doc-1", "Biology notes", 14, 15, "Plants",
            "Photosynthesis takes place in chloroplasts. Chloroplasts capture light energy.", 0.8);

    private static double dot(float[] a, float[] b) {
        double sum = 0;
        for (int i = 0; i < a.length; i++) {
            sum += a[i] * b[i];
        }
        return sum;
    }

    @Test
    void embedderMakesNormalizedDeterministicVectors() {
        FakeEmbedder embedder = new FakeEmbedder();
        List<float[]> vectors = embedder.embed(List.of("Mitochondria make ATP", "mitochondria make atp!",
                "Chloroplasts capture light", ""), EmbedKind.DOCUMENT, CancellationToken.NONE);

        assertThat(vectors).hasSize(4).allSatisfy(v -> {
            assertThat(v).hasSize(FakeEmbedder.DEFAULT_DIMENSIONS);
            assertThat(dot(v, v)).isCloseTo(1.0, within(1e-5));
        });
        assertThat(vectors.get(0)).containsExactly(vectors.get(1));
        assertThat(dot(vectors.get(0), embedder.embed("How do mitochondria make ATP?", EmbedKind.QUERY)))
                .isGreaterThan(dot(vectors.get(2), embedder.embed("How do mitochondria make ATP?", EmbedKind.QUERY)));
        assertThat(embedder.dimensions()).isEqualTo(1024);
        assertThat(embedder.modelName()).isEqualTo(FakeEmbedder.MODEL_NAME);
        assertThat(new FakeEmbedder(16).embed("x y", EmbedKind.QUERY)).hasSize(16);
    }

    @Test
    void embedderHonoursCancellation() {
        CancellationToken token = CancellationToken.create();
        token.cancel();
        assertThatThrownBy(() -> new FakeEmbedder().embed(List.of("a"), EmbedKind.QUERY, token))
                .isInstanceOf(CancellationException.class);
    }

    @Test
    void rerankerScoresByQueryWordOverlap() {
        double[] scores = new FakeReranker().score("Where does photosynthesis happen in plants?",
                List.of(CELLS.text(), PLANTS.text(), ""), CancellationToken.NONE);
        assertThat(scores[1]).isGreaterThan(scores[0]);
        assertThat(scores[0]).isZero();
        assertThat(scores[2]).isZero();
        assertThat(java.util.Arrays.stream(scores).boxed().toList()).allSatisfy(s -> assertThat(s).isBetween(0.0, 1.0));
        assertThat(new FakeReranker().score("the of", List.of("anything"), CancellationToken.NONE)).containsExactly(0.0);
    }

    @Test
    void answererStreamsACitedAnswerThatPassesValidation() {
        List<String> tokens = new ArrayList<>();
        GroundedAnswer answer = new FakeGroundedAnswerer().answer("What do mitochondria do?", List.of(CELLS, PLANTS),
                tokens::add, CancellationToken.NONE);

        assertThat(answer.insufficient()).isFalse();
        assertThat(answer.text()).isEqualTo("Your material says: Mitochondria produce most of the energy a cell needs [1].");
        assertThat(String.join("", tokens)).isEqualTo(answer.text());
        assertThat(tokens.size()).isGreaterThan(5);

        CitationValidator.Result validated = CitationValidator.validate(answer.text(), List.of(CELLS, PLANTS));
        assertThat(validated.citations()).singleElement().satisfies(c -> {
            assertThat(c.number()).isEqualTo(1);
            assertThat(c.pageStart()).isEqualTo(12);
        });
    }

    @Test
    void answererReportsInsufficientEvidenceWithoutPassages() {
        List<String> tokens = new ArrayList<>();
        GroundedAnswer answer = new FakeGroundedAnswerer().answer("Anything?", List.of(), tokens::add, CancellationToken.NONE);
        assertThat(answer.insufficient()).isTrue();
        assertThat(tokens).isEmpty();
    }

    @Test
    void answererStopsWhenCancelled() {
        CancellationToken token = CancellationToken.create();
        List<String> tokens = new ArrayList<>();
        assertThatThrownBy(() -> new FakeGroundedAnswerer().answer("q", List.of(CELLS), t -> {
            tokens.add(t);
            if (tokens.size() == 3) {
                token.cancel();
            }
        }, token)).isInstanceOf(CancellationException.class);
        assertThat(tokens).hasSize(3);
    }

    @Test
    void generatorMakesOneValidDraftPerType() {
        ItemGenerationRequest request = new ItemGenerationRequest(List.of(CELLS, PLANTS), List.of("Cell energy"),
                Set.of(), ItemType.values().length, Difficulty.HARD);
        List<ItemDraft> drafts = new FakeItemGenerator().generate(request, CancellationToken.NONE);

        assertThat(drafts).extracting(ItemDraft::type).containsExactly(ItemType.values());
        assertThat(drafts).allSatisfy(FakesTest::assertValid);
        assertThat(drafts).allSatisfy(d -> {
            assertThat(d.difficulty()).isEqualTo(Difficulty.HARD);
            assertThat(d.conceptNames()).containsExactly("Cell energy");
            assertThat(d.passageNumber()).isIn(1, 2);
        });
        assertThat(drafts).extracting(ItemDraft::passageNumber).containsExactly(1, 2, 1, 2, 1);
    }

    @Test
    void generatorHonoursTypesAndCount() {
        ItemGenerationRequest request = new ItemGenerationRequest(List.of(CELLS), List.of(),
                Set.of(ItemType.CLOZE, ItemType.MCQ), 3, null);
        List<ItemDraft> drafts = new FakeItemGenerator().generate(request, CancellationToken.NONE);
        assertThat(drafts).extracting(ItemDraft::type).containsExactly(ItemType.MCQ, ItemType.CLOZE, ItemType.MCQ);
        assertThat(drafts).allSatisfy(FakesTest::assertValid);
        assertThat(new FakeItemGenerator().generate(new ItemGenerationRequest(List.of(), null, null, 2, null),
                CancellationToken.NONE)).isEmpty();
    }

    /** The content conventions documented on {@link ItemType}. */
    static void assertValid(ItemDraft draft) {
        assertThat(draft.prompt()).isNotBlank();
        assertThat(draft.answer()).isNotBlank();
        switch (draft.type()) {
            case MCQ -> {
                assertThat(draft.distractors()).hasSizeBetween(2, 4).doesNotHaveDuplicates().doesNotContain(draft.answer());
            }
            case TRUE_FALSE -> assertThat(draft.answer()).isIn("true", "false");
            case CLOZE -> {
                assertThat(draft.prompt().split(ItemType.CLOZE_BLANK, -1)).hasSize(2);
                assertThat(draft.prompt()).doesNotContain(draft.answer());
            }
            case SHORT_ANSWER, FLASHCARD -> assertThat(draft.distractors()).isEmpty();
        }
    }

    @Test
    void conceptExtractorNamesFrequentWords() {
        List<ConceptDraft> concepts = new FakeConceptExtractor().extract("Cells", List.of(CELLS, PLANTS), CancellationToken.NONE);
        assertThat(concepts).hasSizeLessThanOrEqualTo(FakeConceptExtractor.MAX_CONCEPTS);
        assertThat(concepts).extracting(ConceptDraft::name).startsWith("Chloroplasts", "Energy", "Mitochondria");
        assertThat(concepts.getFirst().description()).isEqualTo("Photosynthesis takes place in chloroplasts.");
        assertThat(new FakeConceptExtractor().extract("Empty", List.of(), CancellationToken.NONE)).isEmpty();
    }

    @Test
    void supportCheckerComparesTheAnswerWithThePassage() {
        FakeSupportChecker checker = new FakeSupportChecker();
        assertThat(checker.check("What makes ATP?", "Mitochondria convert glucose into ATP", CELLS, CancellationToken.NONE)
                .supported()).isTrue();
        assertThat(checker.check("What makes ATP?", "Ribosomes in the nucleus", CELLS, CancellationToken.NONE)
                .supported()).isFalse();
        assertThat(checker.check("q", "a", null, CancellationToken.NONE).supported()).isFalse();
    }

    @Test
    void graderScoresByReferenceCoverage() {
        FakeShortAnswerGrader grader = new FakeShortAnswerGrader();
        String reference = "Mitochondria convert glucose into ATP";

        GradeResult full = grader.grade("q", reference, "They convert glucose to ATP in mitochondria", CELLS, CancellationToken.NONE);
        assertThat(full.score()).isEqualTo(1.0);
        assertThat(full.verdict()).isEqualTo(GradeResult.Verdict.CORRECT);
        assertThat(full.missingPoints()).isEmpty();

        GradeResult partial = grader.grade("q", reference, "Mitochondria use glucose", CELLS, CancellationToken.NONE);
        assertThat(partial.score()).isEqualTo(0.5);
        assertThat(partial.verdict()).isEqualTo(GradeResult.Verdict.PARTIAL);
        assertThat(partial.missingPoints()).containsExactly("convert", "atp");

        GradeResult wrong = grader.grade("q", reference, "No idea", CELLS, CancellationToken.NONE);
        assertThat(wrong.score()).isZero();
        assertThat(wrong.verdict()).isEqualTo(GradeResult.Verdict.INCORRECT);
        assertThat(wrong.feedback()).isNotBlank();
    }

    @Test
    void pageReaderAndFigureDescriberAreDeterministic() {
        byte[] png = {1, 2, 3};
        assertThat(new FakePageReader().read(png, 4, CancellationToken.NONE).markdown()).contains("page 4");
        assertThat(new FakePageReader().read(new byte[0], 4, CancellationToken.NONE).hasIllegibleParts()).isTrue();
        assertThat(new FakeFigureDescriber().describe(png, 4, CancellationToken.NONE)).hasSize(1);
        assertThat(new FakeFigureDescriber().describe(new byte[0], 4, CancellationToken.NONE)).isEmpty();
    }

    @Test
    void runtimeIsReadyAndNotifiesListeners() {
        try (FakeAiRuntime runtime = new FakeAiRuntime()) {
            assertThat(runtime.status().isReady()).isTrue();
            assertThat(runtime.status().model()).isEqualTo(ModelChoice.CHAT_4B);
            assertThat(runtime.availableModels()).containsExactlyInAnyOrder(ModelChoice.CHAT_4B, ModelChoice.CHAT_9B);

            List<AiStatus> seen = new ArrayList<>();
            Subscription subscription = runtime.addListener(seen::add);
            runtime.start(ModelChoice.CHAT_9B);
            runtime.setStatus(new AiStatus(AiStatus.State.FAILED, ModelChoice.CHAT_9B, "crashed"));
            runtime.start(ModelChoice.AUTO);
            subscription.close();
            runtime.stop();

            assertThat(seen).extracting(AiStatus::state)
                    .containsExactly(AiStatus.State.READY, AiStatus.State.FAILED, AiStatus.State.READY);
            assertThat(seen.getLast().model()).isEqualTo(ModelChoice.CHAT_4B);
            assertThat(runtime.status().state()).isEqualTo(AiStatus.State.UNAVAILABLE);
        }
    }

    @Test
    void bundleWiresOneOfEach() {
        FakeAi ai = FakeAi.create();
        assertThat(ai.runtime().status().isReady()).isTrue();
        assertThat(ai.embedder().dimensions()).isEqualTo(FakeEmbedder.DEFAULT_DIMENSIONS);
        assertThat(ai.answerer()).isNotNull();
        assertThat(ai.figureDescriber()).isNotNull();
    }

    @Test
    void fillsEveryAiSlotOfTheCapabilities() {
        FakeAi ai = FakeAi.create();
        AiCapabilities slots = ai.capabilities();

        assertThat(slots.runtime()).isSameAs(ai.runtime());
        assertThat(slots.embedder()).isSameAs(ai.embedder());
        assertThat(slots.reranker()).isSameAs(ai.reranker());
        assertThat(slots.answerer()).isSameAs(ai.answerer());
        assertThat(slots.conceptExtractor()).isSameAs(ai.conceptExtractor());
        assertThat(slots.itemGenerator()).isSameAs(ai.itemGenerator());
        assertThat(slots.supportChecker()).isSameAs(ai.supportChecker());
        assertThat(slots.grader()).isSameAs(ai.grader());
        assertThat(slots.pageReader()).isSameAs(ai.pageReader());
        assertThat(slots.figureDescriber()).isSameAs(ai.figureDescriber());
    }
}
