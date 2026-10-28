package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.AiCapabilities;
import edu.learnsphere.core.spi.Capabilities;
import java.time.Duration;

/**
 * One fake for every AI interface, ready to wire into services. The components keep their concrete types
 * so tests can steer them (for example {@link FakeAiRuntime#setStatus}); {@link #capabilities()} hands
 * them to a {@link Capabilities} in one call:
 *
 * <pre>{@code
 * FakeAi ai = FakeAi.create();
 * Capabilities capabilities = Capabilities.builder()
 *         .pdf(inspector, extractor, chunker, renderer, locator)
 *         .searchIndexFactory(factory)
 *         .ai(ai.capabilities())
 *         .build();
 * }</pre>
 *
 * @param runtime         always ready
 * @param embedder        hashed bag-of-words vectors
 * @param reranker        query word overlap
 * @param answerer        first sentence of the first passage, cited
 * @param itemGenerator   one valid draft per requested type
 * @param conceptExtractor most frequent words
 * @param supportChecker  answer word overlap with the passage
 * @param grader          reference word overlap
 * @param pageReader      fixed transcription
 * @param figureDescriber one figure per page image
 * @param questionReader  greetings by their first word, pointing words as follow-ups
 */
public record FakeAi(
        FakeAiRuntime runtime,
        FakeEmbedder embedder,
        FakeReranker reranker,
        FakeGroundedAnswerer answerer,
        FakeItemGenerator itemGenerator,
        FakeConceptExtractor conceptExtractor,
        FakeSupportChecker supportChecker,
        FakeShortAnswerGrader grader,
        FakePageReader pageReader,
        FakeFigureDescriber figureDescriber,
        FakeQuestionReader questionReader) {

    /** Fakes with instant answers (tests). */
    public static FakeAi create() {
        return create(Duration.ZERO);
    }

    /** Fakes whose answers stream one word every {@code wordDelay} (demo mode). */
    public static FakeAi create(Duration wordDelay) {
        return new FakeAi(new FakeAiRuntime(), new FakeEmbedder(), new FakeReranker(), new FakeGroundedAnswerer(wordDelay),
                new FakeItemGenerator(), new FakeConceptExtractor(), new FakeSupportChecker(), new FakeShortAnswerGrader(),
                new FakePageReader(), new FakeFigureDescriber(), new FakeQuestionReader());
    }

    /** Every fake as the AI slots of a {@link Capabilities}. */
    public AiCapabilities capabilities() {
        return new AiCapabilities(runtime, embedder, reranker, answerer, conceptExtractor, itemGenerator, supportChecker,
                grader, pageReader, figureDescriber, questionReader);
    }
}
