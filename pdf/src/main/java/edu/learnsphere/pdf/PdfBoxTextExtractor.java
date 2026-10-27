package edu.learnsphere.pdf;

import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.PdfTextExtractor;
import edu.learnsphere.core.util.CancellationToken;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.apache.pdfbox.pdmodel.PDDocument;

/**
 * Extracts the text layer of a PDF with PDFBox, page by page:
 *
 * <ul>
 *   <li>blocks (headings, paragraphs, list items) in reading order, worked out from glyph positions, so
 *       two-column pages read column by column (see {@link LayoutAnalyzer} and {@link ReadingOrder});</li>
 *   <li>each block's dominant font size and weight, and each page's body font size, so headings can be
 *       told apart from body text;</li>
 *   <li>words hyphenated across line ends rejoined;</li>
 *   <li>running headers, running footers and page numbers removed (see {@link MarginFilter}).</li>
 * </ul>
 *
 * <p>Every page is delivered, in order, including pages without text (as a {@link PageText} with no
 * blocks), so callers can count pages and spot scanned ones. A page whose content cannot be read is
 * delivered empty and logged; only problems with the whole file are thrown, as {@link PdfReadException}.
 * Stateless and thread-safe.
 */
public final class PdfBoxTextExtractor implements PdfTextExtractor {

    /** Creates an extractor. */
    public PdfBoxTextExtractor() {
    }

    /**
     * {@inheritDoc}
     *
     * <p>Pages reach {@code sink} a few pages behind the one being read, because running headers are found by
     * comparing each page with its neighbours.
     *
     * @throws PdfReadException when the file is missing, password-protected or damaged
     */
    @Override
    public void extract(Path pdf, Consumer<PageText> sink, CancellationToken token) throws IOException {
        Objects.requireNonNull(sink, "sink");
        CancellationToken cancellation = token == null ? CancellationToken.NONE : token;
        try (PDDocument document = PdfDocuments.open(pdf)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount == 0) {
                return;
            }
            MarginFilter filter = new MarginFilter(page -> sink.accept(PageTextBuilder.build(page)));
            collect(document, pdf, 1, pageCount, cancellation, filter);
            cancellation.throwIfCancelled();
            filter.finish();
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Gives the same result as {@link #extract} for that page: the neighbouring pages are read too, to
     * recognise running headers.
     *
     * @throws PdfReadException when the file is missing, password-protected or damaged, or has no such page
     */
    @Override
    public PageText extractPage(Path pdf, int pageNumber) throws IOException {
        try (PDDocument document = PdfDocuments.open(pdf)) {
            PdfDocuments.checkPageNumber(document, pageNumber, pdf);
            int first = Math.max(1, pageNumber - MarginFilter.WINDOW);
            int last = Math.min(document.getNumberOfPages(), pageNumber + MarginFilter.WINDOW);
            PageText[] result = new PageText[1];
            MarginFilter filter = new MarginFilter(page -> {
                if (page.pageNumber() == pageNumber) {
                    result[0] = PageTextBuilder.build(page);
                }
            });
            collect(document, pdf, first, last, CancellationToken.NONE, filter);
            filter.finish();
            return result[0] == null ? new PageText(pageNumber, 0, List.of()) : result[0];
        }
    }

    private static void collect(PDDocument document, Path pdf, int first, int last, CancellationToken token,
                                MarginFilter filter) throws IOException {
        new GlyphCollector(token, filter::accept).collect(document, pdf, first, last);
    }
}
