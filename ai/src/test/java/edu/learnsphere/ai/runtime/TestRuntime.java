package edu.learnsphere.ai.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Builds small runtime folders (stand-in server file, tiny model files) for tests. */
final class TestRuntime {

    static final Platform PLATFORM = Platform.MACOS_ARM64;
    static final HardwareProbe.Hardware MACHINE_16GB =
            new HardwareProbe.Hardware(Optional.of(PLATFORM), 16L * HardwareProbe.GIB, 8);

    private TestRuntime() {
    }

    /** A complete runtime folder with the 4B chat model (with vision), an embedding model of {@code dims}, a reranker. */
    static Path create(Path root, int dims) throws IOException {
        Path runtime = Files.createDirectories(root.resolve("runtime"));
        server(runtime, "llama-b100");
        Path chat = Files.createDirectories(runtime.resolve("models/chat-4b"));
        Files.writeString(chat.resolve("Chat-4B-Q4_K_M.gguf"), "model");
        Files.writeString(chat.resolve("mmproj-F16.gguf"), "projector");
        Path embed = Files.createDirectories(runtime.resolve("models/embed"));
        Files.write(embed.resolve("Embed-0.6B-Q8_0.gguf"), gguf("qwen3", dims));
        Path rerank = Files.createDirectories(runtime.resolve("models/rerank"));
        Files.writeString(rerank.resolve("rerank.gguf"), "reranker");
        return runtime;
    }

    /** Adds a server executable under {@code llama/<platform>/<build>/}. */
    static Path server(Path runtime, String build) throws IOException {
        Path folder = Files.createDirectories(runtime.resolve("llama").resolve(PLATFORM.folder()).resolve(build));
        Path executable = folder.resolve(PLATFORM.serverExecutable());
        Files.writeString(executable, "#!/bin/sh\nexit 1\n");
        executable.toFile().setExecutable(true);
        return executable;
    }

    /**
     * A minimal GGUF v3 header: an array entry first (to exercise skipping), then
     * {@code general.architecture} and {@code <arch>.embedding_length}.
     */
    static byte[] gguf(String architecture, int embeddingLength) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeU32(out, 0x46554747);
        writeU32(out, 3);
        writeU64(out, 0); // tensors
        writeU64(out, 4); // metadata entries
        // tokenizer.ggml.tokens: array of 2 strings
        writeString(out, "tokenizer.ggml.tokens");
        writeU32(out, 9);
        writeU32(out, 8);
        writeU64(out, 2);
        writeString(out, "hello");
        writeString(out, "world");
        // a float32 value
        writeString(out, "general.score");
        writeU32(out, 6);
        writeU32(out, Float.floatToIntBits(0.5f));
        writeString(out, "general.architecture");
        writeU32(out, 8);
        writeString(out, architecture);
        writeString(out, architecture + ".embedding_length");
        writeU32(out, 4);
        writeU32(out, embeddingLength);
        return out.toByteArray();
    }

    private static void writeU32(ByteArrayOutputStream out, long value) {
        out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt((int) value).array());
    }

    private static void writeU64(ByteArrayOutputStream out, long value) {
        out.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array());
    }

    private static void writeString(ByteArrayOutputStream out, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeU64(out, bytes.length);
        out.writeBytes(bytes);
    }
}
