package edu.learnsphere.ai.fake;

import java.time.Duration;

/**
 * One fake for every AI interface, ready to wire into services.
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
        FakeFigureDescriber figureDescriber) {

    /** Fakes with instant answers (tests). */
    public static FakeAi create() {
        return create(Duration.ZERO);
    }

    /** Fakes whose answers stream one word every {@code wordDelay} (demo mode). */
    public static FakeAi create(Duration wordDelay) {
        return new FakeAi(new FakeAiRuntime(), new FakeEmbedder(), new FakeReranker(), new FakeGroundedAnswerer(wordDelay),
                new FakeItemGenerator(), new FakeConceptExtractor(), new FakeSupportChecker(), new FakeShortAnswerGrader(),
                new FakePageReader(), new FakeFigureDescriber());
    }
}
