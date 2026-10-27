package edu.learnsphere.testbench.probe;

import edu.learnsphere.ai.runtime.ServerRole;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Samples the memory of the llama-server processes while the testbench runs, on a background thread.
 *
 * <p>Two measures are taken on macOS: the resident set size ({@code ps -o rss}, every {@value #RSS_INTERVAL_MS}
 * ms) and the physical footprint ({@code footprint -p}, every {@value #FOOTPRINT_INTERVAL_MS} ms). The footprint
 * includes the GPU buffers that Metal allocates in unified memory (the KV cache, compute buffers), which the
 * resident size does not always show, so it is the better measure of what the servers take from the computer.
 * Peaks are kept per server role, for the servers together, and per phase. On other systems only the resident
 * size is sampled.
 *
 * <p>It also records how busy the computer was, since timings depend on it: the system load average, and other
 * llama-server processes (not started by this run) competing for the GPU.
 */
public final class MemorySampler implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MemorySampler.class);
    static final long RSS_INTERVAL_MS = 500;
    static final long FOOTPRINT_INTERVAL_MS = 2_000;
    private static final Pattern FOOTPRINT = Pattern.compile("Footprint:\\s*([\\d.]+)\\s*(B|KB|MB|GB)");

    /**
     * Peak memory in bytes.
     *
     * @param rssByRole       highest resident size of each role's process
     * @param footprintByRole highest physical footprint of each role's process (empty when not measured)
     * @param totalRss        highest sum of the resident sizes sampled at the same moment
     * @param totalFootprint  highest sum of the latest footprints of every role (0 when not measured)
     * @param footprintByPhase highest total footprint while each phase ran (resident size when footprint is not
     *                         measured)
     * @param samples         how many times the processes were sampled
     * @param activity        how busy the computer was meanwhile
     */
    public record Peaks(Map<ServerRole, Long> rssByRole, Map<ServerRole, Long> footprintByRole, long totalRss,
                        long totalFootprint, Map<String, Long> footprintByPhase, int samples, Activity activity) {

        public Peaks {
            rssByRole = Map.copyOf(rssByRole);
            footprintByRole = Map.copyOf(footprintByRole);
            footprintByPhase = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(footprintByPhase));
        }

        /** True when the physical footprint was measured. */
        public boolean hasFootprint() {
            return !footprintByRole.isEmpty();
        }
    }

    /**
     * How busy the computer was while the servers ran.
     *
     * @param meanLoad              mean one-minute load average (negative when the system does not report it)
     * @param maxLoad               highest one-minute load average
     * @param maxOtherServers       most llama-server processes from elsewhere running at the same time
     * @param shareWithOtherServers share of checks that found at least one llama-server from elsewhere
     */
    public record Activity(double meanLoad, double maxLoad, int maxOtherServers, double shareWithOtherServers) {

        /** No measurements. */
        public static final Activity NONE = new Activity(-1, -1, 0, 0);
    }

    private final Supplier<Map<ServerRole, Long>> processes;
    private final Supplier<String> phase;
    private final boolean footprintAvailable;
    private final Thread thread;
    private final Object lock = new Object();
    private final Map<ServerRole, Long> peakRss = new EnumMap<>(ServerRole.class);
    private final Map<ServerRole, Long> peakFootprint = new EnumMap<>(ServerRole.class);
    private final Map<ServerRole, Long> latestFootprint = new EnumMap<>(ServerRole.class);
    private final Map<String, Long> peakByPhase = new LinkedHashMap<>();
    private long peakTotalRss;
    private long peakTotalFootprint;
    private int samples;
    private double loadSum;
    private int loadSamples;
    private double maxLoad = -1;
    private int otherChecks;
    private int otherSeen;
    private int maxOther;
    private volatile boolean running = true;

    /**
     * Starts sampling.
     *
     * @param processes the process id of each running server role (sampled afresh every time)
     * @param phase     the phase running now
     */
    public MemorySampler(Supplier<Map<ServerRole, Long>> processes, Supplier<String> phase) {
        this.processes = Objects.requireNonNull(processes, "processes");
        this.phase = Objects.requireNonNull(phase, "phase");
        this.footprintAvailable = isMac() && java.nio.file.Files.isExecutable(java.nio.file.Path.of("/usr/bin/footprint"));
        this.thread = Thread.ofPlatform().daemon(true).name("testbench-memory").start(this::loop);
    }

    private void loop() {
        long lastFootprint = 0;
        while (running) {
            try {
                Map<ServerRole, Long> pids = processes.get();
                if (!pids.isEmpty()) {
                    boolean footprintDue = footprintAvailable
                            && System.currentTimeMillis() - lastFootprint >= FOOTPRINT_INTERVAL_MS;
                    sample(pids, footprintDue);
                    if (footprintDue) {
                        lastFootprint = System.currentTimeMillis();
                    }
                }
                Thread.sleep(RSS_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                LOG.debug("Memory sample failed", e);
            }
        }
    }

    private void sample(Map<ServerRole, Long> pids, boolean withFootprint) throws InterruptedException {
        Map<Long, Long> rss = residentSizes(pids.values().stream().toList());
        Map<ServerRole, Long> footprints = new EnumMap<>(ServerRole.class);
        if (withFootprint) {
            for (Map.Entry<ServerRole, Long> entry : pids.entrySet()) {
                footprint(entry.getValue()).ifPresent(bytes -> footprints.put(entry.getKey(), bytes));
            }
        }
        String current = phase.get();
        double load = java.lang.management.ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
        int others = withFootprint || !footprintAvailable ? otherServers(pids.values()) : -1;
        synchronized (lock) {
            samples++;
            if (load >= 0) {
                loadSum += load;
                loadSamples++;
                maxLoad = Math.max(maxLoad, load);
            }
            if (others >= 0) {
                otherChecks++;
                otherSeen += others > 0 ? 1 : 0;
                maxOther = Math.max(maxOther, others);
            }
            long total = 0;
            for (Map.Entry<ServerRole, Long> entry : pids.entrySet()) {
                Long bytes = rss.get(entry.getValue());
                if (bytes != null) {
                    total += bytes;
                    peakRss.merge(entry.getKey(), bytes, Math::max);
                }
            }
            peakTotalRss = Math.max(peakTotalRss, total);
            footprints.forEach((role, bytes) -> {
                latestFootprint.put(role, bytes);
                peakFootprint.merge(role, bytes, Math::max);
            });
            latestFootprint.keySet().retainAll(pids.keySet());
            long footprintTotal = latestFootprint.values().stream().mapToLong(Long::longValue).sum();
            peakTotalFootprint = Math.max(peakTotalFootprint, footprintTotal);
            long phaseValue = footprintAvailable ? footprintTotal : total;
            if (!current.isEmpty()) {
                peakByPhase.merge(current, phaseValue, Math::max);
            }
        }
    }

    /** The peaks so far. */
    public Peaks peaks() {
        synchronized (lock) {
            Activity activity = new Activity(loadSamples == 0 ? -1 : loadSum / loadSamples, maxLoad, maxOther,
                    otherChecks == 0 ? 0 : otherSeen / (double) otherChecks);
            return new Peaks(peakRss, peakFootprint, peakTotalRss, peakTotalFootprint, peakByPhase, samples, activity);
        }
    }

    /** Stops sampling and returns the peaks. */
    public Peaks finish() {
        close();
        return peaks();
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
        try {
            thread.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ measuring

    /** llama-server processes running that are not among {@code ours}. */
    static int otherServers(java.util.Collection<Long> ours) {
        return (int) ProcessHandle.allProcesses()
                .filter(p -> !ours.contains(p.pid()))
                .filter(p -> p.info().command().map(c -> c.endsWith("/llama-server") || c.equals("llama-server"))
                        .orElse(false))
                .count();
    }

    /** Resident size in bytes of each process that is still running. */
    static Map<Long, Long> residentSizes(List<Long> pids) throws InterruptedException {
        String list = pids.stream().map(String::valueOf).collect(Collectors.joining(","));
        String output = run(List.of("ps", "-o", "pid=,rss=", "-p", list));
        return parseResidentSizes(output);
    }

    /** Parses {@code ps -o pid=,rss=} output (sizes in KiB) into bytes by process id. */
    static Map<Long, Long> parseResidentSizes(String output) {
        Map<Long, Long> sizes = new LinkedHashMap<>();
        for (String line : output.split("\\R")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length == 2) {
                try {
                    sizes.put(Long.parseLong(parts[0]), Long.parseLong(parts[1]) * 1024);
                } catch (NumberFormatException ignored) {
                    // a header or a line from another tool
                }
            }
        }
        return sizes;
    }

    private static OptionalLong footprint(long pid) throws InterruptedException {
        return parseFootprint(run(List.of("/usr/bin/footprint", "-p", String.valueOf(pid))));
    }

    /** Parses the {@code Footprint: 2336 KB} header of {@code footprint} output into bytes. */
    static OptionalLong parseFootprint(String output) {
        Matcher matcher = FOOTPRINT.matcher(output);
        if (!matcher.find()) {
            return OptionalLong.empty();
        }
        double value = Double.parseDouble(matcher.group(1));
        long unit = switch (matcher.group(2)) {
            case "KB" -> 1024L;
            case "MB" -> 1024L * 1024;
            case "GB" -> 1024L * 1024 * 1024;
            default -> 1L;
        };
        return OptionalLong.of(Math.round(value * unit));
    }

    private static String run(List<String> command) throws InterruptedException {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            process.getOutputStream().close();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            return output;
        } catch (IOException e) {
            return "";
        }
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }
}
