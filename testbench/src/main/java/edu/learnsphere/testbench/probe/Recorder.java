package edu.learnsphere.testbench.probe;

import edu.learnsphere.core.spi.AiCapabilities;
import edu.learnsphere.core.spi.AiRuntime;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.core.spi.ChunkDraft;
import edu.learnsphere.core.spi.Chunker;
import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.spi.FigureDescriber;
import edu.learnsphere.core.spi.GroundedAnswer;
import edu.learnsphere.core.spi.GroundedAnswerer;
import edu.learnsphere.core.spi.ItemDraft;
import edu.learnsphere.core.spi.ItemGenerationRequest;
import edu.learnsphere.core.spi.ItemGenerator;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.core.spi.PageReader;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.PageTranscription;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.spi.SearchIndexFactory;
import edu.learnsphere.core.spi.SupportChecker;
import edu.learnsphere.core.spi.SupportVerdict;
import edu.learnsphere.core.spi.TokenSink;
import edu.learnsphere.core.util.CancellationToken;
import edu.learnsphere.core.util.Subscription;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Watches the real pipeline from the outside: {@link #instrument} wraps the capabilities a workspace is built
 * from in decorators that pass every call through unchanged and record what went in, what came out and how long
 * it took. The workspace's services therefore run exactly as in the app, while the testbench can see the
 * passages given to the answerer, the raw answer before citations are checked, the chunks of each document, the
 * transcription of each scanned page and the drafts the item generator returned.
 *
 * <p>Calls are tagged with the current {@link #label(String) label} (the document being imported, or the
 * evaluation running). Thread-safe.
 */
public final class Recorder {

    /** One call to the chunker: a document's pages became passages. */
    public record ChunkCall(String label, int pages, List<ChunkDraft> chunks, long millis) {
    }

    /** One scanned page read by the page reader. */
    public record PageRead(String label, int page, long millis, PageTranscription result, String error) {
    }

    /** One page of figures described. */
    public record FigureCall(String label, int page, long millis, List<String> notes, String error) {
    }

    /** One batch of texts embedded. */
    public record EmbedCall(String label, EmbedKind kind, int texts, long millis) {
    }

    /**
     * One answer written by the answerer.
     *
     * @param rawText      the text as written, before the app checks its citations
     * @param firstTokenMs from the call until the first streamed text, or -1 when none was streamed
     */
    public record AnswerCall(String question, List<Passage> passages, String rawText, boolean insufficient,
                             long firstTokenMs, long elapsedMs, String error) {
    }

    /** One call to the item generator. */
    public record GenerationCall(String label, int requested, Set<edu.learnsphere.core.model.ItemType> types,
                                 List<ItemDraft> drafts, long millis, String error) {
    }

    /** One support check. */
    public record SupportCall(String label, String prompt, String answer, SupportVerdict verdict, long millis,
                              String error) {
    }

    private volatile String label = "";
    private final AtomicReference<SearchIndex> index = new AtomicReference<>();
    private final List<ChunkCall> chunkCalls = new CopyOnWriteArrayList<>();
    private final List<PageRead> pageReads = new CopyOnWriteArrayList<>();
    private final List<FigureCall> figureCalls = new CopyOnWriteArrayList<>();
    private final List<EmbedCall> embedCalls = new CopyOnWriteArrayList<>();
    private final List<AnswerCall> answerCalls = new CopyOnWriteArrayList<>();
    private final List<GenerationCall> generationCalls = new CopyOnWriteArrayList<>();
    private final List<SupportCall> supportCalls = new CopyOnWriteArrayList<>();

    /** Tags calls from now on with {@code value}. */
    public void label(String value) {
        label = Objects.requireNonNull(value, "value");
    }

    /** The label calls are tagged with now. */
    public String currentLabel() {
        return label;
    }

    /**
     * {@code base} with every PDF chunker and AI slot recorded, the search index factory captured, and the
     * runtime pinned to {@code model} (the workspace starts the runtime with the profile's model setting; the
     * testbench decides which model is evaluated).
     */
    public Capabilities instrument(Capabilities base, ModelChoice model) {
        AiCapabilities ai = base.ai();
        AiCapabilities recorded = new AiCapabilities(new PinnedRuntime(ai.runtime(), model), new RecordingEmbedder(ai.embedder()),
                ai.reranker(), new RecordingAnswerer(ai.answerer()), ai.conceptExtractor(),
                new RecordingGenerator(ai.itemGenerator()), new RecordingSupportChecker(ai.supportChecker()), ai.grader(),
                new RecordingPageReader(ai.pageReader()), new RecordingFigureDescriber(ai.figureDescriber()));
        SearchIndexFactory factory = base.searchIndexFactory();
        return Capabilities.builder()
                .pdf(base.pdfInspector(), base.textExtractor(), new RecordingChunker(base.chunker()), base.pageRenderer(),
                        base.passageLocator())
                .searchIndexFactory((dir, dimensions, model1) -> capture(factory, dir, dimensions, model1))
                .ai(recorded)
                .build();
    }

    private SearchIndex capture(SearchIndexFactory factory, Path dir, int dimensions, String model) {
        SearchIndex opened = factory.open(dir, dimensions, model);
        index.set(opened);
        return opened;
    }

    /** The search index the workspace opened. */
    public SearchIndex index() {
        SearchIndex opened = index.get();
        if (opened == null) {
            throw new IllegalStateException("No workspace has opened a search index yet");
        }
        return opened;
    }

    public List<ChunkCall> chunkCalls() {
        return List.copyOf(chunkCalls);
    }

    public List<PageRead> pageReads() {
        return List.copyOf(pageReads);
    }

    public List<FigureCall> figureCalls() {
        return List.copyOf(figureCalls);
    }

    public List<EmbedCall> embedCalls() {
        return List.copyOf(embedCalls);
    }

    public List<AnswerCall> answerCalls() {
        return List.copyOf(answerCalls);
    }

    public List<GenerationCall> generationCalls() {
        return List.copyOf(generationCalls);
    }

    public List<SupportCall> supportCalls() {
        return List.copyOf(supportCalls);
    }

    private static long since(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String failure(RuntimeException e) {
        return e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage());
    }

    /** Starts the runtime with the evaluated model whatever the profile's setting asks for. */
    private record PinnedRuntime(AiRuntime delegate, ModelChoice model) implements AiRuntime {

        @Override
        public void start(ModelChoice choice) {
            delegate.start(model);
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public AiStatus status() {
            return delegate.status();
        }

        @Override
        public Set<ModelChoice> availableModels() {
            return delegate.availableModels();
        }

        @Override
        public ModelChoice recommendedModel() {
            return model;
        }

        @Override
        public Subscription addListener(Consumer<AiStatus> listener) {
            return delegate.addListener(listener);
        }

        @Override
        public void close() {
            delegate.stop();
        }
    }

    private final class RecordingChunker implements Chunker {

        private final Chunker delegate;

        RecordingChunker(Chunker delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ChunkDraft> chunk(List<PageText> pages, Options options) {
            long start = System.nanoTime();
            List<ChunkDraft> chunks = delegate.chunk(pages, options);
            chunkCalls.add(new ChunkCall(label, pages.size(), List.copyOf(chunks), since(start)));
            return chunks;
        }

        @Override
        public String toStoredText(PageText page) {
            return delegate.toStoredText(page);
        }
    }

    private final class RecordingEmbedder implements Embedder {

        private final Embedder delegate;

        RecordingEmbedder(Embedder delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<float[]> embed(List<String> texts, EmbedKind kind, CancellationToken token) {
            long start = System.nanoTime();
            List<float[]> vectors = delegate.embed(texts, kind, token);
            embedCalls.add(new EmbedCall(label, kind, texts.size(), since(start)));
            return vectors;
        }

        @Override
        public int dimensions() {
            return delegate.dimensions();
        }

        @Override
        public String modelName() {
            return delegate.modelName();
        }
    }

    private final class RecordingAnswerer implements GroundedAnswerer {

        private final GroundedAnswerer delegate;

        RecordingAnswerer(GroundedAnswerer delegate) {
            this.delegate = delegate;
        }

        @Override
        public GroundedAnswer answer(String question, List<Passage> passages, TokenSink sink, CancellationToken token) {
            long start = System.nanoTime();
            AtomicLong first = new AtomicLong(-1);
            TokenSink timed = piece -> {
                first.compareAndSet(-1, since(start));
                sink.accept(piece);
            };
            try {
                GroundedAnswer answer = delegate.answer(question, passages, timed, token);
                answerCalls.add(new AnswerCall(question, List.copyOf(passages), answer.text(), answer.insufficient(),
                        first.get(), since(start), null));
                return answer;
            } catch (RuntimeException e) {
                answerCalls.add(new AnswerCall(question, List.copyOf(passages), "", false, first.get(), since(start),
                        failure(e)));
                throw e;
            }
        }
    }

    private final class RecordingGenerator implements ItemGenerator {

        private final ItemGenerator delegate;

        RecordingGenerator(ItemGenerator delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ItemDraft> generate(ItemGenerationRequest request, CancellationToken token) {
            long start = System.nanoTime();
            try {
                List<ItemDraft> drafts = delegate.generate(request, token);
                generationCalls.add(new GenerationCall(label, request.count(), request.types(), List.copyOf(drafts),
                        since(start), null));
                return drafts;
            } catch (RuntimeException e) {
                generationCalls.add(new GenerationCall(label, request.count(), request.types(), List.of(), since(start),
                        failure(e)));
                throw e;
            }
        }
    }

    private final class RecordingSupportChecker implements SupportChecker {

        private final SupportChecker delegate;

        RecordingSupportChecker(SupportChecker delegate) {
            this.delegate = delegate;
        }

        @Override
        public SupportVerdict check(String prompt, String answer, Passage passage, CancellationToken token) {
            long start = System.nanoTime();
            try {
                SupportVerdict verdict = delegate.check(prompt, answer, passage, token);
                supportCalls.add(new SupportCall(label, prompt, answer, verdict, since(start), null));
                return verdict;
            } catch (RuntimeException e) {
                supportCalls.add(new SupportCall(label, prompt, answer, null, since(start), failure(e)));
                throw e;
            }
        }
    }

    private final class RecordingPageReader implements PageReader {

        private final PageReader delegate;

        RecordingPageReader(PageReader delegate) {
            this.delegate = delegate;
        }

        @Override
        public PageTranscription read(byte[] pngImage, int pageNumber, CancellationToken token) {
            long start = System.nanoTime();
            try {
                PageTranscription result = delegate.read(pngImage, pageNumber, token);
                pageReads.add(new PageRead(label, pageNumber, since(start), result, null));
                return result;
            } catch (RuntimeException e) {
                pageReads.add(new PageRead(label, pageNumber, since(start), null, failure(e)));
                throw e;
            }
        }
    }

    private final class RecordingFigureDescriber implements FigureDescriber {

        private final FigureDescriber delegate;

        RecordingFigureDescriber(FigureDescriber delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<String> describe(byte[] pngImage, int pageNumber, CancellationToken token) {
            long start = System.nanoTime();
            try {
                List<String> notes = delegate.describe(pngImage, pageNumber, token);
                figureCalls.add(new FigureCall(label, pageNumber, since(start), List.copyOf(notes), null));
                return notes;
            } catch (RuntimeException e) {
                figureCalls.add(new FigureCall(label, pageNumber, since(start), List.of(), failure(e)));
                throw e;
            }
        }
    }
}
