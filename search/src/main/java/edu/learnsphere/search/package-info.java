/**
 * The per-profile Lucene index, implementing {@link edu.learnsphere.core.spi.SearchIndex}: BM25 keyword
 * search and HNSW vector search over passages, both restricted to a course (and optionally modules) before
 * ranking, merged with reciprocal rank fusion. The index is rebuildable from the database at any time.
 *
 * <p>Wire it into {@link edu.learnsphere.core.spi.Capabilities} with
 * {@code .searchIndexFactory(new LuceneSearchIndexFactory())}. {@link edu.learnsphere.search.LuceneSearchIndex}
 * describes durability, recovery and threading. {@code ./gradlew :search:benchmark} measures rebuild and search
 * latency over 50,000 passages.
 */
package edu.learnsphere.search;
