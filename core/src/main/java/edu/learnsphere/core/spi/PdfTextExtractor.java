package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Extracts the text layer of a PDF with block structure and font sizes. Implemented in the pdf module. */
public interface PdfTextExtractor {

    /**
     * Extracts every page in order and hands each to {@code sink} as soon as it is ready, so large
     * documents can be stored and reported page by page.
     *
     * @throws IOException                              when the file cannot be read
     * @throws java.util.concurrent.CancellationException when {@code token} is cancelled
     */
    void extract(Path pdf, Consumer<PageText> sink, CancellationToken token) throws IOException;

    /** Extracts one page (1-based). */
    PageText extractPage(Path pdf, int pageNumber) throws IOException;

    /** Extracts every page into a list. */
    default List<PageText> extractAll(Path pdf, CancellationToken token) throws IOException {
        List<PageText> pages = new java.util.ArrayList<>();
        extract(pdf, pages::add, token);
        return pages;
    }
}
