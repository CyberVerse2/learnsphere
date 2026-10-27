package edu.learnsphere.core.model;

import java.util.Objects;

/**
 * A row of {@code document_pages}: the text of one page.
 *
 * @param pageNumber  1-based page number
 * @param text        page text (Markdown for pages read from images)
 * @param source      text layer or read from the page image
 * @param reviewed    true once a creator checked an image-read page; text-layer pages are always usable
 * @param figureNotes descriptions of figures on the page, one per line, or null
 * @param hasImages   true when the page contains images
 */
public record DocumentPage(
        String documentId,
        int pageNumber,
        String text,
        PageSource source,
        boolean reviewed,
        String figureNotes,
        boolean hasImages,
        long createdAt,
        long updatedAt) {

    public DocumentPage {
        Objects.requireNonNull(documentId, "documentId");
        text = Objects.requireNonNullElse(text, "");
        Objects.requireNonNull(source, "source");
        if (pageNumber < 1) {
            throw new IllegalArgumentException("pageNumber must be >= 1: " + pageNumber);
        }
    }

    /** True when the page may be used as evidence: text-layer pages, or image-read pages a creator reviewed. */
    public boolean isUsableAsEvidence() {
        return source == PageSource.TEXT_LAYER || reviewed;
    }

    /** True for an image-read page that still needs a creator's review. */
    public boolean needsReview() {
        return source == PageSource.AI_READ && !reviewed;
    }
}
