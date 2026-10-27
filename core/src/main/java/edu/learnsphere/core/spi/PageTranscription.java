package edu.learnsphere.core.spi;

import java.util.List;
import java.util.Objects;

/**
 * Text read from a page image.
 *
 * @param markdown        the page as Markdown (headings, lists, tables)
 * @param illegibleSpans  short descriptions of parts that could not be read (for the reviewer)
 */
public record PageTranscription(String markdown, List<String> illegibleSpans) {

    public PageTranscription {
        Objects.requireNonNull(markdown, "markdown");
        illegibleSpans = illegibleSpans == null ? List.of() : List.copyOf(illegibleSpans);
    }

    /** True when some parts could not be read. */
    public boolean hasIllegibleParts() {
        return !illegibleSpans.isEmpty();
    }
}
