package edu.learnsphere.core.spi;

/**
 * One fused search result.
 *
 * @param score        reciprocal rank fusion score (higher is better; not comparable across queries)
 * @param keywordRank  1-based rank in the keyword results, or null if absent there
 * @param vectorRank   1-based rank in the vector results, or null if absent there
 */
public record SearchHit(String chunkId, String documentId, String courseId, String moduleId, String documentTitle,
                        int pageStart, int pageEnd, String sectionTitle, String text, double score,
                        Integer keywordRank, Integer vectorRank) {

    /** This hit as passage number {@code number} for the answerer, with {@code relevance} from the reranker. */
    public Passage toPassage(int number, double relevance) {
        return new Passage(number, chunkId, documentId, documentTitle, pageStart, pageEnd, sectionTitle, text, relevance);
    }
}
