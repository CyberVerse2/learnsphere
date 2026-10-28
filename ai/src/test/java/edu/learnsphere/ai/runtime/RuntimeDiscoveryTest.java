package edu.learnsphere.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.spi.ModelChoice;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Platform detection, finding the runtime folder, reading model files and choosing a model. */
class RuntimeDiscoveryTest {

    @TempDir
    Path temp;

    // ------------------------------------------------------------------ platform

    @Test
    void detectsTheBundledPlatforms() {
        assertThat(Platform.detect("Mac OS X", "aarch64")).contains(Platform.MACOS_ARM64);
        assertThat(Platform.detect("Windows 11", "amd64")).contains(Platform.WINDOWS_X64);
        assertThat(Platform.detect("Linux", "x86_64")).contains(Platform.LINUX_X64);
        assertThat(Platform.detect("Linux", "aarch64")).contains(Platform.LINUX_ARM64);
        assertThat(Platform.WINDOWS_X64.serverExecutable()).isEqualTo("llama-server.exe");
    }

    @Test
    void noPlatformForUnsupportedCombinations() {
        assertThat(Platform.detect("Mac OS X", "x86_64")).isEmpty();
        assertThat(Platform.detect("Windows 11", "aarch64")).isEmpty();
        assertThat(Platform.detect("FreeBSD", "amd64")).isEmpty();
    }

    // ------------------------------------------------------------------ locating

    @Test
    void theSystemPropertyWinsThenTheEnvironmentVariable() throws IOException {
        Path fromProperty = Files.createDirectories(temp.resolve("a"));
        Path fromEnvironment = Files.createDirectories(temp.resolve("b"));
        Properties properties = new Properties();
        properties.setProperty(RuntimeLocator.PROPERTY, fromProperty.toString());
        Map<String, String> environment = Map.of(RuntimeLocator.ENVIRONMENT, fromEnvironment.toString());

        assertThat(RuntimeLocator.locate(properties, environment, Optional.empty(), temp)).contains(fromProperty.toAbsolutePath());
        assertThat(RuntimeLocator.locate(new Properties(), environment, Optional.empty(), temp))
                .contains(fromEnvironment.toAbsolutePath());
    }

    @Test
    void aSettingThatNamesNoFolderIsSkipped() throws IOException {
        Path app = Files.createDirectories(temp.resolve("app"));
        Path packaged = Files.createDirectories(app.resolve(RuntimeLocator.FOLDER));
        Properties properties = new Properties();
        properties.setProperty(RuntimeLocator.PROPERTY, temp.resolve("missing").toString());

        assertThat(RuntimeLocator.locate(properties, Map.of(), Optional.of(app), temp)).contains(packaged.toAbsolutePath());
    }

    @Test
    void developmentFindsTheRuntimeAtTheRepositoryRoot() throws IOException {
        Path repo = Files.createDirectories(temp.resolve("repo"));
        Files.writeString(repo.resolve("settings.gradle.kts"), "");
        Path runtime = Files.createDirectories(repo.resolve(RuntimeLocator.FOLDER));
        Path module = Files.createDirectories(repo.resolve("ai/build"));

        assertThat(RuntimeLocator.locate(new Properties(), Map.of(), Optional.empty(), module)).contains(runtime.toAbsolutePath());
        assertThat(RuntimeLocator.locate(new Properties(), Map.of(), Optional.empty(), temp)).isEmpty();
    }

    // ------------------------------------------------------------------ hardware

    @Test
    void memoryChecksAllowForMachinesReportingSlightlyLess() {
        HardwareProbe.Hardware sixteen = new HardwareProbe.Hardware(Optional.of(Platform.MACOS_ARM64),
                (long) (15.8 * HardwareProbe.GIB), 8);
        assertThat(sixteen.hasAtLeastGib(16)).isTrue();
        assertThat(sixteen.hasAtLeastGib(24)).isFalse();
        HardwareProbe.Hardware unknown = new HardwareProbe.Hardware(Optional.empty(), 0, 4);
        assertThat(unknown.memoryKnown()).isFalse();
        assertThat(unknown.hasAtLeastGib(1)).isFalse();
    }

    @Test
    void readsLinuxMemory() throws IOException {
        Path meminfo = Files.writeString(temp.resolve("meminfo"), "MemTotal:       16303428 kB\nMemFree: 1 kB\n");
        assertThat(HardwareProbe.fromMeminfo(meminfo)).isEqualTo(16303428L * 1024);
        assertThat(HardwareProbe.fromMeminfo(temp.resolve("none"))).isZero();
    }

    @Test
    void readingWindowsMemoryElsewhereReportsUnknown() {
        if (System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("windows")) {
            assertThat(HardwareProbe.fromGlobalMemoryStatus()).isGreaterThan(HardwareProbe.GIB);
        } else {
            assertThat(HardwareProbe.fromGlobalMemoryStatus()).isZero();
        }
    }

    @Test
    void probingThisComputerNeverFails() {
        HardwareProbe.Hardware here = HardwareProbe.probe();
        assertThat(here.processors()).isPositive();
        assertThat(here.totalMemoryBytes()).isNotNegative();
    }

    // ------------------------------------------------------------------ model files

    @Test
    void readsTheEmbeddingLengthFromTheModelHeader() throws IOException {
        Path model = Files.write(temp.resolve("e.gguf"), TestRuntime.gguf("qwen3", 768));
        assertThat(GgufMetadata.embeddingLength(model)).isEqualTo(OptionalInt.of(768));
        Path notGguf = Files.writeString(temp.resolve("x.gguf"), "not a model");
        assertThat(GgufMetadata.embeddingLength(notGguf)).isEmpty();
        assertThat(GgufMetadata.embeddingLength(temp.resolve("missing.gguf"))).isEmpty();
    }

    @Test
    void scansACompleteRuntime() throws IOException {
        Path runtime = TestRuntime.create(temp, 8);
        ModelCatalog catalog = ModelCatalog.scan(Optional.of(runtime), TestRuntime.MACHINE_16GB);

        assertThat(catalog.serverExecutable()).hasValueSatisfying(p -> assertThat(p.toString()).contains("llama-b100"));
        assertThat(catalog.chatModel(ModelChoice.CHAT_4B)).hasValueSatisfying(chat -> {
            assertThat(chat.model().getFileName().toString()).isEqualTo("Chat-4B-Q4_K_M.gguf");
            assertThat(chat.hasVision()).isTrue();
        });
        assertThat(catalog.embeddingDimensions()).isEqualTo(8);
        assertThat(catalog.embeddingModelName()).isEqualTo("Embed-0.6B-Q8_0");
        assertThat(catalog.availableModels()).containsExactly(ModelChoice.CHAT_4B);
        assertThat(catalog.recommendedModel()).isEqualTo(ModelChoice.CHAT_4B);
        assertThat(catalog.problem(ModelChoice.AUTO)).isEmpty();
        assertThat(catalog.problem(ModelChoice.CHAT_9B)).hasValueSatisfying(p -> assertThat(p).contains("chat-9b"));
    }

    @Test
    void onAppleSiliconAnMlxChatModelIsUsedWhenItsPythonIsPresent() throws IOException {
        Path runtime = TestRuntime.create(temp, 8);
        Path folder = TestRuntime.mlxChat(runtime, "chat-4b");
        assertThat(ModelCatalog.scan(Optional.of(runtime), TestRuntime.MACHINE_16GB).mlxChatModel(ModelChoice.AUTO))
                .as("no MLX Python yet").isEmpty();

        TestRuntime.mlxPython(runtime);
        ModelCatalog catalog = ModelCatalog.scan(Optional.of(runtime), TestRuntime.MACHINE_16GB);
        assertThat(catalog.mlxPython()).isPresent();
        assertThat(catalog.mlxChatModel(ModelChoice.AUTO)).contains(folder.toAbsolutePath().normalize());
        assertThat(catalog.mlxChatModel(ModelChoice.CHAT_9B)).isEmpty();
        assertThat(catalog.problem(ModelChoice.AUTO)).isEmpty();

        HardwareProbe.Hardware windows = new HardwareProbe.Hardware(Optional.of(Platform.WINDOWS_X64), 16L * HardwareProbe.GIB, 8);
        assertThat(ModelCatalog.scan(Optional.of(runtime), windows).mlxChatModel(ModelChoice.AUTO)).isEmpty();
    }

    @Test
    void anMlxFolderWithoutWeightsIsIgnored() throws IOException {
        Path runtime = TestRuntime.create(temp, 8);
        TestRuntime.mlxPython(runtime);
        Path folder = TestRuntime.mlxChat(runtime, "chat-4b");
        Files.delete(folder.resolve("model.safetensors"));
        assertThat(ModelCatalog.scan(Optional.of(runtime), TestRuntime.MACHINE_16GB).mlxChatModel(ModelChoice.AUTO)).isEmpty();
    }

    @Test
    void theNewestServerBuildWins() throws IOException {
        Path runtime = TestRuntime.create(temp, 8);
        TestRuntime.server(runtime, "llama-b99");
        TestRuntime.server(runtime, "llama-b11550");
        ModelCatalog catalog = ModelCatalog.scan(Optional.of(runtime), TestRuntime.MACHINE_16GB);
        assertThat(catalog.serverExecutable()).hasValueSatisfying(p -> assertThat(p.toString()).contains("llama-b11550"));
    }

    @Test
    void theLargerModelNeedsItsFilesAndEnoughMemory() throws IOException {
        Path runtime = TestRuntime.create(temp, 8);
        Path nine = Files.createDirectories(runtime.resolve("models/chat-9b"));
        Files.writeString(nine.resolve("Chat-9B.gguf"), "model");

        ModelCatalog sixteen = ModelCatalog.scan(Optional.of(runtime), TestRuntime.MACHINE_16GB);
        assertThat(sixteen.availableModels()).containsExactlyInAnyOrder(ModelChoice.CHAT_4B, ModelChoice.CHAT_9B);
        assertThat(sixteen.recommendedModel()).isEqualTo(ModelChoice.CHAT_9B);
        assertThat(sixteen.chatModel(ModelChoice.CHAT_9B).orElseThrow().hasVision()).isFalse();

        ModelCatalog eight = ModelCatalog.scan(Optional.of(runtime),
                new HardwareProbe.Hardware(Optional.of(TestRuntime.PLATFORM), 8L * HardwareProbe.GIB, 8));
        assertThat(eight.availableModels()).containsExactly(ModelChoice.CHAT_4B);
        assertThat(eight.recommendedModel()).isEqualTo(ModelChoice.CHAT_4B);
        assertThat(eight.problem(ModelChoice.CHAT_9B)).hasValueSatisfying(p -> assertThat(p).contains("memory"));

        ModelCatalog unknown = ModelCatalog.scan(Optional.of(runtime),
                new HardwareProbe.Hardware(Optional.of(TestRuntime.PLATFORM), 0, 8));
        assertThat(unknown.availableModels()).containsExactly(ModelChoice.CHAT_4B);
        assertThat(unknown.recommendedModel()).isEqualTo(ModelChoice.CHAT_4B);
        assertThat(unknown.problem(ModelChoice.CHAT_9B)).hasValueSatisfying(p -> assertThat(p).contains("memory"));
    }

    @Test
    void aSplitModelIsUsedThroughItsFirstPart() throws IOException {
        Path runtime = TestRuntime.create(temp, 8);
        Path chat = runtime.resolve("models/chat-4b");
        Files.delete(chat.resolve("Chat-4B-Q4_K_M.gguf"));
        Files.writeString(chat.resolve("Chat-4B-Q4_K_M-00002-of-00002.gguf"), "part two");
        Files.writeString(chat.resolve("Chat-4B-Q4_K_M-00001-of-00002.gguf"), "part one");
        Path embed = runtime.resolve("models/embed");
        Files.move(embed.resolve("Embed-0.6B-Q8_0.gguf"), embed.resolve("Embed-0.6B-Q8_0-00001-of-00001.gguf"));

        ModelCatalog catalog = ModelCatalog.scan(Optional.of(runtime), TestRuntime.MACHINE_16GB);

        assertThat(catalog.chatModel(ModelChoice.CHAT_4B).orElseThrow().model().getFileName())
                .hasToString("Chat-4B-Q4_K_M-00001-of-00002.gguf");
        assertThat(catalog.embeddingModelName()).isEqualTo("Embed-0.6B-Q8_0");
        assertThat(catalog.embeddingDimensions()).isEqualTo(8);
    }

    @Test
    void anEmptyOrMissingRuntimeExplainsWhatIsMissing() throws IOException {
        ModelCatalog none = ModelCatalog.scan(Optional.empty(), TestRuntime.MACHINE_16GB);
        assertThat(none.availableModels()).isEmpty();
        assertThat(none.problem(ModelChoice.AUTO)).hasValueSatisfying(p -> assertThat(p).contains("no runtime folder"));
        assertThat(none.embeddingDimensions()).isEqualTo(ModelCatalog.DEFAULT_EMBEDDING_DIMENSIONS);
        assertThat(none.embeddingModelName()).isEqualTo(ModelCatalog.DEFAULT_EMBEDDING_NAME);

        Path empty = Files.createDirectories(temp.resolve("empty"));
        ModelCatalog nothing = ModelCatalog.scan(Optional.of(empty), TestRuntime.MACHINE_16GB);
        assertThat(nothing.problem(ModelChoice.AUTO)).hasValueSatisfying(p -> assertThat(p).contains("llama-server"));

        ModelCatalog otherPlatform = ModelCatalog.scan(Optional.of(TestRuntime.create(temp, 8)),
                new HardwareProbe.Hardware(Optional.empty(), 0, 4));
        assertThat(otherPlatform.availableModels()).isEmpty();
        assertThat(otherPlatform.problem(ModelChoice.AUTO)).hasValueSatisfying(p -> assertThat(p).contains("no llama.cpp build"));
    }

    @Test
    void buildNumbersComeFromFolderNames() {
        assertThat(ModelCatalog.buildNumber(Path.of("llama/macos-arm64/llama-b11550/llama-server"))).isEqualTo(11550);
        assertThat(ModelCatalog.buildNumber(Path.of("llama/macos-arm64/llama-server"))).isEqualTo(-1);
    }
}
