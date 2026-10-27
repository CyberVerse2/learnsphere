package edu.learnsphere.core.spi;

import java.io.IOException;
import java.nio.file.Path;

/** Looks at a PDF without extracting it. Implemented in the pdf module. Thread-safe. */
public interface PdfInspector {

    /**
     * Inspects {@code pdf}. Encrypted or damaged files are reported in the result, not thrown.
     *
     * @throws IOException when the file cannot be read at all (missing, unreadable)
     */
    PdfInspection inspect(Path pdf) throws IOException;
}
