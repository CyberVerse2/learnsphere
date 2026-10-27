package edu.learnsphere.core.spi;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * The per-profile search index over passages (implemented with Lucene in the search module), opened by a
 * {@link SearchIndexFactory}. It can always be rebuilt from the database. Thread-safe; writes become
 * visible to searches after {@link #commit()}.
 */
public interface SearchIndex extends AutoCloseable {

    /** Vector length every passage's embedding must have. */
    int dimensions();

    /** Name of the embedder whose vectors the index holds. */
    String embeddingModel();

    /**
     * True when the index was opened empty because the folder held no index, an unreadable one, or one
     * built with other dimensions or another embedding model. Becomes false once {@link #rebuild}
     * completes. Searches on an index that needs a rebuild return nothing.
     */
    boolean needsRebuild();

    /**
     * Adds or replaces passages by chunk id.
     *
     * @throws IllegalArgumentException when an embedding does not have {@link #dimensions()} values
     */
    void upsert(Collection<IndexedChunk> chunks);

    /** Removes passages by chunk id. */
    void delete(Collection<String> chunkIds);

    /** Removes every passage of a document. */
    void deleteByDocument(String documentId);

    /** Removes every passage of a course. */
    void deleteByCourse(String courseId);

    /** Hybrid search, best first. */
    List<SearchHit> search(SearchQuery query);

    /** Makes pending writes durable and visible to searches. */
    void commit();

    /**
     * Replaces the whole index with {@code chunks}, commits and clears {@link #needsRebuild()}. Consumes the
     * stream once, in order, without holding it all in memory (feed it from
     * {@code ChunkRepository.withIndexable}). The caller closes the stream. Searches keep seeing the old
     * contents until the rebuild commits.
     */
    void rebuild(Stream<IndexedChunk> chunks);

    /** Number of passages in the index. */
    long size();

    /** Closes the index. */
    @Override
    void close();
}
