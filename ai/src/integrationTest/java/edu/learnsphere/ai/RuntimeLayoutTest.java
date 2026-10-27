package edu.learnsphere.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Checks that the development runtime folder has the layout described in docs/ARCHITECTURE.md
 * ("Runtime files"). Runs only through {@code ./gradlew :ai:integrationTest}, which is skipped when the
 * folder is missing.
 */
class RuntimeLayoutTest {

    private static Path runtime;

    @BeforeAll
    static void locate() {
        String property = System.getProperty("learnsphere.runtime");
        assertThat(property).as("learnsphere.runtime system property").isNotBlank();
        runtime = Path.of(property);
        assertThat(runtime).isDirectory();
    }

    private static List<Path> find(Path dir, String glob) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        var matcher = dir.getFileSystem().getPathMatcher("glob:" + glob);
        try (Stream<Path> files = Files.walk(dir)) {
            return files.filter(Files::isRegularFile).filter(p -> matcher.matches(p.getFileName())).toList();
        }
    }

    @Test
    void hasALlamaServerForThisPlatform() throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String platform = os.contains("win") ? "windows-x64" : os.contains("mac") ? "macos-arm64" : "linux-x64";
        String binary = os.contains("win") ? "llama-server.exe" : "llama-server";
        List<Path> servers = find(runtime.resolve("llama").resolve(platform), binary);
        assertThat(servers).as("llama-server under llama/%s", platform).isNotEmpty();
        if (!os.contains("win")) {
            assertThat(Files.isExecutable(servers.getFirst())).isTrue();
        }
    }

    @Test
    void hasTheRequiredModels() throws IOException {
        Path models = runtime.resolve("models");
        assertThat(find(models.resolve("chat-4b"), "*.gguf")).as("chat-4b model")
                .anySatisfy(p -> assertThat(p.getFileName().toString()).doesNotStartWith("mmproj"));
        assertThat(find(models.resolve("chat-4b"), "mmproj*.gguf")).as("chat-4b vision projector").isNotEmpty();
        assertThat(find(models.resolve("embed"), "*.gguf")).as("embedding model").isNotEmpty();
        assertThat(find(models.resolve("rerank"), "*.gguf")).as("reranking model").isNotEmpty();
    }
}
