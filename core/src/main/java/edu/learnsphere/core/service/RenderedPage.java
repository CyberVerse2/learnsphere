package edu.learnsphere.core.service;

import java.util.Objects;

/** A PDF page rendered to PNG, ready for {@code new Image(new ByteArrayInputStream(png))}. */
public record RenderedPage(String documentId, int pageNumber, byte[] png, int widthPx, int heightPx) {

    public RenderedPage {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(png, "png");
    }
}
