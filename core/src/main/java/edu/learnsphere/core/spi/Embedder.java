package edu.learnsphere.core.spi;

import edu.learnsphere.core.util.CancellationToken;
import java.util.List;

/**
 * Turns text into L2-normalized vectors (implemented in the ai module). Thread-safe.
 *
 * <p>AI SPI calls throw {@link edu.learnsphere.core.error.UnavailableException} while the study
 * assistant is not ready, another {@link edu.learnsphere.core.error.LearnSphereException} when a request
 * fails, and {@link java.util.concurrent.CancellationException} when the token is cancelled.
 */
public interface Embedder {

    /** Embeds each text; the result has one vector per text in the same order. */
    List<float[]> embed(List<String> texts, EmbedKind kind, CancellationToken token);

    /** Embeds one text. */
    default float[] embed(String text, EmbedKind kind) {
        return embed(List.of(text), kind, CancellationToken.NONE).getFirst();
    }

    /** Vector length. */
    int dimensions();

    /** Name stored with each embedding ({@code chunks.embedding_model}) so stale vectors can be found. */
    String modelName();
}
