package edu.learnsphere.pdf;

import edu.learnsphere.core.spi.PdfInspection;
import edu.learnsphere.core.spi.PdfInspector;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inspects PDFs with PDFBox: page count, whether a password is needed, whether the file is damaged, and per
 * page the number of text characters and the share of the page covered by images (scanned pages are almost
 * all image and little or no text).
 *
 * <p>A file that opens without a password is not reported as encrypted, even when it carries an owner
 * password restricting printing or copying, because its pages can be read. A file with no pages, or whose
 * every page fails to read, is reported as damaged; a single unreadable page is reported as empty. Page sizes
 * are as displayed, with the page's rotation applied. Stateless and thread-safe.
 */
public final class PdfBoxInspector implements PdfInspector {

    private static final Logger LOG = LoggerFactory.getLogger(PdfBoxInspector.class);

    /** Creates an inspector. */
    public PdfBoxInspector() {
    }

    /**
     * {@inheritDoc}
     *
     * @throws PdfReadException ({@link PdfReadException.Reason#MISSING}) when the file is missing or unreadable
     */
    @Override
    public PdfInspection inspect(Path pdf) throws IOException {
        PDDocument document;
        try {
            document = PdfDocuments.open(pdf);
        } catch (PdfReadException e) {
            return switch (e.reason()) {
                case ENCRYPTED -> PdfInspection.encryptedFile();
                case DAMAGED -> {
                    LOG.info("PDF cannot be parsed: {}", pdf, e);
                    yield PdfInspection.damagedFile();
                }
                default -> throw e;
            };
        }
        try (document) {
            return inspect(document, pdf);
        }
    }

    private static PdfInspection inspect(PDDocument document, Path pdf) {
        int pageCount;
        try {
            pageCount = document.getNumberOfPages();
        } catch (RuntimeException e) {
            LOG.info("PDF has no readable page list: {}", pdf, e);
            return PdfInspection.damagedFile();
        }
        if (pageCount <= 0) {
            return PdfInspection.damagedFile();
        }
        List<PdfInspection.PageInfo> pages = new ArrayList<>(pageCount);
        int failed = 0;
        for (int index = 0; index < pageCount; index++) {
            PDPage page;
            try {
                page = document.getPage(index);
            } catch (RuntimeException e) {
                LOG.info("PDF page {} cannot be found: {}", index + 1, pdf, e);
                return PdfInspection.damagedFile();
            }
            PageGeometry geometry = PageGeometry.of(page);
            int textChars = 0;
            double coverage = 0;
            try {
                PageScanner scanner = new PageScanner(page).scan();
                textChars = scanner.textChars();
                coverage = scanner.imageCoverage();
            } catch (IOException | RuntimeException e) {
                failed++;
                LOG.warn("Page {} of {} cannot be read; it is treated as empty", index + 1, pdf, e);
            }
            pages.add(new PdfInspection.PageInfo(index + 1, textChars > 0, textChars, coverage,
                    geometry.displayWidth(), geometry.displayHeight()));
        }
        if (failed == pageCount) {
            return PdfInspection.damagedFile();
        }
        return new PdfInspection(pageCount, false, false, pages);
    }
}
