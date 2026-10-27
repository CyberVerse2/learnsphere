package edu.learnsphere.ai.fake;

import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.util.CancellationToken;
import java.util.ArrayList;
import java.util.List;

/**
 * Hashed bag-of-words vectors: each word adds ±1 at a hashed position, then the vector is L2-normalized.
 * Texts that share words get similar vectors, so search behaves sensibly in tests. A text without words
 * maps to a fixed unit vector (cosine similarity is undefined for the zero vector).
 */
public final class FakeEmbedder implements Embedder {

    /** Same length as the bundled embedding model's vectors. */
    public static final int DEFAULT_DIMENSIONS = 1024;
    public static final String MODEL_NAME = "fake-embedder-v1";

    private final int dimensions;

    public FakeEmbedder() {
        this(DEFAULT_DIMENSIONS);
    }

    public FakeEmbedder(int dimensions) {
        if (dimensions < 8) {
            throw new IllegalArgumentException("dimensions must be >= 8: " + dimensions);
        }
        this.dimensions = dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts, EmbedKind kind, CancellationToken token) {
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (String text : texts) {
            token.throwIfCancelled();
            vectors.add(vector(text));
        }
        return vectors;
    }

    /** The vector for one text (the query/document kind does not matter for the fake). */
    public float[] vector(String text) {
        float[] v = new float[dimensions];
        for (String word : FakeText.words(text)) {
            if (word.length() < 2) {
                continue;
            }
            int hash = FakeText.fnv1a(word);
            int index = Math.floorMod(hash, dimensions);
            v[index] += (hash & 0x8000_0000) == 0 ? 1f : -1f;
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm == 0) {
            v[0] = 1f;
            return v;
        }
        float scale = (float) (1 / Math.sqrt(norm));
        for (int i = 0; i < v.length; i++) {
            v[i] *= scale;
        }
        return v;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String modelName() {
        return MODEL_NAME;
    }
}
