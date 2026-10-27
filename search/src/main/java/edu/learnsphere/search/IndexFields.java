package edu.learnsphere.search;

import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchHit;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;

/**
 * The Lucene document layout of a passage and the mapping in both directions.
 *
 * <ul>
 *   <li>{@value #CHUNK_ID}, {@value #DOCUMENT_ID}, {@value #COURSE_ID}, {@value #MODULE_ID}: exact keywords,
 *       stored, for upserts, deletes and the course and module filters ({@value #MODULE_ID} is absent for
 *       passages outside any module).</li>
 *   <li>{@value #TEXT} and {@value #SECTION_TITLE}: English-analysed full text (BM25), stored so hits carry
 *       the passage and callers can build snippets and highlights from it.</li>
 *   <li>{@value #DOCUMENT_TITLE}, {@value #PAGE_START}, {@value #PAGE_END}: stored only, for citations.</li>
 *   <li>{@value #VECTOR}: the unit-length embedding, HNSW-indexed with dot-product similarity.</li>
 * </ul>
 *
 * Changing this layout means raising {@link IndexMetadata#FORMAT}, so existing indexes are rebuilt.
 */
final class IndexFields {

    static final String CHUNK_ID = "chunk_id";
    static final String DOCUMENT_ID = "document_id";
    static final String COURSE_ID = "course_id";
    static final String MODULE_ID = "module_id";
    static final String DOCUMENT_TITLE = "document_title";
    static final String PAGE_START = "page_start";
    static final String PAGE_END = "page_end";
    static final String SECTION_TITLE = "section_title";
    static final String TEXT = "text";
    static final String VECTOR = "vector";

    /** Unit vectors make the dot product equal to cosine similarity, and it is the cheapest to compute. */
    static final VectorSimilarityFunction SIMILARITY = VectorSimilarityFunction.DOT_PRODUCT;

    private IndexFields() {
    }

    /** The term that identifies a passage, for updates and deletes. */
    static Term idTerm(String chunkId) {
        return new Term(CHUNK_ID, chunkId);
    }

    /** The Lucene document for {@code chunk}, with {@code unitVector} (already validated and normalized). */
    static Document toDocument(IndexedChunk chunk, float[] unitVector) {
        Document doc = new Document();
        doc.add(new StringField(CHUNK_ID, chunk.chunkId(), Store.YES));
        doc.add(new StringField(DOCUMENT_ID, chunk.documentId(), Store.YES));
        doc.add(new StringField(COURSE_ID, chunk.courseId(), Store.YES));
        if (chunk.moduleId() != null) {
            doc.add(new StringField(MODULE_ID, chunk.moduleId(), Store.YES));
        }
        doc.add(new StoredField(DOCUMENT_TITLE, chunk.documentTitle()));
        doc.add(new StoredField(PAGE_START, chunk.pageStart()));
        doc.add(new StoredField(PAGE_END, chunk.pageEnd()));
        if (chunk.sectionTitle() != null && !chunk.sectionTitle().isBlank()) {
            doc.add(new TextField(SECTION_TITLE, chunk.sectionTitle(), Store.YES));
        }
        doc.add(new TextField(TEXT, chunk.text(), Store.YES));
        doc.add(new KnnFloatVectorField(VECTOR, unitVector, SIMILARITY));
        return doc;
    }

    /** A search hit from a stored document. */
    static SearchHit toHit(Document stored, double score, Integer keywordRank, Integer vectorRank) {
        return new SearchHit(stored.get(CHUNK_ID), stored.get(DOCUMENT_ID), stored.get(COURSE_ID), stored.get(MODULE_ID),
                stored.get(DOCUMENT_TITLE), intValue(stored, PAGE_START), intValue(stored, PAGE_END),
                stored.get(SECTION_TITLE), stored.get(TEXT), score, keywordRank, vectorRank);
    }

    private static int intValue(Document stored, String name) {
        IndexableField field = stored.getField(name);
        return field == null || field.numericValue() == null ? 0 : field.numericValue().intValue();
    }
}
