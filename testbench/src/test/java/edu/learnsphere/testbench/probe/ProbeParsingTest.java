package edu.learnsphere.testbench.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class ProbeParsingTest {

    @Test
    void residentSizesAreReadInKibibytes() {
        Map<Long, Long> sizes = MemorySampler.parseResidentSizes("  4211 1048576\n 4212   2048\nPID RSS\n\n");
        assertThat(sizes).containsExactly(Map.entry(4211L, 1024L * 1024 * 1024), Map.entry(4212L, 2048L * 1024));
    }

    @Test
    void aCommandThatHangsIsEndedAtItsTimeLimit() throws Exception {
        long start = System.nanoTime();
        assertThat(MemorySampler.run(List.of("sleep", "30"), 1)).isEmpty();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(10_000);
        assertThat(MemorySampler.run(List.of("echo", "  4211 1048576"), 5)).contains("4211 1048576");
        assertThat(MemorySampler.run(List.of("/no/such/tool"), 5)).isEmpty();
    }

    @Test
    void footprintHeaderIsReadInItsUnit() {
        assertThat(MemorySampler.parseFootprint("zsh [74250]: 64-bit    Footprint: 2336 KB (16384 bytes per page)"))
                .hasValue(2336L * 1024);
        assertThat(MemorySampler.parseFootprint("llama-server [1]: 64-bit    Footprint: 3.5 GB (16384 bytes per page)"))
                .hasValue(Math.round(3.5 * 1024 * 1024 * 1024));
        assertThat(MemorySampler.parseFootprint("Footprint: 812 MB")).hasValue(812L * 1024 * 1024);
        assertThat(MemorySampler.parseFootprint("footprint: no process")).isEmpty();
    }

    @Test
    void serverLogBuffersComeFromTheLatestLaunchOnly() {
        List<String> log = List.of(
                "=== 2026-10-27T10:00:00Z launching: llama-server -m old.gguf",
                "load_tensors: Metal_Mapped model buffer size =  9999.00 MiB",
                "=== 2026-10-27T11:00:00Z launching: llama-server -m chat.gguf",
                "load_tensors:   CPU_Mapped model buffer size =   303.75 MiB",
                "load_tensors: Metal_Mapped model buffer size =  2368.53 MiB",
                "llama_kv_cache:      Metal KV buffer size =   512.00 MiB",
                "llama_memory_recurrent:      Metal RS buffer size =    25.13 MiB",
                "llama_context:      Metal compute buffer size =   300.75 MiB",
                "llama_context:        CPU  output buffer size =     0.58 MiB",
                "main: server is listening on http://127.0.0.1:50000");
        ServerLogs.Buffers buffers = ServerLogs.parse(log);
        assertThat(buffers.byKind().get("model")).isCloseTo(2672.28, within(1e-6));
        assertThat(buffers.byKind().get("KV")).isEqualTo(512.0);
        assertThat(buffers.byKind().get("RS")).isEqualTo(25.13);
        assertThat(buffers.byKind().get("compute")).isEqualTo(300.75);
        assertThat(buffers.byDevice()).containsKeys("Metal_Mapped model", "CPU_Mapped model", "Metal KV");
        assertThat(buffers.totalMib()).isCloseTo(2672.28 + 512 + 25.13 + 300.75 + 0.58, within(1e-6));
        assertThat(ServerLogs.parse(List.of("nothing here")).isEmpty()).isTrue();
    }

    @Test
    void chatLogCollectsTheClientStatisticsByPhase() {
        String[] phase = {"answers"};
        try (ChatLog log = ChatLog.install(() -> phase[0])) {
            Logger chat = LoggerFactory.getLogger("edu.learnsphere.ai.client.LocalChat");
            Logger structured = LoggerFactory.getLogger("edu.learnsphere.ai.client.StructuredChat");
            Logger generator = LoggerFactory.getLogger("edu.learnsphere.ai.client.LlamaItemGenerator");
            chat.debug("Chat {} done: {} prompt + {} generated tokens, first token {} ms, {} ms in all ({})",
                    "INTERACTIVE", 2400L, 181L, 900L, 4500L, "stop");
            phase[0] = "generation";
            structured.warn("The {} reply was not complete JSON ({}, {} tokens); {}", "items", "length", 1200L, "trying again");
            generator.debug("Dropped a {} draft: {} ({})", "mcq", "distractor repeats the answer: Paging", "Which…?");
            chat.debug("Chat {} done: {} prompt + {} generated tokens, first token {} ms, {} ms in all ({})",
                    "USER_WAITING", 3000L, 1200L, 1000L, 21000L, "length");

            assertThat(log.completions("answers")).singleElement().satisfies(c -> {
                assertThat(c.priority()).isEqualTo("INTERACTIVE");
                assertThat(c.promptTokens()).isEqualTo(2400);
                assertThat(c.completionTokens()).isEqualTo(181);
                assertThat(c.generationRate()).isCloseTo(180 * 1000.0 / 3600, within(1e-9));
                assertThat(c.promptRate()).isCloseTo(2400 * 1000.0 / 900, within(1e-9));
            });
            assertThat(log.completions("generation")).hasSize(1);
            assertThat(log.invalidJson("generation")).singleElement()
                    .isEqualTo(new ChatLog.InvalidJson("generation", "items", "length", true));
            assertThat(log.droppedDrafts()).singleElement()
                    .satisfies(d -> assertThat(d.reason()).startsWith("distractor repeats the answer"));
        }
        ch.qos.logback.classic.Logger chat = (ch.qos.logback.classic.Logger) LoggerFactory
                .getLogger("edu.learnsphere.ai.client.LocalChat");
        assertThat(chat.getLevel()).isNull();
        assertThat(chat.iteratorForAppenders().hasNext()).isFalse();
    }
}
