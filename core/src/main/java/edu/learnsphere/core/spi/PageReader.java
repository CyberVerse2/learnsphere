package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;

/** Transcribes a scanned page image to Markdown (ai module, {@link ChatPriority#BACKGROUND}). */
public interface PageReader {

    /**
     * @param pngImage the page rendered at about 150 DPI
     */
    PageTranscription read(byte[] pngImage, int pageNumber, CancellationToken token);
}
