package edu.learnsphere.core.service.impl.pipeline;

import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.spi.SearchQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A search index for pipeline tests: keyword matches (shared words of three or more letters) and cosine
 * similarity, fused by reciprocal rank like the real index. Writes are seen by searches only after
 * {@link #commit()}. Thread-safe.
 */
final class InMemorySearchIndex implements SearchIndex {

    private final int dimensions;
    private final String model;
    private final Map<String, IndexedChunk> staged = new LinkedHashMap<>();
    private Map<String, IndexedChunk> committed = Map.of();
    private volatile boolean needsRebuild;
    private int commits;

    InMemorySearchIndex(int dimensions, String model) {
        this.dimensions = dimensions;
        this.model = model;
    }

    void setNeedsRebuild(boolean value) {
        needsRebuild = value;
    }

    synchronized int commits() {
        return commits;
    }

    /** Committed passages of a document. */
    synchronized List<IndexedChunk> chunksOf(String documentId) {
        return committed.values().stream().filter(c -> c.documentId().equals(documentId)).toList();
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
        return needsRebuild;
    }

    @Override
    public synchronized void upsert(Collection<IndexedChunk> chunks) {
        for (IndexedChunk chunk : chunks) {
            if (chunk.embedding().length != dimensions) {
                throw new IllegalArgumentException("Wrong number of dimensions");
            }
            staged.put(chunk.chunkId(), chunk);
        }
    }

    @Override
    public synchronized void delete(Collection<String> chunkIds) {
        chunkIds.forEach(staged::remove);
    }

    @Override
    public synchronized void deleteByDocument(String documentId) {
        staged.values().removeIf(chunk -> chunk.documentId().equals(documentId));
    }

    @Override
    public synchronized void deleteByCourse(String courseId) {
        staged.values().removeIf(chunk -> chunk.courseId().equals(courseId));
    }

    @Override
    public synchronized void commit() {
        committed = Map.copyOf(staged);
        commits++;
    }

    @Override
    public synchronized void rebuild(Stream<IndexedChunk> chunks) {
        staged.clear();
        chunks.forEach(chunk -> staged.put(chunk.chunkId(), chunk));
        commit();
        needsRebuild = false;
    }

    @Override
    public synchronized long size() {
        return committed.size();
    }

    @Override
    public List<SearchHit> search(SearchQuery query) {
        List<IndexedChunk> candidates;
        synchronized (this) {
            candidates = committed.values().stream()
                    .filter(c -> c.courseId().equals(query.courseId()))
                    .filter(c -> query.moduleIds().isEmpty() || query.moduleIds().contains(c.moduleId()))
                    .sorted(Comparator.comparing(IndexedChunk::chunkId))
                    .toList();
        }
        Set<String> queryWords = words(query.text());
        List<IndexedChunk> byKeyword = candidates.stream()
                .filter(c -> shared(queryWords, c.text()) > 0)
                .sorted(Comparator.comparingInt((IndexedChunk c) -> shared(queryWords, c.text())).reversed())
                .limit(query.keywordTopK())
                .toList();
        List<IndexedChunk> byVector = query.vector() == null ? List.of() : candidates.stream()
                .sorted(Comparator.comparingDouble((IndexedChunk c) -> PipelineText.cosine(query.vector(), c.embedding()))
                        .reversed())
                .limit(query.vectorTopK())
                .toList();
        Map<String, Double> scores = new HashMap<>();
        Map<String, Integer> keywordRanks = new HashMap<>();
        Map<String, Integer> vectorRanks = new HashMap<>();
        Map<String, IndexedChunk> byId = new HashMap<>();
        for (int i = 0; i < byKeyword.size(); i++) {
            IndexedChunk chunk = byKeyword.get(i);
            byId.put(chunk.chunkId(), chunk);
            keywordRanks.put(chunk.chunkId(), i + 1);
            scores.merge(chunk.chunkId(), 1.0 / (query.rrfK() + i + 1), Double::sum);
        }
        for (int i = 0; i < byVector.size(); i++) {
            IndexedChunk chunk = byVector.get(i);
            byId.put(chunk.chunkId(), chunk);
            vectorRanks.put(chunk.chunkId(), i + 1);
            scores.merge(chunk.chunkId(), 1.0 / (query.rrfK() + i + 1), Double::sum);
        }
        List<SearchHit> hits = new ArrayList<>();
        scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(query.limit())
                .forEach(entry -> {
                    IndexedChunk c = byId.get(entry.getKey());
                    hits.add(new SearchHit(c.chunkId(), c.documentId(), c.courseId(), c.moduleId(), c.documentTitle(),
                            c.pageStart(), c.pageEnd(), c.sectionTitle(), c.text(), entry.getValue(),
                            keywordRanks.get(c.chunkId()), vectorRanks.get(c.chunkId())));
                });
        return hits;
    }

    private static Set<String> words(String text) {
        Set<String> words = new HashSet<>();
        for (String word : text.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 3) {
                words.add(word);
            }
        }
        return words;
    }

    private static int shared(Set<String> queryWords, String text) {
        Set<String> textWords = words(text);
        int count = 0;
        for (String word : queryWords) {
            if (textWords.contains(word)) {
                count++;
            }
        }
        return count;
    }

    @Override
    public void close() {
    }
}
