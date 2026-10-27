package edu.learnsphere.core.service;

import java.util.Objects;

/** A place in the material ("From your material" links): a PDF and its pages. */
public record SourceRef(String documentId, String documentTitle, int pageStart, int pageEnd, String chunkId) {

    public SourceRef {
        Objects.requireNonNull(documentId, "documentId");
        documentTitle = Objects.requireNonNullElse(documentTitle, "");
    }
}
