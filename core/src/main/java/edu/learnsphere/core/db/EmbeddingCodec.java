package edu.learnsphere.core.db;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Converts embedding vectors to and from the {@code chunks.embedding} BLOB: float32 values, little-endian. */
public final class EmbeddingCodec {

    private EmbeddingCodec() {
    }

    /** Encodes {@code vector} as little-endian float32 bytes; null stays null. */
    public static byte[] encode(float[] vector) {
        if (vector == null) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(vector);
        return buffer.array();
    }

    /**
     * Decodes little-endian float32 bytes; null stays null.
     *
     * @throws IllegalArgumentException when the length is not a multiple of four
     */
    public static float[] decode(byte[] bytes) {
        if (bytes == null) {
            return null;
        }
        if (bytes.length % Float.BYTES != 0) {
            throw new IllegalArgumentException("Embedding byte length must be a multiple of 4: " + bytes.length);
        }
        float[] vector = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector);
        return vector;
    }
}
