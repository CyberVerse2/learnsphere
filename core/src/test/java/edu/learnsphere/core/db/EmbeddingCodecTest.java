package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmbeddingCodecTest {

    @Test
    void roundTripsVectors() {
        float[] vector = {0f, 1f, -1.5f, 3.25e-7f, Float.MAX_VALUE, Float.MIN_VALUE};
        assertThat(EmbeddingCodec.decode(EmbeddingCodec.encode(vector))).containsExactly(vector);
    }

    @Test
    void writesLittleEndianFloat32() {
        byte[] bytes = EmbeddingCodec.encode(new float[] {1f});
        // 1.0f = 0x3F800000, little-endian: 00 00 80 3F
        assertThat(bytes).containsExactly(0x00, 0x00, 0x80, 0x3F);
    }

    @Test
    void keepsNullAndEmpty() {
        assertThat(EmbeddingCodec.encode(null)).isNull();
        assertThat(EmbeddingCodec.decode(null)).isNull();
        assertThat(EmbeddingCodec.decode(EmbeddingCodec.encode(new float[0]))).isEmpty();
    }

    @Test
    void rejectsTruncatedBytes() {
        assertThatThrownBy(() -> EmbeddingCodec.decode(new byte[] {1, 2, 3})).isInstanceOf(IllegalArgumentException.class);
    }
}
