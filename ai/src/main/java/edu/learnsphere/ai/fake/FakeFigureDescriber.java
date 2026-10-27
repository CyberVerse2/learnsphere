package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.FigureDescriber;
import edu.learnsphere.core.util.CancellationToken;
import java.util.List;

/** Describes one figure per non-empty page image. */
public final class FakeFigureDescriber implements FigureDescriber {

    @Override
    public List<String> describe(byte[] pngImage, int pageNumber, CancellationToken token) {
        token.throwIfCancelled();
        if (pngImage == null || pngImage.length == 0) {
            return List.of();
        }
        return List.of("A labelled diagram on page " + pageNumber + ".");
    }
}
