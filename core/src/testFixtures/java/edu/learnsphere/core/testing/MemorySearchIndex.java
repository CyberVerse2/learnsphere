package edu.learnsphere.core.testing;

import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.spi.SearchIndexFactory;
import edu.learnsphere.core.spi.SearchQuery;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * A {@link SearchIndex} held in memory for tests: passages are matched by the words of the query (no ranking
 * beyond the number of matching words), and committed changes are what {@link #chunks()} shows. Set
 * {@link #failNextWrites} to make writes throw, as a broken index on disk would. Thread-safe.
 *
 * <pre>{@code
 * MemorySearchIndex index = new MemorySearchIndex(2, "embed-v1");
 * Capabilities.builder().searchIndexFactory(index.factory())…
 * }</pre>
 */
public final class MemorySearchIndex implements SearchIndex {

    private final int dimensions;
    private final String embeddingModel;
    private final Map<String, IndexedChunk> committed = new LinkedHashMap<>();
    private final Map<String, IndexedChunk> pending = new LinkedHashMap<>();
    private boolean needsRebuild;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger commits = new AtomicInteger();

    /** How many of the next upserts, deletes and rebuilds throw {@link IllegalStateException}. */
    public final AtomicInteger failNextWrites = new AtomicInteger();

    public MemorySearchIndex(int dimensions, String embeddingModel) {
        this.dimensions = dimensions;
        this.embeddingModel = embeddingModel;
    }

    /** A factory that hands out this index whatever folder it is asked for. */
    public SearchIndexFactory factory() {
        return (folder, dims, model) -> this;
    }

    /** Makes {@link #needsRebuild()} report {@code value} until the next rebuild. */
    public synchronized MemorySearchIndex needingRebuild(boolean value) {
        needsRebuild = value;
        return this;
    }

    /** The committed passages, by chunk id, in insertion order. */
    public synchronized Map<String, IndexedChunk> chunks() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(committed));
    }

    /** How many times {@link #commit()} ran. */
    public int commits() {
        return commits.get();
    }

    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String embeddingModel() {
        return embeddingModel;
    }

    @Override
    public synchronized boolean needsRebuild() {
        return needsRebuild;
    }

    @Override
    public synchronized void upsert(Collection<IndexedChunk> chunks) {
        write();
        for (IndexedChunk chunk : chunks) {
            if (chunk.embedding().length != dimensions) {
                throw new IllegalArgumentException("Embedding of " + chunk.chunkId() + " has " + chunk.embedding().length
                        + " dimensions, not " + dimensions);
            }
            pending.put(chunk.chunkId(), chunk);
        }
    }

    @Override
    public synchronized void delete(Collection<String> chunkIds) {
        write();
        chunkIds.forEach(id -> pending.put(id, null));
    }

    @Override
    public synchronized void deleteByDocument(String documentId) {
        write();
        view().values().stream().filter(c -> c.documentId().equals(documentId))
                .map(IndexedChunk::chunkId).toList().forEach(id -> pending.put(id, null));
    }

    @Override
    public synchronized void deleteByCourse(String courseId) {
        write();
        view().values().stream().filter(c -> c.courseId().equals(courseId))
                .map(IndexedChunk::chunkId).toList().forEach(id -> pending.put(id, null));
    }

    @Override
    public synchronized List<SearchHit> search(SearchQuery query) {
        List<String> words = Arrays.stream(query.text().toLowerCase(Locale.ROOT).split("\\W+"))
                .filter(w -> !w.isBlank()).toList();
        List<SearchHit> hits = new ArrayList<>();
        for (IndexedChunk c : committed.values()) {
            if (!c.courseId().equals(query.courseId())
                    || (!query.moduleIds().isEmpty() && !query.moduleIds().contains(c.moduleId()))) {
                continue;
            }
            String text = c.text().toLowerCase(Locale.ROOT);
            long matches = words.stream().filter(text::contains).count();
            if (matches > 0) {
                hits.add(new SearchHit(c.chunkId(), c.documentId(), c.courseId(), c.moduleId(), c.documentTitle(),
                        c.pageStart(), c.pageEnd(), c.sectionTitle(), c.text(), matches, hits.size() + 1, null));
            }
        }
        hits.sort((a, b) -> Double.compare(b.score(), a.score()));
        return List.copyOf(hits.subList(0, Math.min(query.limit(), hits.size())));
    }

    @Override
    public synchronized void commit() {
        pending.forEach((id, chunk) -> {
            if (chunk == null) {
                committed.remove(id);
            } else {
                committed.put(id, chunk);
            }
        });
        pending.clear();
        commits.incrementAndGet();
    }

    @Override
    public synchronized void rebuild(Stream<IndexedChunk> chunks) {
        write();
        pending.clear();
        committed.clear();
        chunks.forEach(c -> committed.put(c.chunkId(), c));
        needsRebuild = false;
        commits.incrementAndGet();
    }

    @Override
    public synchronized long size() {
        return committed.size();
    }

    @Override
    public void close() {
        closed.set(true);
    }

    private void write() {
        if (closed.get()) {
            throw new IllegalStateException("Index closed");
        }
        if (failNextWrites.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
            throw new IllegalStateException("Simulated index failure");
        }
    }

    /** Committed passages with pending changes applied. */
    private Map<String, IndexedChunk> view() {
        Map<String, IndexedChunk> view = new LinkedHashMap<>(committed);
        pending.forEach((id, chunk) -> {
            if (chunk == null) {
                view.remove(id);
            } else {
                view.put(id, chunk);
            }
        });
        return view;
    }
}
