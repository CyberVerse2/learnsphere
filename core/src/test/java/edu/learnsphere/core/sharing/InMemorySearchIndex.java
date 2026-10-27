package edu.learnsphere.core.sharing;

import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.spi.SearchQuery;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A search index that keeps passages in a map; searching is not supported. */
final class InMemorySearchIndex implements SearchIndex {

    private final int dimensions;
    private final String model;
    private final Map<String, IndexedChunk> chunks = new ConcurrentHashMap<>();
    final AtomicInteger commits = new AtomicInteger();

    InMemorySearchIndex(int dimensions, String model) {
        this.dimensions = dimensions;
        this.model = model;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String embeddingModel() {
        return model;
    }

    @Override
    public boolean needsRebuild() {
        return false;
    }

    @Override
    public void upsert(Collection<IndexedChunk> batch) {
        for (IndexedChunk c : batch) {
            if (c.embedding() == null || c.embedding().length != dimensions) {
                throw new IllegalArgumentException("Bad vector for " + c.chunkId());
            }
            chunks.put(c.chunkId(), c);
        }
    }

    @Override
    public void delete(Collection<String> chunkIds) {
        chunkIds.forEach(chunks::remove);
    }

    @Override
    public void deleteByDocument(String documentId) {
        chunks.values().removeIf(c -> c.documentId().equals(documentId));
    }

    @Override
    public void deleteByCourse(String courseId) {
        chunks.values().removeIf(c -> c.courseId().equals(courseId));
    }

    @Override
    public List<SearchHit> search(SearchQuery query) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void commit() {
        commits.incrementAndGet();
    }

    @Override
    public void rebuild(Stream<IndexedChunk> rows) {
        chunks.clear();
        upsert(rows.toList());
    }

    @Override
    public long size() {
        return chunks.size();
    }

    @Override
    public void close() {
    }

    IndexedChunk get(String chunkId) {
        return chunks.get(chunkId);
    }

    Set<String> chunkIdsOfCourse(String courseId) {
        return chunks.values().stream().filter(c -> c.courseId().equals(courseId)).map(IndexedChunk::chunkId)
                .collect(Collectors.toSet());
    }
}
