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

    /** Renders at a resolution in dots per inch (150 for reading scanned pages). */
    default byte[] renderPngAtDpi(Path pdf, int pageNumber, int dpi) throws IOException {
        return renderPng(pdf, pageNumber, dpi / POINTS_PER_INCH);
    }
}
