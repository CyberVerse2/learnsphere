package edu.learnsphere.core.spi;

import java.util.Objects;

/**
 * A passage produced by {@link Chunker}, before it is stored as a {@code Chunk}.
 *
 * @param ordinal       0-based order within the document
 * @param pageStart     first page (1-based)
 * @param pageEnd       last page
 * @param sectionTitle  nearest heading above the passage, or null
 * @param text          the passage text
 * @param tokenEstimate rough token count
 */
public record ChunkDraft(int ordinal, int pageStart, int pageEnd, String sectionTitle, String text, int tokenEstimate) {

    public ChunkDraft {
        Objects.requireNonNull(text, "text");
        if (pageStart < 1 || pageEnd < pageStart) {
            throw new IllegalArgumentException("Invalid page range " + pageStart + "-" + pageEnd);
        }
    }
}
