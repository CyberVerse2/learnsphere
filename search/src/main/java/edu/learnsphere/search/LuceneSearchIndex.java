package edu.learnsphere.search;

import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.spi.SearchQuery;
import java.io.Closeable;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Stream;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.codecs.Codec;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.BoostQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.AlreadyClosedException;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.Lock;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.BytesRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link SearchIndex} of one profile: a Lucene index in one folder.
 *
 * <h2>Search</h2>
 * Each passage is a Lucene document (layout in {@link IndexFields}). {@link #search} runs two rankings, both
 * restricted to the query's course (and modules) before anything is ranked: BM25 over the English-analysed
 * passage text and section title, and an HNSW nearest-neighbour search over unit-length embeddings with
 * dot-product similarity, where the course filter is part of the vector query itself, so the top
 * {@code vectorTopK} are the nearest passages <em>of that course</em>, not the nearest overall. The two
 * rankings are merged with reciprocal rank fusion ({@link ReciprocalRankFusion}); every hit carries its fused
 * score and both ranks.
 *
 * <h2>Writes, visibility and durability</h2>
 * {@link #upsert}, {@link #delete}, {@link #deleteByDocument} and {@link #deleteByCourse} are buffered;
 * {@link #commit} makes them durable (a Lucene commit is atomic and synced, so a crash leaves the previous
 * commit intact) and then refreshes the shared searcher, so searches see them. {@link #close} commits what
 * is still pending.
 *
 * <h2>Rebuild and recovery</h2>
 * Every commit records the index's {@link IndexMetadata}: layout version, dimensions, embedding model and
 * whether the last rebuild completed. {@link #open} never fails because of what is in the folder: a missing,
 * unreadable or damaged index (every file's checksum is verified on open), one built for other dimensions
 * or another embedder, one whose last rebuild never finished, or one marked as damaged while it was open, is
 * cleared and opened empty with {@link #needsRebuild()} true. That state is committed straight away, so an
 * index filled only partly before a crash is rebuilt again on the next start. {@link #rebuild} replaces the
 * contents in one commit; when it fails or its stream is cancelled, the index goes back to its last commit.
 *
 * <h2>Threading</h2>
 * Thread-safe. Searches never block: they run on the searcher of the last commit, managed by a
 * {@link SearcherManager}, including while a rebuild is running. Writes from several threads run
 * concurrently; a rebuild has the index to itself, so writes started during a rebuild wait for it to finish
 * and then apply on top of the rebuilt contents. Calls after {@link #close} throw
 * {@link IllegalStateException}.
 *
 * <h2>Errors</h2>
 * Failures reach callers as {@link LearnSphereException}s carrying a {@link UserMessage}: a full disk,
 * a failed search or update, or, when the index turns out damaged while open, a request to restart (the
 * index is then marked so the next open clears it and {@link #needsRebuild()} turns true straight away).
 */
public final class LuceneSearchIndex implements SearchIndex {

    private static final Logger LOG = LoggerFactory.getLogger(LuceneSearchIndex.class);

    /** File in the index folder asking the next open to clear the index (written when damage is found while open). */
    static final String REBUILD_MARKER = "learnsphere-rebuild-needed";
    /** Distinct query words used for keyword search; more would only slow it down. */
    static final int MAX_QUERY_TERMS = 128;
    /** Weight of a section-title match relative to a match in the passage text. */
    static final float SECTION_TITLE_BOOST = 0.5f;
    /** Indexing buffer before Lucene writes a segment. */
    private static final double RAM_BUFFER_MB = 64;
    /** A failed rebuild swaps the searcher manager; a search that raced with the swap tries again. */
    private static final int ACQUIRE_ATTEMPTS = 3;
    /** How many unusable passages a rebuild names in the log before only counting them. */
    private static final int LOGGED_SKIPS = 5;

    private final Path folder;
    private final Directory directory;
    private final Analyzer analyzer = new EnglishAnalyzer();
    private final int dimensions;
    private final String embeddingModel;
    /** Shared by writes, exclusive for rebuild and close. Searches do not take it. */
    private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();

    /** Writer and searchers; replaced after a failed rebuild, null once closed or when it could not be reopened. */
    private volatile Engine engine;
    private volatile boolean needsRebuild;
    private volatile boolean closed;

    private record Engine(IndexWriter writer, SearcherManager searchers) {
    }

    @FunctionalInterface
    private interface SearcherWork<T> {
        T apply(IndexSearcher searcher) throws IOException;
    }

    @FunctionalInterface
    private interface WriteWork {
        void apply(Engine engine) throws IOException;
    }

    private LuceneSearchIndex(Path folder, Directory directory, int dimensions, String embeddingModel) {
        this.folder = folder;
        this.directory = directory;
        this.dimensions = dimensions;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Opens (creating if needed) the index in {@code folder}. Usable as a
     * {@link edu.learnsphere.core.spi.SearchIndexFactory}: {@code LuceneSearchIndex::open}.
     *
     * @param dimensions     vector length, 1 to {@link #maxDimensions()}
     * @param embeddingModel name of the embedder whose vectors the index holds
     * @throws UncheckedIOException     when the folder cannot be created, read or written, or another open
     *                                  index holds it (its contents are then left untouched)
     * @throws IllegalArgumentException when {@code dimensions} is out of range or the model name is blank
     */
    public static LuceneSearchIndex open(Path folder, int dimensions, String embeddingModel) {
        Objects.requireNonNull(folder, "folder");
        Objects.requireNonNull(embeddingModel, "embeddingModel");
        if (embeddingModel.isBlank()) {
            throw new IllegalArgumentException("The embedding model name is blank");
        }
        int max = maxDimensions();
        if (dimensions < 1 || dimensions > max) {
            throw new IllegalArgumentException("Vector dimensions must be between 1 and " + max + ": " + dimensions);
        }
        Path absolute = folder.toAbsolutePath().normalize();
        Directory directory;
        try {
            Files.createDirectories(absolute);
            directory = FSDirectory.open(absolute);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open the search index folder " + absolute, e);
        }
        LuceneSearchIndex index = new LuceneSearchIndex(absolute, directory, dimensions, embeddingModel);
        try {
            index.start();
            return index;
        } catch (IOException e) {
            index.releaseResources();
            throw new UncheckedIOException("Cannot open the search index in " + absolute, e);
        } catch (RuntimeException | Error e) {
            index.releaseResources();
            throw e;
        }
    }

    /** The longest vector the index format accepts. */
    public static int maxDimensions() {
        return Codec.getDefault().knnVectorsFormat().getMaxDimensions(IndexFields.VECTOR);
    }

    // ---------------------------------------------------------------------------------------------------------
    // Opening and recovery

    private void start() throws IOException {
        String reason = null;
        IndexWriter writer = null;
        if (Files.exists(folder.resolve(REBUILD_MARKER))) {
            reason = "it was found damaged while open";
        } else if (!DirectoryReader.indexExists(directory)) {
            reason = "the folder holds no index";
        } else {
            try {
                writer = newWriter(OpenMode.APPEND);
                reason = staleReason(writer);
            } catch (LockObtainFailedException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                reason = "it cannot be read: " + e;
            }
            if (reason != null && writer != null) {
                rollbackQuietly(writer);
                writer = null;
            }
        }
        if (reason != null) {
            LOG.info("Starting an empty search index in {} because {}; it needs a rebuild", folder, reason);
            clearFolder();
            writer = newWriter(OpenMode.CREATE);
            try {
                writer.setLiveCommitData(IndexMetadata.current(dimensions, embeddingModel, false).toCommitData().entrySet());
                writer.commit();
                Files.deleteIfExists(folder.resolve(REBUILD_MARKER));
            } catch (IOException | RuntimeException e) {
                rollbackQuietly(writer);
                throw e;
            }
            needsRebuild = true;
        }
        try {
            engine = new Engine(writer, new SearcherManager(writer, true, false, null));
        } catch (IOException | RuntimeException e) {
            rollbackQuietly(writer);
            throw e;
        }
    }

    /** Why the index {@code writer} opened cannot be used as it is, or null. Verifies every file's checksum. */
    private String staleReason(IndexWriter writer) throws IOException {
        String reason = IndexMetadata.staleReason(writer.getLiveCommitData(), dimensions, embeddingModel);
        if (reason != null) {
            return reason;
        }
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            for (LeafReaderContext leaf : reader.leaves()) {
                leaf.reader().checkIntegrity();
            }
        }
        return null;
    }

    /** Deletes every file in the folder while holding the index lock, so an index open elsewhere is never touched. */
    private void clearFolder() throws IOException {
        try (Lock lock = directory.obtainLock(IndexWriter.WRITE_LOCK_NAME);
             Stream<Path> entries = Files.list(folder)) {
            lock.ensureValid();
            for (Path entry : (Iterable<Path>) entries::iterator) {
                String name = entry.getFileName().toString();
                if (!name.equals(IndexWriter.WRITE_LOCK_NAME) && !name.equals(REBUILD_MARKER)
                        && Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    private IndexWriter newWriter(OpenMode mode) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig(analyzer)
                .setOpenMode(mode)
                .setRAMBufferSizeMB(RAM_BUFFER_MB)
                // close() commits explicitly, then closes without waiting for merges.
                .setCommitOnClose(false);
        return new IndexWriter(directory, config);
    }

    // ---------------------------------------------------------------------------------------------------------
    // SearchIndex

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String embeddingModel() {
        return embeddingModel;
    }

    @Override
    public boolean needsRebuild() {
        return needsRebuild;
    }

    @Override
    public void upsert(Collection<IndexedChunk> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        if (chunks.isEmpty()) {
            return;
        }
        List<IndexedChunk> batch = List.copyOf(chunks);
        List<Document> documents = new ArrayList<>(batch.size());
        for (IndexedChunk chunk : batch) {
            String problem = Vectors.problem(chunk.embedding(), dimensions);
            if (problem != null) {
                throw new IllegalArgumentException(problem + " (passage " + chunk.chunkId() + ")");
            }
            documents.add(IndexFields.toDocument(chunk, Vectors.unit(chunk.embedding(), dimensions)));
        }
        write("upsert", current -> {
            for (int i = 0; i < documents.size(); i++) {
                current.writer().updateDocument(IndexFields.idTerm(batch.get(i).chunkId()), documents.get(i));
            }
        });
    }

    @Override
    public void delete(Collection<String> chunkIds) {
        Objects.requireNonNull(chunkIds, "chunkIds");
        if (chunkIds.isEmpty()) {
            return;
        }
        Term[] terms = chunkIds.stream().map(id -> IndexFields.idTerm(Objects.requireNonNull(id, "chunkId")))
                .toArray(Term[]::new);
        write("delete", current -> current.writer().deleteDocuments(terms));
    }

    @Override
    public void deleteByDocument(String documentId) {
        Term term = new Term(IndexFields.DOCUMENT_ID, Objects.requireNonNull(documentId, "documentId"));
        write("delete document", current -> current.writer().deleteDocuments(term));
    }

    @Override
    public void deleteByCourse(String courseId) {
        Term term = new Term(IndexFields.COURSE_ID, Objects.requireNonNull(courseId, "courseId"));
        write("delete course", current -> current.writer().deleteDocuments(term));
    }

    @Override
    public void commit() {
        write("commit", current -> {
            current.writer().commit();
            current.searchers().maybeRefreshBlocking();
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>Passages whose embedding cannot be indexed (wrong length, only zeros, not finite) are skipped and
     * logged rather than failing the whole rebuild. Writes from other threads wait until the rebuild has
     * committed or failed. When the stream throws (for example a {@link java.util.concurrent.CancellationException}
     * from a cancelled job) or writing fails, the index returns to its last commit and the exception
     * propagates; {@link #needsRebuild()} keeps its value.
     */
    @Override
    public void rebuild(Stream<IndexedChunk> chunks) {
        Objects.requireNonNull(chunks, "chunks");
        gate.writeLock().lock();
        try {
            ensureOpen();
            Engine current = engine();
            long started = System.nanoTime();
            long added = 0;
            long skipped = 0;
            boolean committed = false;
            try {
                // Earlier writes become part of the last commit, so a failed rebuild can return to it without losing them.
                current.writer().commit();
                current.writer().deleteAll();
                Iterator<IndexedChunk> it = chunks.iterator();
                while (it.hasNext()) {
                    IndexedChunk chunk = it.next();
                    float[] vector = Vectors.unitOrNull(chunk.embedding(), dimensions);
                    if (vector == null) {
                        if (skipped++ < LOGGED_SKIPS) {
                            LOG.warn("Rebuild skips passage {}: {}", chunk.chunkId(), Vectors.problem(chunk.embedding(), dimensions));
                        }
                        continue;
                    }
                    current.writer().updateDocument(IndexFields.idTerm(chunk.chunkId()), IndexFields.toDocument(chunk, vector));
                    added++;
                }
                current.writer().setLiveCommitData(IndexMetadata.current(dimensions, embeddingModel, true).toCommitData().entrySet());
                current.writer().commit();
                committed = true;
            } catch (IOException e) {
                throw failure("rebuild", e, SearchFailures.UPDATE_FAILED);
            } catch (AlreadyClosedException e) {
                throw stopped("rebuild", current, e);
            } finally {
                if (!committed) {
                    restoreLastCommit(current);
                }
            }
            needsRebuild = false;
            deleteMarker();
            try {
                current.searchers().maybeRefreshBlocking();
            } catch (IOException e) {
                throw failure("refresh after rebuild", e, SearchFailures.SEARCH_FAILED);
            }
            LOG.info("Rebuilt the search index in {}: {} passages in {} ms{}", folder, added,
                    (System.nanoTime() - started) / 1_000_000, skipped == 0 ? "" : ", " + skipped + " skipped");
        } finally {
            gate.writeLock().unlock();
        }
    }

    @Override
    public List<SearchHit> search(SearchQuery query) {
        Objects.requireNonNull(query, "query");
        float[] target = query.vector() == null || query.vectorTopK() == 0 ? null : Vectors.unit(query.vector(), dimensions);
        ensureOpen();
        if (needsRebuild) {
            return List.of();
        }
        Query filter = scopeFilter(query.courseId(), query.moduleIds());
        Query keyword = query.keywordTopK() == 0 ? null : keywordQuery(query.text(), filter);
        Query vector = target == null ? null : new KnnFloatVectorQuery(IndexFields.VECTOR, target, query.vectorTopK(), filter);
        if (keyword == null && vector == null) {
            return List.of();
        }
        return withSearcher("search", searcher -> {
            int[] keywordDocs = keyword == null ? new int[0] : docs(searcher.search(keyword, query.keywordTopK()).scoreDocs);
            int[] vectorDocs = vector == null ? new int[0] : docs(searcher.search(vector, query.vectorTopK()).scoreDocs);
            List<ReciprocalRankFusion.Fused> fused = ReciprocalRankFusion.fuse(keywordDocs, vectorDocs, query.rrfK(), query.limit());
            StoredFields stored = searcher.storedFields();
            List<SearchHit> hits = new ArrayList<>(fused.size());
            for (ReciprocalRankFusion.Fused f : fused) {
                hits.add(IndexFields.toHit(stored.document(f.doc()), f.score(), f.keywordRank(), f.vectorRank()));
            }
            return List.copyOf(hits);
        });
    }

    /** {@inheritDoc} Counts the passages searches see, that is, as of the last commit. */
    @Override
    public long size() {
        ensureOpen();
        return withSearcher("count", searcher -> (long) searcher.getIndexReader().numDocs());
    }

    /** Commits pending writes and closes the index. Waits for a running rebuild. Idempotent. */
    @Override
    public void close() {
        gate.writeLock().lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            Engine current = engine;
            engine = null;
            if (current != null) {
                closeQuietly(current.searchers());
                try {
                    current.writer().commit();
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Search index changes in {} not saved at close", folder, e);
                }
                // Nothing is pending after the commit; rollback closes without waiting for background merges.
                rollbackQuietly(current.writer());
            }
            releaseResources();
        } finally {
            gate.writeLock().unlock();
        }
    }

    @Override
    public String toString() {
        return "LuceneSearchIndex[" + folder + ", " + dimensions + " dims, " + embeddingModel
                + (needsRebuild ? ", needs rebuild" : "") + (closed ? ", closed" : "") + "]";
    }

    // ---------------------------------------------------------------------------------------------------------
    // Queries

    /** Only passages of the course, and of the modules when some are given. */
    private static Query scopeFilter(String courseId, Set<String> moduleIds) {
        Query course = new TermQuery(new Term(IndexFields.COURSE_ID, courseId));
        if (moduleIds.isEmpty()) {
            return course;
        }
        List<BytesRef> modules = moduleIds.stream().map(BytesRef::new).toList();
        return new BooleanQuery.Builder()
                .add(course, Occur.FILTER)
                .add(new TermInSetQuery(IndexFields.MODULE_ID, modules), Occur.FILTER)
                .build();
    }

    /** BM25 over passage text and section title for the question's words, inside {@code filter}; null without words. */
    private Query keywordQuery(String text, Query filter) {
        Set<String> terms = analyze(text);
        if (terms.isEmpty()) {
            return null;
        }
        BooleanQuery.Builder words = new BooleanQuery.Builder();
        for (String term : terms) {
            words.add(new TermQuery(new Term(IndexFields.TEXT, term)), Occur.SHOULD);
            words.add(new BoostQuery(new TermQuery(new Term(IndexFields.SECTION_TITLE, term)), SECTION_TITLE_BOOST),
                    Occur.SHOULD);
        }
        return new BooleanQuery.Builder()
                .add(words.build(), Occur.MUST)
                .add(filter, Occur.FILTER)
                .build();
    }

    /** The distinct analysed terms of {@code text}, in order, at most {@link #MAX_QUERY_TERMS}. */
    private Set<String> analyze(String text) {
        Set<String> terms = new LinkedHashSet<>();
        try (TokenStream stream = analyzer.tokenStream(IndexFields.TEXT, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (terms.size() < MAX_QUERY_TERMS && stream.incrementToken()) {
                terms.add(term.toString());
            }
            stream.end();
        } catch (IOException e) {
            // Analysing an in-memory string does not do I/O.
            throw new UncheckedIOException(e);
        }
        return terms;
    }

    private static int[] docs(ScoreDoc[] scoreDocs) {
        int[] docs = new int[scoreDocs.length];
        for (int i = 0; i < scoreDocs.length; i++) {
            docs[i] = scoreDocs[i].doc;
        }
        return docs;
    }

    // ---------------------------------------------------------------------------------------------------------
    // Plumbing

    private <T> T withSearcher(String what, SearcherWork<T> work) {
        for (int attempt = 1; ; attempt++) {
            Engine current = engine();
            IndexSearcher searcher;
            try {
                searcher = current.searchers().acquire();
            } catch (AlreadyClosedException e) {
                ensureOpen();
                if (attempt < ACQUIRE_ATTEMPTS) {
                    continue;
                }
                throw new LearnSphereException(SearchFailures.SEARCH_FAILED, what + " found no searcher", e);
            } catch (IOException e) {
                throw failure(what, e, SearchFailures.SEARCH_FAILED);
            }
            try {
                return work.apply(searcher);
            } catch (IOException e) {
                throw failure(what, e, SearchFailures.SEARCH_FAILED);
            } finally {
                try {
                    current.searchers().release(searcher);
                } catch (IOException | RuntimeException e) {
                    LOG.warn("Releasing a searcher of {} failed", folder, e);
                }
            }
        }
    }

    private void write(String what, WriteWork work) {
        gate.readLock().lock();
        try {
            ensureOpen();
            Engine current = engine();
            try {
                work.apply(current);
            } catch (IOException e) {
                throw failure(what, e, SearchFailures.UPDATE_FAILED);
            } catch (AlreadyClosedException e) {
                throw stopped(what, current, e);
            }
        } finally {
            gate.readLock().unlock();
        }
    }

    /**
     * Rolls back a failed rebuild and opens a fresh writer and searcher manager on the last commit. When that
     * fails, the index is marked for a rebuild and stays unusable until it is opened again.
     */
    private void restoreLastCommit(Engine failed) {
        rollbackQuietly(failed.writer());
        Engine replacement = null;
        try {
            IndexWriter writer = newWriter(OpenMode.APPEND);
            try {
                replacement = new Engine(writer, new SearcherManager(writer, true, false, null));
            } catch (IOException | RuntimeException e) {
                rollbackQuietly(writer);
                throw e;
            }
        } catch (IOException | RuntimeException e) {
            LOG.error("The search index in {} could not be reopened after an unfinished rebuild", folder, e);
            markForRebuild(e);
        }
        engine = replacement;
        closeQuietly(failed.searchers());
        LOG.info("An unfinished rebuild of the search index in {} was undone", folder);
    }

    private LearnSphereException failure(String what, IOException e, UserMessage fallback) {
        if (SearchFailures.isDiskFull(e)) {
            return new LearnSphereException(UserMessage.DISK_FULL, "Search index " + what + " failed: disk full", e);
        }
        if (SearchFailures.isDamage(e)) {
            markForRebuild(e);
            return new LearnSphereException(SearchFailures.NEEDS_RESTART, "Search index " + what + " found damage", e);
        }
        return new LearnSphereException(fallback, "Search index " + what + " failed in " + folder, e);
    }

    /** The writer stopped itself after an unrecoverable error (a "tragic" event in Lucene's terms). */
    private LearnSphereException stopped(String what, Engine current, AlreadyClosedException e) {
        Throwable tragic = current.writer().getTragicException();
        if (tragic != null && SearchFailures.isDamage(tragic)) {
            markForRebuild(tragic);
        }
        if (tragic != null && SearchFailures.isDiskFull(tragic)) {
            return new LearnSphereException(UserMessage.DISK_FULL, "Search index " + what + ": writer stopped, disk full", e);
        }
        return new LearnSphereException(SearchFailures.NEEDS_RESTART, "Search index " + what + ": writer stopped", e);
    }

    /** Searches return nothing from now on, and the next open clears the index so it is rebuilt. */
    private void markForRebuild(Throwable cause) {
        needsRebuild = true;
        LOG.error("The search index in {} is damaged; it will be rebuilt", folder, cause);
        try {
            Files.writeString(folder.resolve(REBUILD_MARKER), "The search index was found damaged and is rebuilt on the next open.\n",
                    StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Could not mark the search index in {} for a rebuild", folder, e);
        }
    }

    private void deleteMarker() {
        try {
            Files.deleteIfExists(folder.resolve(REBUILD_MARKER));
        } catch (IOException e) {
            LOG.warn("Could not remove the rebuild mark of the search index in {}", folder, e);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("The search index in " + folder + " is closed");
        }
    }

    private Engine engine() {
        Engine current = engine;
        if (current == null) {
            ensureOpen();
            throw new LearnSphereException(SearchFailures.NEEDS_RESTART, "The search index in " + folder + " is not usable");
        }
        return current;
    }

    private void releaseResources() {
        closeQuietly(directory);
        closeQuietly(analyzer);
    }

    private void rollbackQuietly(IndexWriter writer) {
        try {
            writer.rollback();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Closing the search index writer in {} failed", folder, e);
        }
    }

    private void closeQuietly(Closeable resource) {
        try {
            resource.close();
        } catch (IOException | RuntimeException e) {
            LOG.warn("Closing part of the search index in {} failed", folder, e);
        }
    }
}
