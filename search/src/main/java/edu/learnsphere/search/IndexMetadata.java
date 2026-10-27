package edu.learnsphere.search;

import java.util.HashMap;
import java.util.Map;

/**
 * What an index was built for, kept in the user data of every Lucene commit so it survives restarts and is
 * replaced atomically with the commit it describes.
 *
 * @param format         {@link #FORMAT} of the code that wrote the index
 * @param dimensions     vector length
 * @param embeddingModel name of the embedder that produced the vectors
 * @param complete       false from the moment an index is cleared until a {@code rebuild} commits, so an
 *                       index left half-filled by a crash or a cancelled rebuild is rebuilt again
 */
record IndexMetadata(String format, int dimensions, String embeddingModel, boolean complete) {

    /** Version of the field layout in {@link IndexFields}; raise it whenever that layout changes. */
    static final String FORMAT = "1";

    static final String KEY_FORMAT = "learnsphere.format";
    static final String KEY_DIMENSIONS = "learnsphere.dimensions";
    static final String KEY_EMBEDDING_MODEL = "learnsphere.embeddingModel";
    static final String KEY_COMPLETE = "learnsphere.complete";

    /** Metadata of an index written by this code. */
    static IndexMetadata current(int dimensions, String embeddingModel, boolean complete) {
        return new IndexMetadata(FORMAT, dimensions, embeddingModel, complete);
    }

    /** The commit user data to store. */
    Map<String, String> toCommitData() {
        return Map.of(
                KEY_FORMAT, format,
                KEY_DIMENSIONS, Integer.toString(dimensions),
                KEY_EMBEDDING_MODEL, embeddingModel,
                KEY_COMPLETE, Boolean.toString(complete));
    }

    /**
     * Why an index whose last commit carries {@code commitData} cannot be used for {@code dimensions} and
     * {@code embeddingModel}, or null when it can.
     */
    static String staleReason(Iterable<Map.Entry<String, String>> commitData, int dimensions, String embeddingModel) {
        Map<String, String> data = new HashMap<>();
        if (commitData != null) {
            commitData.forEach(e -> data.put(e.getKey(), e.getValue()));
        }
        if (!FORMAT.equals(data.get(KEY_FORMAT))) {
            return "it has layout " + data.get(KEY_FORMAT) + " instead of " + FORMAT;
        }
        if (!Integer.toString(dimensions).equals(data.get(KEY_DIMENSIONS))) {
            return "it holds vectors of " + data.get(KEY_DIMENSIONS) + " dimensions instead of " + dimensions;
        }
        if (!embeddingModel.equals(data.get(KEY_EMBEDDING_MODEL))) {
            return "its vectors come from " + data.get(KEY_EMBEDDING_MODEL) + " instead of " + embeddingModel;
        }
        if (!Boolean.parseBoolean(data.get(KEY_COMPLETE))) {
            return "its last rebuild did not finish";
        }
        return null;
    }
}
