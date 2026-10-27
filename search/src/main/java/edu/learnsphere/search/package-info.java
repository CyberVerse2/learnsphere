/**
 * The per-profile Lucene index, implementing {@link edu.learnsphere.core.spi.SearchIndex}: BM25 keyword
 * search and HNSW vector search over passages, merged with reciprocal rank fusion and filtered by course
 * and module. The index is rebuildable from the database at any time.
 */
package edu.learnsphere.search;
