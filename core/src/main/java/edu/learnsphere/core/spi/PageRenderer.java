package edu.learnsphere.core.spi;

import java.io.IOException;
import java.nio.file.Path;

/** Renders PDF pages to PNG images. Implemented in the pdf module. Thread-safe. */
public interface PageRenderer {

    /** Points per inch in PDF user space; {@code scale = dpi / POINTS_PER_INCH}. */
    float POINTS_PER_INCH = 72f;

    /**
     * Renders page {@code pageNumber} (1-based) at {@code scale} (1 = 72 DPI) as PNG bytes.
     *
     * @throws IOException when the file cannot be read or the page does not exist
     */
    byte[] renderPng(Path pdf, int pageNumber, float scale) throws IOException;

    /** Renders at a resolution in dots per inch. */
    default byte[] renderPngAtDpi(Path pdf, int pageNumber, int dpi) throws IOException {
        return renderPng(pdf, pageNumber, dpi / POINTS_PER_INCH);
    }

    /**
     * Renders at {@code dpi}, or at a lower resolution when the image would have more than {@code maxPixels}
     * pixels, keeping the page's proportions. Implementations that cannot tell a page's size ignore the limit.
     */
    default byte[] renderPngAtDpi(Path pdf, int pageNumber, int dpi, long maxPixels) throws IOException {
        return renderPngAtDpi(pdf, pageNumber, dpi);
    }
}
