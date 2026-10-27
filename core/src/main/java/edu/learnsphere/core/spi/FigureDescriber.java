package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;
import java.util.List;

/** Describes figures and diagrams on a page image (ai module, {@link ChatPriority#BACKGROUND}). */
public interface FigureDescriber {

    /**
     * One or two sentences per figure, in reading order; empty when the page has no meaningful figure.
     *
     * @param pngImage the page rendered at about 120 DPI
     */
    List<String> describe(byte[] pngImage, int pageNumber, CancellationToken token);
}
