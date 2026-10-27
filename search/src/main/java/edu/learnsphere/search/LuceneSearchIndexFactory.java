package edu.learnsphere.search;

import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.spi.SearchIndexFactory;
import java.nio.file.Path;

/**
 * Opens {@link LuceneSearchIndex}es: the search module's {@link SearchIndexFactory}, for
 * {@code Capabilities.builder().searchIndexFactory(new LuceneSearchIndexFactory())}. Stateless and
 * thread-safe; each call opens an independent index, and a folder can be open in only one index at a time.
 */
public final class LuceneSearchIndexFactory implements SearchIndexFactory {

    /** A factory; it holds no state. */
    public LuceneSearchIndexFactory() {
    }

    /** {@inheritDoc} See {@link LuceneSearchIndex#open}. */
    @Override
    public SearchIndex open(Path indexDir, int dimensions, String embeddingModel) {
        return LuceneSearchIndex.open(indexDir, dimensions, embeddingModel);
    }
}
