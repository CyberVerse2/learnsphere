package edu.learnsphere.core.sharing;

import edu.learnsphere.core.spi.EmbedKind;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.util.CancellationToken;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** A deterministic embedder (vectors from the text's bytes) that counts the passages it embeds. */
final class CountingEmbedder implements Embedder {

    private final int dimensions;
    private final String model;
    final AtomicInteger embedded = new AtomicInteger();

    CountingEmbedder(int dimensions, String model) {
        this.dimensions = dimensions;
        this.model = model;
    }

    @Override
    public List<float[]> embed(List<String> texts, EmbedKind kind, CancellationToken token) {
        token.throwIfCancelled();
        List<float[]> vectors = new ArrayList<>();
        for (String text : texts) {
            vectors.add(vector(text, dimensions));
        }
        embedded.addAndGet(texts.size());
        return vectors;
    }

    static float[] vector(String text, int dimensions) {
        float[] v = new float[dimensions];
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < bytes.length; i++) {
            v[i % dimensions] += bytes[i] / 128f;
        }
        v[0] += 1;
        return v;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String modelName() {
        return model;
    }
}
