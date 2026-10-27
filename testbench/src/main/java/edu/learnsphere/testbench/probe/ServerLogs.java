package edu.learnsphere.testbench.probe;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the memory llama.cpp reports allocating from a server's log: lines such as
 * {@code load_tensors: Metal_Mapped model buffer size = 2368.53 MiB} or
 * {@code llama_kv_cache: Metal KV buffer size = 512.00 MiB}. Only the latest launch in the log is read (the app
 * starts each launch with a {@code === … launching:} line).
 */
public final class ServerLogs {

    private static final Pattern BUFFER = Pattern.compile(
            "(?:^|\\s)(\\S+)\\s+(model|KV|RS|compute|output)\\s+buffer size\\s*=\\s*([\\d.]+)\\s*MiB");
    private static final String LAUNCH = "=== ";

    /**
     * Buffer sizes in MiB.
     *
     * @param byKind   summed over devices: {@code model}, {@code KV}, {@code RS}, {@code compute}, {@code output}
     * @param byDevice each device and kind as logged ({@code Metal_Mapped model}, {@code CPU KV}, …)
     */
    public record Buffers(Map<String, Double> byKind, Map<String, Double> byDevice) {

        public Buffers {
            byKind = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byKind));
            byDevice = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(byDevice));
        }

        /** Total of every buffer. */
        public double totalMib() {
            return byKind.values().stream().mapToDouble(Double::doubleValue).sum();
        }

        /** True when the log reported no buffers. */
        public boolean isEmpty() {
            return byKind.isEmpty();
        }
    }

    private ServerLogs() {
    }

    /** The buffers of the latest launch in {@code log}; empty when the file is missing or reports none. */
    public static Buffers read(Path log) {
        try {
            return parse(Files.readAllLines(log, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new Buffers(Map.of(), Map.of());
        }
    }

    /** The buffers of the latest launch in these log lines. */
    static Buffers parse(List<String> lines) {
        int from = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(LAUNCH)) {
                from = i;
            }
        }
        Map<String, Double> byKind = new LinkedHashMap<>();
        Map<String, Double> byDevice = new LinkedHashMap<>();
        for (String line : lines.subList(from, lines.size())) {
            Matcher matcher = BUFFER.matcher(line);
            if (matcher.find()) {
                double mib = Double.parseDouble(matcher.group(3));
                byKind.merge(matcher.group(2), mib, Double::sum);
                byDevice.merge(matcher.group(1) + " " + matcher.group(2), mib, Double::sum);
            }
        }
        return new Buffers(byKind, byDevice);
    }
}
