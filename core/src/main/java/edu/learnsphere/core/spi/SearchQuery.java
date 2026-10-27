package edu.learnsphere.core.spi;

import java.util.Objects;
import java.util.Set;

/**
 * A hybrid search restricted to one course: BM25 keyword search and vector search, merged with
 * reciprocal rank fusion.
 *
 * @param text          the question as typed
 * @param vector        the question's {@link EmbedKind#QUERY} embedding
 * @param courseId      only passages of this course
 * @param moduleIds     only passages of these modules (empty = whole course)
 * @param keywordTopK   keyword candidates (default 40)
 * @param vectorTopK    vector candidates (default 40)
 * @param limit         fused results to return (default 24)
 * @param rrfK          reciprocal rank fusion constant (default 60)
 */
public record SearchQuery(String text, float[] vector, String courseId, Set<String> moduleIds, int keywordTopK,
                          int vectorTopK, int limit, int rrfK) {

    public SearchQuery {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(courseId, "courseId");
        moduleIds = moduleIds == null ? Set.of() : Set.copyOf(moduleIds);
        if (keywordTopK < 0 || vectorTopK < 0 || limit <= 0 || rrfK <= 0) {
            throw new IllegalArgumentException("Invalid search sizes");
        }
    }

    /** The default search for answering a question in a course. */
    public static SearchQuery forCourse(String courseId, String text, float[] vector) {
        return new SearchQuery(text, vector, courseId, Set.of(), 40, 40, 24, 60);
    }

    /** A copy limited to some modules. */
    public SearchQuery inModules(Set<String> ids) {
        return new SearchQuery(text, vector, courseId, ids, keywordTopK, vectorTopK, limit, rrfK);
    }

    /** A copy returning at most {@code n} results. */
    public SearchQuery withLimit(int n) {
        return new SearchQuery(text, vector, courseId, moduleIds, keywordTopK, vectorTopK, n, rrfK);
    }
}
