package edu.learnsphere.core.spi;

import java.util.List;

/**
 * Splits one document's pages into heading-aware passages. Implemented in the pdf module. Passages never
 * span documents. Figure descriptions are passed as extra blocks at the end of their page, written as
 * {@code "Figure: …"} lines, and end up in the passage covering that page.
 */
public interface Chunker {

    /** Target and overlap sizes in tokens. */
    record Options(int targetTokens, int overlapTokens) {

        /** About 350 tokens per passage with about 60 tokens of overlap. */
        public static final Options DEFAULT = new Options(350, 60);

        public Options {
            if (targetTokens <= 0 || overlapTokens < 0 || overlapTokens >= targetTokens) {
                throw new IllegalArgumentException("Invalid chunk sizes " + targetTokens + "/" + overlapTokens);
            }
        }
    }

    /** Passages for {@code pages} (in page order), numbered from 0. */
    List<ChunkDraft> chunk(List<PageText> pages, Options options);

    /** Passages with {@link Options#DEFAULT}. */
    default List<ChunkDraft> chunk(List<PageText> pages) {
        return chunk(pages, Options.DEFAULT);
    }
}
