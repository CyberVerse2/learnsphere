package edu.learnsphere.core.spi;

import java.nio.file.Path;

/**
 * Opens a profile's search index (implemented with Lucene in the search module). The workspace opens one
 * index per profile, in {@code AppPaths.ProfilePaths.indexDir()}, with the embedder's
 * {@link Embedder#dimensions()} and {@link Embedder#modelName()}.
 *
 * <p>Opening never fails because of what is on disk. When the folder holds no index, an index written
 * with other dimensions or by another embedding model, or an index that cannot be read, the factory
 * clears it, opens an empty one recording the requested dimensions and model, and the returned index
 * reports {@link SearchIndex#needsRebuild()}. The workspace then queues a rebuild from the database
 * ({@code JobKind.REBUILD_INDEX}), because the index can always be rebuilt from the stored passages.
 */
@FunctionalInterface
public interface SearchIndexFactory {

    /**
     * Opens (creating if needed) the index in {@code indexDir}.
     *
     * @param dimensions     vector length of every embedding the index will hold
     * @param embeddingModel name of the embedder that produced them ({@link Embedder#modelName()})
     * @throws java.io.UncheckedIOException when the folder cannot be written at all
     */
    SearchIndex open(Path indexDir, int dimensions, String embeddingModel);
}
