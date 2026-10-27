package edu.learnsphere.core.spi;

import java.util.Objects;

/**
 * A passage as stored in the search index.
 *
 * @param embedding L2-normalized vector; must have the index's dimensions
 */
public record IndexedChunk(String chunkId, String documentId, String courseId, String moduleId, String documentTitle,
                           int pageStart, int pageEnd, String sectionTitle, String text, float[] embedding) {

    public IndexedChunk {
        Objects.requireNonNull(chunkId, "chunkId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(embedding, "embedding");
        documentTitle = Objects.requireNonNullElse(documentTitle, "");
    }
}
