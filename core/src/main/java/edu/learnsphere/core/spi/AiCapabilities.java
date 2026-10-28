package edu.learnsphere.core.spi;

import java.util.Objects;

/**
 * Every AI interface, as one bundle. The ai module builds the real bundle around its llama-server
 * runtime; {@code edu.learnsphere.ai.fake.FakeAi#capabilities()} builds one from the deterministic fakes.
 * {@link Capabilities.Builder#ai(AiCapabilities)} puts it into a {@link Capabilities}.
 */
public record AiCapabilities(
        AiRuntime runtime,
        Embedder embedder,
        Reranker reranker,
        GroundedAnswerer answerer,
        ConceptExtractor conceptExtractor,
        ItemGenerator itemGenerator,
        SupportChecker supportChecker,
        ShortAnswerGrader grader,
        PageReader pageReader,
        FigureDescriber figureDescriber,
        QuestionReader questionReader) {

    public AiCapabilities {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(embedder, "embedder");
        Objects.requireNonNull(reranker, "reranker");
        Objects.requireNonNull(answerer, "answerer");
        Objects.requireNonNull(conceptExtractor, "conceptExtractor");
        Objects.requireNonNull(itemGenerator, "itemGenerator");
        Objects.requireNonNull(supportChecker, "supportChecker");
        Objects.requireNonNull(grader, "grader");
        Objects.requireNonNull(pageReader, "pageReader");
        Objects.requireNonNull(figureDescriber, "figureDescriber");
        Objects.requireNonNull(questionReader, "questionReader");
    }

    /** A bundle whose questions are taken as they are typed ({@link QuestionReader#AS_IS}). */
    public AiCapabilities(AiRuntime runtime, Embedder embedder, Reranker reranker, GroundedAnswerer answerer,
                          ConceptExtractor conceptExtractor, ItemGenerator itemGenerator, SupportChecker supportChecker,
                          ShortAnswerGrader grader, PageReader pageReader, FigureDescriber figureDescriber) {
        this(runtime, embedder, reranker, answerer, conceptExtractor, itemGenerator, supportChecker, grader, pageReader,
                figureDescriber, QuestionReader.AS_IS);
    }
}
