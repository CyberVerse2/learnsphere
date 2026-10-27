package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.Arrays;
import java.util.Objects;

/**
 * A row of {@code chunks}: a passage of a document used for search, answers and item generation.
 *
 * @param moduleId       copied from the document, or null
 * @param ordinal        0-based order within the document
 * @param pageStart      first page the passage covers (1-based)
 * @param pageEnd        last page the passage covers
 * @param sectionTitle   nearest heading above the passage, or null
 * @param tokenEstimate  rough token count
 * @param embedding      L2-normalized vector, or null until embedded
 * @param embeddingModel name of the embedder that produced {@code embedding}, or null
 */
public record Chunk(
        String id,
        String documentId,
        String courseId,
        String moduleId,
        int ordinal,
        int pageStart,
        int pageEnd,
        String sectionTitle,
        String text,
        int tokenEstimate,
        float[] embedding,
        String embeddingModel,
        long createdAt,
        long updatedAt) {

    public Chunk {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(text, "text");
        if (pageStart < 1 || pageEnd < pageStart) {
            throw new IllegalArgumentException("Invalid page range " + pageStart + "-" + pageEnd);
        }
    }

    /** A new chunk without an embedding. */
    public static Chunk create(String documentId, String courseId, String moduleId, int ordinal, int pageStart, int pageEnd,
                               String sectionTitle, String text, int tokenEstimate, long now) {
        return new Chunk(Ids.newId(), documentId, courseId, moduleId, ordinal, pageStart, pageEnd, sectionTitle, text,
                tokenEstimate, null, null, now, now);
    }

    /** True once an embedding is stored. */
    public boolean hasEmbedding() {
        return embedding != null;
    }

    /** A copy with {@code vector} as its embedding. */
    public Chunk withEmbedding(float[] vector, String model, long now) {
        return new Chunk(id, documentId, courseId, moduleId, ordinal, pageStart, pageEnd, sectionTitle, text, tokenEstimate,
                vector, model, createdAt, now);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Chunk c
                && ordinal == c.ordinal && pageStart == c.pageStart && pageEnd == c.pageEnd
                && tokenEstimate == c.tokenEstimate && createdAt == c.createdAt && updatedAt == c.updatedAt
                && id.equals(c.id) && documentId.equals(c.documentId) && courseId.equals(c.courseId)
                && Objects.equals(moduleId, c.moduleId) && Objects.equals(sectionTitle, c.sectionTitle)
                && text.equals(c.text) && Arrays.equals(embedding, c.embedding)
                && Objects.equals(embeddingModel, c.embeddingModel);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, documentId, ordinal, text, Arrays.hashCode(embedding));
    }

    @Override
    public String toString() {
        return "Chunk[id=" + id + ", documentId=" + documentId + ", ordinal=" + ordinal + ", pages=" + pageStart + "-"
                + pageEnd + ", sectionTitle=" + sectionTitle + ", tokens=" + tokenEstimate + ", embedding="
                + (embedding == null ? "none" : embedding.length + " dims") + "]";
    }
}
