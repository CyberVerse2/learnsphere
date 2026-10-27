package edu.learnsphere.core.spi;

import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

/**
 * The per-profile search index over passages (implemented with Lucene in the search module). It can
 * always be rebuilt from the database. Thread-safe; writes become visible to searches after
 * {@link #commit()}.
 */
public interface SearchIndex extends AutoCloseable {

    /** Adds or replaces passages by chunk id. */
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

    /** Replaces the whole index with {@code chunks} and commits. The caller closes the stream. */
    void rebuild(Stream<IndexedChunk> chunks);

    /** Number of passages in the index. */
    long size();

    /** Closes the index. */
    @Override
    void close();
}
