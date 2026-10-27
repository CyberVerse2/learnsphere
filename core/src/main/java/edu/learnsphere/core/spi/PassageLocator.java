package edu.learnsphere.core.spi;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Finds where a passage of text appears on a PDF page, for highlighting citations. Implemented in the pdf module. */
public interface PassageLocator {

    /**
     * Rectangles (one per matched line) covering {@code passage} on page {@code pageNumber}, matched
     * tolerantly (case, whitespace, hyphenation). Empty when the passage cannot be found.
     */
    List<NormalizedRect> locate(Path pdf, int pageNumber, String passage) throws IOException;
}
