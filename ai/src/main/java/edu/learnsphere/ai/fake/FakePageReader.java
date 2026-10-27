package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.PageReader;
import edu.learnsphere.core.spi.PageTranscription;
import edu.learnsphere.core.util.CancellationToken;
import java.util.List;

/** Returns a fixed transcription naming the page; an empty image yields an empty, fully illegible page. */
public final class FakePageReader implements PageReader {

    @Override
    public PageTranscription read(byte[] pngImage, int pageNumber, CancellationToken token) {
        token.throwIfCancelled();
        if (pngImage == null || pngImage.length == 0) {
            return new PageTranscription("", List.of("page " + pageNumber));
        }
        return new PageTranscription("## Page " + pageNumber + "\n\nText read from page " + pageNumber + ".\n", List.of());
    }
}
