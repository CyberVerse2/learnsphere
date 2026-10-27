package edu.learnsphere.ai.runtime;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Predicate;

/**
 * Reads scalar metadata from the header of a GGUF model file without loading the model, for example the
 * embedding length of an embedding model. Arrays (such as the tokenizer vocabulary) are skipped.
 */
public final class GgufMetadata {

    private static final int MAGIC = 0x46554747; // "GGUF" little-endian
    private static final long MAX_STRING = 16L * 1024 * 1024;
    private static final long MAX_ENTRIES = 1_000_000;

    private GgufMetadata() {
    }

    /** The {@code <architecture>.embedding_length} of a model (its vector size for embedding models). */
    public static OptionalInt embeddingLength(Path file) {
        try {
            Map<String, Object> values = read(file, seen -> {
                Object arch = seen.get("general.architecture");
                return arch != null && seen.containsKey(arch + ".embedding_length");
            });
            Object arch = values.get("general.architecture");
            Object length = arch == null ? null : values.get(arch + ".embedding_length");
            return length instanceof Number n && n.longValue() > 0 && n.longValue() <= Integer.MAX_VALUE
                    ? OptionalInt.of(n.intValue())
                    : OptionalInt.empty();
        } catch (IOException e) {
            return OptionalInt.empty();
        }
    }

    /**
     * Reads metadata entries in file order until {@code done} returns true for the entries read so far, or
     * the metadata ends. Numbers become {@link Long} or {@link Double}, booleans {@link Boolean}, strings
     * {@link String}; arrays are skipped.
     *
     * @throws IOException when the file cannot be read or is not a GGUF file
     */
    public static Map<String, Object> read(Path file, Predicate<Map<String, Object>> done) throws IOException {
        Map<String, Object> values = new LinkedHashMap<>();
        try (InputStream raw = Files.newInputStream(file); Reader in = new Reader(new BufferedInputStream(raw, 1 << 16))) {
            if (in.u32() != MAGIC) {
                throw new IOException("Not a GGUF file: " + file);
            }
            long version = in.u32();
            if (version < 2 || version > 3) {
                throw new IOException("Unsupported GGUF version " + version + ": " + file);
            }
            in.u64(); // tensor count
            long entries = in.u64();
            if (entries < 0 || entries > MAX_ENTRIES) {
                throw new IOException("Implausible GGUF metadata count " + entries + ": " + file);
            }
            for (long i = 0; i < entries; i++) {
                String key = in.string();
                int type = (int) in.u32();
                Object value = in.value(type);
                if (value != null) {
                    values.put(key, value);
                    if (done.test(values)) {
                        break;
                    }
                }
            }
        }
        return values;
    }

    /** Little-endian reader for the GGUF header. */
    private static final class Reader implements AutoCloseable {

        private final InputStream in;

        Reader(InputStream in) {
            this.in = in;
        }

        int u8() throws IOException {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("Unexpected end of GGUF header");
            }
            return b;
        }

        long u16() throws IOException {
            return u8() | ((long) u8() << 8);
        }

        long u32() throws IOException {
            return u16() | (u16() << 16);
        }

        long u64() throws IOException {
            return u32() | (u32() << 32);
        }

        String string() throws IOException {
            long length = u64();
            if (length < 0 || length > MAX_STRING) {
                throw new IOException("Implausible GGUF string length " + length);
            }
            byte[] bytes = in.readNBytes((int) length);
            if (bytes.length != length) {
                throw new EOFException("Unexpected end of GGUF header");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }

        void skip(long bytes) throws IOException {
            long left = bytes;
            while (left > 0) {
                long skipped = in.skip(left);
                if (skipped <= 0) {
                    u8();
                    skipped = 1;
                }
                left -= skipped;
            }
        }

        /** Reads a value of {@code type}; returns null for arrays (skipped). */
        Object value(int type) throws IOException {
            return switch (type) {
                case 0 -> (long) u8();
                case 1 -> (long) (byte) u8();
                case 2 -> u16();
                case 3 -> (long) (short) u16();
                case 4 -> u32();
                case 5 -> (long) (int) u32();
                case 6 -> (double) Float.intBitsToFloat((int) u32());
                case 7 -> u8() != 0;
                case 8 -> string();
                case 9 -> {
                    skipArray();
                    yield null;
                }
                case 10, 11 -> u64();
                case 12 -> Double.longBitsToDouble(u64());
                default -> throw new IOException("Unknown GGUF value type " + type);
            };
        }

        private void skipArray() throws IOException {
            int elementType = (int) u32();
            long count = u64();
            if (count < 0) {
                throw new IOException("Implausible GGUF array length " + count);
            }
            int width = switch (elementType) {
                case 0, 1, 7 -> 1;
                case 2, 3 -> 2;
                case 4, 5, 6 -> 4;
                case 10, 11, 12 -> 8;
                default -> -1;
            };
            if (width > 0) {
                skip(count * width);
                return;
            }
            for (long i = 0; i < count; i++) {
                if (elementType == 8) {
                    long length = u64();
                    if (length < 0 || length > MAX_STRING) {
                        throw new IOException("Implausible GGUF string length " + length);
                    }
                    skip(length);
                } else if (elementType == 9) {
                    skipArray();
                } else {
                    throw new IOException("Unknown GGUF array element type " + elementType);
                }
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
