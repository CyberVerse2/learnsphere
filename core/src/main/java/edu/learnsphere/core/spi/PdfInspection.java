package edu.learnsphere.core.spi;

import java.util.List;

/**
 * What {@link PdfInspector} found out about a file without extracting it.
 *
 * @param pageCount number of pages (0 when it could not be opened)
 * @param encrypted true when the file needs a password to open
 * @param damaged   true when the file could not be parsed
 * @param pages     one entry per page in page order; empty when encrypted or damaged
 */
public record PdfInspection(int pageCount, boolean encrypted, boolean damaged, List<PageInfo> pages) {

    public PdfInspection {
        pages = pages == null ? List.of() : List.copyOf(pages);
    }

    /** An inspection of a file that could not be opened. */
    public static PdfInspection damagedFile() {
        return new PdfInspection(0, false, true, List.of());
    }

    /** An inspection of a password-protected file. */
    public static PdfInspection encryptedFile() {
        return new PdfInspection(0, true, false, List.of());
    }

    /** True when the file can be imported page by page. */
    public boolean isReadable() {
        return !encrypted && !damaged && pageCount > 0;
    }

    /**
     * One page.
     *
     * @param pageNumber    1-based
     * @param hasTextLayer  true when the page has extractable text
     * @param textChars     number of non-whitespace characters in the text layer
     * @param imageCoverage share of the page area covered by images, 0–1
     * @param widthPt       page width in points
     * @param heightPt      page height in points
     */
    public record PageInfo(int pageNumber, boolean hasTextLayer, int textChars, double imageCoverage, float widthPt, float heightPt) {

        /** True for a page that looks scanned: almost no text, but an image. */
        public boolean looksScanned(int minTextChars) {
            return textChars < minTextChars && imageCoverage > 0;
        }
    }
}
