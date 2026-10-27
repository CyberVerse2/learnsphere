package edu.learnsphere.core.spi;

import java.util.Objects;

/**
 * A numbered passage given to the AI as evidence, cited in answers as {@code [number]}.
 *
 * @param number    1-based label
 * @param relevance reranker score 0–1 (or 0 when not reranked)
 */
public record Passage(int number, String chunkId, String documentId, String documentTitle, int pageStart, int pageEnd,
                      String sectionTitle, String text, double relevance) {

    public Passage {
        Objects.requireNonNull(text, "text");
        documentTitle = Objects.requireNonNullElse(documentTitle, "");
        if (number < 1) {
            throw new IllegalArgumentException("Passage numbers start at 1: " + number);
        }
    }

    /** "p. 12" or "pp. 12–13". */
    public String pageLabel() {
        return pageEnd > pageStart ? "pp. " + pageStart + "–" + pageEnd : "p. " + pageStart;
    }
}
