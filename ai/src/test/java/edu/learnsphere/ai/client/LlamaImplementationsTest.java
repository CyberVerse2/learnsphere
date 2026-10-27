package edu.learnsphere.ai.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import edu.learnsphere.ai.AiMessages;
import edu.learnsphere.ai.client.FakeOpenAiServer.Reply;
import edu.learnsphere.ai.prompt.Prompts;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.spi.ConceptDraft;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.GradeResult;
import edu.learnsphere.core.spi.GroundedAnswer;
import edu.learnsphere.core.spi.ItemDraft;
import edu.learnsphere.core.spi.ItemGenerationRequest;
import edu.learnsphere.core.spi.PageTranscription;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.SupportVerdict;
import edu.learnsphere.core.util.CancellationToken;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Each AI interface implementation against a scripted server: what it sends and how it reads the reply. */
@Timeout(30)
class LlamaImplementationsTest {

    static final Passage PAGING = new Passage(1, "c1", "d1", "OS notes", 4, 4, "Paging",
            "Paging divides physical memory into fixed-size frames and logical memory into pages of the same size.", 0.9);
    static final Passage TLB = new Passage(2, "c2", "d1", "OS notes", 4, 5, "TLB",
            "The TLB is a small hardware cache of recently used page-table entries.", 0.8);

    private FakeOpenAiServer server;
    private OpenAiClient client;
    private LocalChat chat;

    @BeforeEach
    void setUp() throws IOException {
        server = new FakeOpenAiServer();
        client = new OpenAiClient(Duration.ofSeconds(2));
        chat = server.localChat(client);
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.close();
    }

    private String lastUserText() {
        JsonNode messages = server.lastChatRequest().path("messages");
        JsonNode content = messages.get(messages.size() - 1).path("content");
        if (content.isArray()) {
            StringBuilder text = new StringBuilder();
            content.forEach(part -> text.append(part.path("text").asText("")));
            return text.toString();
        }
        return content.asText();
    }

    // ------------------------------------------------------------------ answers

    @Test
    void answersStreamWithCitationsAfterTheWorkedExamples() {
        server.reply(Reply.text("Pages ", "map to frames [1]."));
        List<String> streamed = new ArrayList<>();
        GroundedAnswer answer = new LlamaGroundedAnswerer(chat).answer("What is paging?", List.of(PAGING, TLB),
                streamed::add, CancellationToken.NONE);

        assertThat(answer.insufficient()).isFalse();
        assertThat(answer.text()).isEqualTo("Pages map to frames [1].");
        assertThat(String.join("", streamed)).isEqualTo(answer.text());
        JsonNode messages = server.lastChatRequest().path("messages");
        assertThat(messages).hasSize(6);
        assertThat(messages.get(4).path("content").asText()).isEqualTo(Prompts.NOT_IN_MATERIAL);
        assertThat(lastUserText()).contains("[1] OS notes · p. 4 · Paging", "[2] OS notes · pp. 4–5 · TLB",
                "What is paging?");
    }

    @Test
    void theSentinelBecomesInsufficientEvidenceAndIsNeverStreamed() {
        server.reply(Reply.text("NOT_IN", "_MATERIAL"));
        List<String> streamed = new ArrayList<>();
        GroundedAnswer answer = new LlamaGroundedAnswerer(chat).answer("Who wrote Linux?", List.of(PAGING),
                streamed::add, CancellationToken.NONE);
        assertThat(answer.insufficient()).isTrue();
        assertThat(streamed).isEmpty();
    }

    @Test
    void aRefusalInTheModelsOwnWordsIsInsufficientEvidenceAndNeverShown() {
        server.reply(Reply.text("The passages ", "don't cover ", "who wrote ", "Linux."));
        StringBuilder shown = new StringBuilder();
        GroundedAnswer answer = new LlamaGroundedAnswerer(chat).answer("Who wrote Linux?", List.of(PAGING),
                shown::append, CancellationToken.NONE);
        assertThat(answer.insufficient()).isTrue();
        assertThat(shown).isEmpty();
    }

    @Test
    void aRemarkAboutTheMaterialBeforeACitedAnswerIsShown() {
        server.reply(Reply.text("The notes only describe ", "two algorithms. ", "FCFS runs jobs ",
                "in arrival order [1]."));
        StringBuilder shown = new StringBuilder();
        GroundedAnswer answer = new LlamaGroundedAnswerer(chat).answer("Which algorithms are there?",
                List.of(PAGING), shown::append, CancellationToken.NONE);
        assertThat(answer.insufficient()).isFalse();
        assertThat(answer.text()).isEqualTo("The notes only describe two algorithms. FCFS runs jobs in arrival order [1].");
        assertThat(shown.toString()).isEqualTo(answer.text());
    }

    @Test
    void noPassagesMeansNoRequest() {
        GroundedAnswer answer = new LlamaGroundedAnswerer(chat).answer("What is paging?", List.of(), piece -> { },
                CancellationToken.NONE);
        assertThat(answer.insufficient()).isTrue();
        assertThat(server.chatRequests()).isEmpty();
    }

    @Test
    void refusalDetectionOnlyLooksAtTheOpening() {
        assertThat(LlamaGroundedAnswerer.refusesInItsOwnWords("Your notes don't explain the banker's algorithm.")).isTrue();
        assertThat(LlamaGroundedAnswerer.refusesInItsOwnWords("The material does not mention TCP.")).isTrue();
        assertThat(LlamaGroundedAnswerer.refusesInItsOwnWords(
                "Paging uses frames [1]. The passages don't cover segmentation.")).isFalse();
        assertThat(LlamaGroundedAnswerer.refusesInItsOwnWords("The TLB caches page-table entries [2].")).isFalse();
        assertThat(LlamaGroundedAnswerer.refusesInItsOwnWords(
                "The notes only describe two algorithms. FCFS runs jobs in arrival order [1].")).isFalse();
        assertThat(LlamaGroundedAnswerer.refusesInItsOwnWords("The notes only cover paging, not segmentation.")).isTrue();
    }

    // ------------------------------------------------------------------ embeddings and rerank

    @Test
    void embedsDocumentsAsIsAndQueriesWithTheInstructionNormalised() {
        LlamaEmbedder embedder = new LlamaEmbedder(server.endpoints(), client, 4, "test-embed");
        List<float[]> vectors = embedder.embed(List.of("Paging", "  "), EmbedKind.DOCUMENT, CancellationToken.NONE);
        float[] query = embedder.embed("what is paging", EmbedKind.QUERY);

        assertThat(vectors).hasSize(2);
        for (float[] v : List.of(vectors.get(0), vectors.get(1), query)) {
            double length = 0;
            for (float x : v) {
                length += x * x;
            }
            assertThat(Math.sqrt(length)).isCloseTo(1.0, within(1e-5));
        }
        JsonNode documents = server.embedRequests().get(0).path("input");
        assertThat(documents.get(0).asText()).isEqualTo("Paging");
        assertThat(documents.get(1).asText()).isEqualTo(" ");
        assertThat(server.embedRequests().get(1).path("input").get(0).asText())
                .isEqualTo(LlamaEmbedder.QUERY_PREFIX + "what is paging");
    }

    @Test
    void embedsInBatches() {
        LlamaEmbedder embedder = new LlamaEmbedder(server.endpoints(), client, 4, "test-embed");
        List<String> texts = new ArrayList<>();
        for (int i = 0; i < LlamaEmbedder.BATCH * 2 + 1; i++) {
            texts.add("text " + i);
        }
        assertThat(embedder.embed(texts, EmbedKind.DOCUMENT, CancellationToken.NONE)).hasSize(texts.size());
        assertThat(server.embedRequests()).hasSize(3);
    }

    @Test
    void aVectorOfTheWrongSizeIsAnError() {
        LlamaEmbedder embedder = new LlamaEmbedder(server.endpoints(), client, 1024, "test-embed");
        assertThatThrownBy(() -> embedder.embed(List.of("x"), EmbedKind.DOCUMENT, CancellationToken.NONE))
                .isInstanceOf(LearnSphereException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(AiMessages.REQUEST_FAILED);
    }

    @Test
    void rerankScoresAreProbabilitiesInPassageOrderAndBlankPassagesScoreZero() {
        LlamaReranker reranker = new LlamaReranker(server.endpoints(), client);
        double[] scores = reranker.score("paging", List.of("Locks", "Paging splits memory", " "),
                CancellationToken.NONE);
        assertThat(scores[0]).isCloseTo(1 / (1 + Math.exp(4)), within(1e-9));
        assertThat(scores[1]).isCloseTo(1 / (1 + Math.exp(-4)), within(1e-9));
        assertThat(scores[2]).isZero();
        assertThat(server.rerankRequests().getFirst().path("documents")).hasSize(2);
    }

    @Test
    void probabilitiesFromTheServerAreUsedAsTheyAre() {
        server.rerankScores((query, document) -> Double.parseDouble(document));
        LlamaReranker reranker = new LlamaReranker(server.endpoints(), client);
        assertThat(reranker.score("q", List.of("0.2", "0.9", "1.0", "0"), CancellationToken.NONE))
                .containsExactly(0.2, 0.9, 1.0, 0.0);
    }

    @Test
    void logitsAreTransformedTogetherSoTheOrderHolds() {
        server.rerankScores((query, document) -> Double.parseDouble(document));
        LlamaReranker reranker = new LlamaReranker(server.endpoints(), client);

        double[] scores = reranker.score("q", List.of("0.9", "1.1", "-2"), CancellationToken.NONE);

        assertThat(scores[1]).isGreaterThan(scores[0]);
        assertThat(scores[0]).isGreaterThan(scores[2]);
        assertThat(scores[0]).isCloseTo(1 / (1 + Math.exp(-0.9)), within(1e-9));
        // Once the server has shown it reports logits, a later call whose scores happen to lie in 0-1 is
        // read the same way.
        assertThat(reranker.score("q", List.of("0.5"), CancellationToken.NONE)[0])
                .isCloseTo(1 / (1 + Math.exp(-0.5)), within(1e-9));
    }

    // ------------------------------------------------------------------ structured replies

    @Test
    void conceptsAreDedupedTidiedAndCapped() {
        server.reply(Reply.json("""
                {"concepts":[{"name":"paging.","description":"memory split into pages"},
                {"name":"Paging","description":"duplicate"},
                {"name":"TLB","description":"A cache of page-table entries."}]}"""));
        List<ConceptDraft> concepts = new LlamaConceptExtractor(chat).extract("Memory", List.of(PAGING, TLB),
                CancellationToken.NONE);
        assertThat(concepts).containsExactly(new ConceptDraft("Paging", "Memory split into pages."),
                new ConceptDraft("TLB", "A cache of page-table entries."));
        JsonNode schema = server.lastChatRequest().path("response_format").path("json_schema").path("schema");
        // Short modules may yield a single concept.
        assertThat(schema.path("properties").path("concepts").path("minItems").asInt()).isEqualTo(1);
    }

    @Test
    void anInvalidJsonReplyIsRetriedOnceWithMoreRoom() {
        server.replies(Reply.truncated("{\"concepts\":[{\"name\":\"Pag"),
                Reply.json("{\"concepts\":[{\"name\":\"Paging\",\"description\":\"x\"}]}"));
        List<ConceptDraft> concepts = new LlamaConceptExtractor(chat).extract("Memory", List.of(PAGING),
                CancellationToken.NONE);
        assertThat(concepts).extracting(ConceptDraft::name).containsExactly("Paging");
        assertThat(server.chatRequests()).hasSize(2);
        assertThat(server.chatRequests().get(1).path("max_tokens").asInt())
                .isEqualTo(server.chatRequests().get(0).path("max_tokens").asInt() * 2);
    }

    @Test
    void twoInvalidJsonRepliesFailWithAShortMessage() {
        server.reply(Reply.text("not json"));
        assertThatThrownBy(() -> new LlamaConceptExtractor(chat).extract("Memory", List.of(PAGING), CancellationToken.NONE))
                .isInstanceOf(LearnSphereException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(AiMessages.REQUEST_FAILED);
    }

    @Test
    void itemsFollowThePlanAndBrokenDraftsAreWrittenAgain() {
        // Round 1: the true/false slot comes back as a question (dropped); round 2 fixes it.
        server.replies(Reply.json("""
                {"items":[
                 {"type":"mcq","difficulty":"medium","passage":1,"prompt":"What does paging divide physical memory into?",
                  "answer":"B) Frames","distractors":["Segments","Frames","All of the above","C) Sectors"],
                  "explanation":"Frames hold pages"},
                 {"type":"true_false","difficulty":"easy","passage":2,"answer":"true","prompt":"Is the TLB a cache?",
                  "explanation":"It caches entries."}]}"""), Reply.json("""
                {"items":[
                 {"type":"true_false","difficulty":"easy","passage":2,"answer":"true",
                  "prompt":"The TLB caches recently used page-table entries.","explanation":"It is a small hardware cache."}]}"""));
        List<ItemDraft> drafts = new LlamaItemGenerator(chat).generate(new ItemGenerationRequest(List.of(PAGING, TLB),
                List.of(), Set.of(ItemType.MCQ, ItemType.TRUE_FALSE), 2, null), CancellationToken.NONE);

        assertThat(server.chatRequests()).hasSize(2);
        assertThat(drafts).hasSize(1 + 1);
        ItemDraft mcq = drafts.get(0);
        assertThat(mcq.type()).isEqualTo(ItemType.MCQ);
        // Option letters go; a distractor equal to the answer and "all of the above" are dropped.
        assertThat(mcq.answer()).isEqualTo("Frames");
        assertThat(mcq.distractors()).containsExactly("Segments", "Sectors");
        assertThat(mcq.explanation()).isEqualTo("Frames hold pages.");
        ItemDraft tf = drafts.get(1);
        assertThat(tf.type()).isEqualTo(ItemType.TRUE_FALSE);
        assertThat(tf.answer()).isEqualTo("true");
        assertThat(tf.difficulty()).isEqualTo(Difficulty.EASY);
        assertThat(tf.passageNumber()).isEqualTo(2);
        // The second request lists the accepted item as one to avoid.
        assertThat(lastUserText()).contains("What does paging divide physical memory into?");
    }

    @Test
    void supportVerdictsComeFromTheReplyWithANoteForTheCreator() {
        server.reply(Reply.json("{\"evidence\":\"recently used page-table entries\",\"verdict\":\"supported\",\"note\":\"\"}"));
        SupportVerdict verdict = new LlamaSupportChecker(chat).check("What does the TLB cache?", "Page-table entries", TLB,
                CancellationToken.NONE);
        assertThat(verdict.supported()).isTrue();
        assertThat(verdict.note()).isEqualTo("The source backs this answer.");
        assertThat(lastUserText()).contains("OS notes, pp. 4–5", "What does the TLB cache?", "Page-table entries");

        server.reply(Reply.json("{\"evidence\":\"\",\"verdict\":\"not_supported\",\"note\":\"the notes say entries, not disk blocks\"}"));
        SupportVerdict unsupported = new LlamaSupportChecker(chat).check("What does the TLB cache?", "Disk blocks", TLB,
                CancellationToken.NONE);
        assertThat(unsupported.supported()).isFalse();
        assertThat(unsupported.note()).isEqualTo("The notes say entries, not disk blocks.");
    }

    @Test
    void trueFalseItemsAreCheckedByJudgingTheStatement() {
        server.reply(Reply.json("{\"evidence\":\"each thread has its own stack\",\"verdict\":\"false\",\"note\":\"\"}"));
        SupportVerdict markedFalse = new LlamaSupportChecker(chat).check("Threads share their stack.", "false",
                PAGING, CancellationToken.NONE);
        assertThat(markedFalse.supported()).isTrue();
        assertThat(markedFalse.note()).isEqualTo("The source contradicts this statement.");
        assertThat(lastUserText()).contains("Statement: Threads share their stack.").doesNotContain("Answer:");
        assertThat(server.lastChatRequest().path("response_format").path("json_schema").path("name").asText())
                .isEqualTo("statement");

        SupportVerdict markedTrue = new LlamaSupportChecker(chat).check("Threads share their stack.", "True.", PAGING,
                CancellationToken.NONE);
        assertThat(markedTrue.supported()).isFalse();

        server.reply(Reply.json("{\"evidence\":\"\",\"verdict\":\"not_stated\",\"note\":\"\"}"));
        assertThat(new LlamaSupportChecker(chat).check("Linux was written in 1991.", "true", PAGING,
                CancellationToken.NONE).supported()).isFalse();
    }

    @Test
    void gradesAreComputedFromThePointsNotPickedByTheModel() {
        server.reply(Reply.json("""
                {"points":[{"point":"shared data","status":"covered"},{"point":"at least one write","status":"partly"},
                {"point":"result depends on timing","status":"missing"}],"errors":[],
                "feedback":"Good start: say why the order of the accesses matters."}"""));
        GradeResult grade = new LlamaShortAnswerGrader(chat).grade("What is a race condition?",
                "Concurrent access to shared data, one write, order-dependent result.", "Threads touching shared data",
                null, CancellationToken.NONE);
        assertThat(grade.score()).isEqualTo(0.5);
        assertThat(grade.verdict()).isEqualTo(GradeResult.Verdict.PARTIAL);
        assertThat(grade.missingPoints()).containsExactly("At least one write", "Result depends on timing");
        assertThat(grade.feedback()).startsWith("Good start");
    }

    @Test
    void aBlankAnswerScoresZeroWithoutAsking() {
        GradeResult grade = new LlamaShortAnswerGrader(chat).grade("Q?", "A.", "   ", null, CancellationToken.NONE);
        assertThat(grade.score()).isZero();
        assertThat(grade.verdict()).isEqualTo(GradeResult.Verdict.INCORRECT);
        assertThat(server.chatRequests()).isEmpty();
    }

    // ------------------------------------------------------------------ pages

    @Test
    void pagesAreReadFromTheImageAndIllegiblePartsAreListed() {
        server.reply(Reply.text("```markdown\n# Paging\n\nFrames hold pages. [illegible: margin note]\n```"));
        PageTranscription page = new LlamaPageReader(chat).read(new byte[] {1, 2, 3}, 7, CancellationToken.NONE);
        assertThat(page.markdown()).isEqualTo("# Paging\n\nFrames hold pages. [illegible: margin note]");
        assertThat(page.illegibleSpans()).containsExactly("Margin note");
        JsonNode content = server.lastChatRequest().path("messages").get(1).path("content");
        assertThat(content.get(0).path("image_url").path("url").asText()).startsWith("data:image/png;base64,");
    }

    @Test
    void figuresAreDescribedOncePerDistinctDescription() {
        server.reply(Reply.json("""
                {"figures":[{"description":"A diagram of the process states ready, running and blocked"},
                {"description":"A diagram of the process states ready, running and blocked"}]}"""));
        List<String> figures = new LlamaFigureDescriber(chat).describe(new byte[] {1}, 3, CancellationToken.NONE);
        assertThat(figures).containsExactly("A diagram of the process states ready, running and blocked.");
    }

    @Test
    void anEmptyImageNeedsNoRequest() {
        assertThat(new LlamaFigureDescriber(chat).describe(new byte[0], 1, CancellationToken.NONE)).isEmpty();
        assertThat(new LlamaPageReader(chat).read(new byte[0], 1, CancellationToken.NONE).illegibleSpans()).isNotEmpty();
        assertThat(server.chatRequests()).isEmpty();
    }
}
