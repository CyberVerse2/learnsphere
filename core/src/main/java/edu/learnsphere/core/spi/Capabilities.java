package edu.learnsphere.core.spi;

import java.util.Objects;

/**
 * Every SPI implementation a workspace needs, in one place. {@code app} builds it once at start-up from
 * the pdf, search and ai modules (tests and demo mode put fakes in the AI slots) and hands it to
 * {@code ProfileServices.create}; each opened workspace passes it on to the service implementations.
 *
 * <p>All slots are required. The {@link #aiRuntime()} is shared by every workspace of the app: a workspace
 * starts it when it opens and stops it when it closes, and the app closes it on exit.
 *
 * <pre>{@code
 * Capabilities capabilities = Capabilities.builder()
 *         .pdf(inspector, extractor, chunker, renderer, locator)
 *         .searchIndexFactory(LuceneSearchIndex::open)
 *         .ai(FakeAi.create().capabilities())
 *         .build();
 * }</pre>
 */
public record Capabilities(
        PdfInspector pdfInspector,
        PdfTextExtractor textExtractor,
        Chunker chunker,
        PageRenderer pageRenderer,
        PassageLocator passageLocator,
        SearchIndexFactory searchIndexFactory,
        Embedder embedder,
        Reranker reranker,
        GroundedAnswerer answerer,
        ConceptExtractor conceptExtractor,
        ItemGenerator itemGenerator,
        SupportChecker supportChecker,
        ShortAnswerGrader grader,
        PageReader pageReader,
        FigureDescriber figureDescriber,
        QuestionReader questionReader,
        ModuleSuggester moduleSuggester,
        AiRuntime aiRuntime) {

    public Capabilities {
        Objects.requireNonNull(pdfInspector, "pdfInspector");
        Objects.requireNonNull(textExtractor, "textExtractor");
        Objects.requireNonNull(chunker, "chunker");
        Objects.requireNonNull(pageRenderer, "pageRenderer");
        Objects.requireNonNull(passageLocator, "passageLocator");
        Objects.requireNonNull(searchIndexFactory, "searchIndexFactory");
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
        Objects.requireNonNull(moduleSuggester, "moduleSuggester");
        Objects.requireNonNull(aiRuntime, "aiRuntime");
    }

    /** The AI slots as one bundle. */
    public AiCapabilities ai() {
        return new AiCapabilities(aiRuntime, embedder, reranker, answerer, conceptExtractor, itemGenerator, supportChecker,
                grader, pageReader, figureDescriber, questionReader, moduleSuggester);
    }

    /** An empty builder. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder holding this record's slots, to replace some of them. */
    public Builder toBuilder() {
        return new Builder()
                .pdf(pdfInspector, textExtractor, chunker, pageRenderer, passageLocator)
                .searchIndexFactory(searchIndexFactory)
                .ai(ai());
    }

    /** Fills the slots group by group; {@link #build()} fails while any slot is empty. */
    public static final class Builder {

        private PdfInspector pdfInspector;
        private PdfTextExtractor textExtractor;
        private Chunker chunker;
        private PageRenderer pageRenderer;
        private PassageLocator passageLocator;
        private SearchIndexFactory searchIndexFactory;
        private AiCapabilities ai;

        private Builder() {
        }

        /** The PDF slots (pdf module). */
        public Builder pdf(PdfInspector inspector, PdfTextExtractor extractor, Chunker chunkerValue, PageRenderer renderer,
                           PassageLocator locator) {
            this.pdfInspector = inspector;
            this.textExtractor = extractor;
            this.chunker = chunkerValue;
            this.pageRenderer = renderer;
            this.passageLocator = locator;
            return this;
        }

        /** The search slot (search module). */
        public Builder searchIndexFactory(SearchIndexFactory factory) {
            this.searchIndexFactory = factory;
            return this;
        }

        /** Every AI slot (ai module, or the fakes). */
        public Builder ai(AiCapabilities bundle) {
            this.ai = bundle;
            return this;
        }

        /** @throws NullPointerException naming the first empty slot */
        public Capabilities build() {
            Objects.requireNonNull(ai, "ai");
            return new Capabilities(pdfInspector, textExtractor, chunker, pageRenderer, passageLocator, searchIndexFactory,
                    ai.embedder(), ai.reranker(), ai.answerer(), ai.conceptExtractor(), ai.itemGenerator(),
                    ai.supportChecker(), ai.grader(), ai.pageReader(), ai.figureDescriber(), ai.questionReader(),
                    ai.moduleSuggester(), ai.runtime());
        }
    }
}
