package edu.learnsphere.core.model;

import java.util.Objects;

/**
 * Where a cited part of an answer came from; stored in {@code chat_messages.citations_json}.
 *
 * @param number        the marker number used in the answer text, as in {@code [2]}
 * @param chunkId       the passage, or null if it was removed since
 * @param documentId    the PDF
 * @param documentTitle the PDF's title when the answer was written
 * @param pageStart     first page of the passage
 * @param pageEnd       last page of the passage
 * @param snippet       the sentence or two that best matches the cited claim, used for highlighting
 */
public record Citation(int number, String chunkId, String documentId, String documentTitle, int pageStart, int pageEnd,
                       String snippet) {

    public Citation {
        Objects.requireNonNull(documentId, "documentId");
        documentTitle = Objects.requireNonNullElse(documentTitle, "");
        snippet = Objects.requireNonNullElse(snippet, "");
        if (number < 1) {
            throw new IllegalArgumentException("citation number must be >= 1: " + number);
        }
    }

    /** True when the passage spans more than one page. */
    public boolean isPageRange() {
        return pageEnd > pageStart;
    }
}
