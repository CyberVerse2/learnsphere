package edu.learnsphere.ai.runtime;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads what the computer offers for running models: platform, total memory and processor count.
 *
 * <p>Total memory comes from the JDK's operating-system bean when the {@code jdk.management} module is
 * present, otherwise from {@code sysctl hw.memsize} (macOS) or {@code /proc/meminfo} (Linux). When none of
 * these works it is reported as 0 (unknown), and model choice falls back to the smaller model.
 */
public final class HardwareProbe {

    private static final Logger LOG = LoggerFactory.getLogger(HardwareProbe.class);
    private static final Pattern MEMINFO_TOTAL = Pattern.compile("MemTotal:\\s+(\\d+)\\s+kB");

    /** One gibibyte in bytes. */
    public static final long GIB = 1024L * 1024 * 1024;

    /**
     * What the probe found.
     *
     * @param platform         the llama.cpp platform, or empty when none fits this computer
     * @param totalMemoryBytes physical memory, or 0 when unknown
     * @param processors       logical processors
     */
    public record Hardware(Optional<Platform> platform, long totalMemoryBytes, int processors) {

        public Hardware {
            platform = platform == null ? Optional.empty() : platform;
            if (totalMemoryBytes < 0) {
                throw new IllegalArgumentException("totalMemoryBytes must be >= 0: " + totalMemoryBytes);
            }
        }

        /** True when total memory is known. */
        public boolean memoryKnown() {
            return totalMemoryBytes > 0;
        }

        /** Total memory in GiB (0 when unknown). */
        public double totalMemoryGib() {
            return (double) totalMemoryBytes / GIB;
        }

        /** True when total memory is known and at least {@code gib} GiB (a 16 GB machine counts as 16). */
        public boolean hasAtLeastGib(int gib) {
            // Machines sold as "16 GB" report slightly less to some APIs; allow a 3% margin.
            return memoryKnown() && totalMemoryBytes >= (long) (gib * GIB * 0.97);
        }
    }

    private HardwareProbe() {
    }

    /** Probes this computer. Never throws. */
    public static Hardware probe() {
        return new Hardware(Platform.current(), totalMemory(), Runtime.getRuntime().availableProcessors());
    }

    static long totalMemory() {
        long fromBean = fromOperatingSystemBean();
        if (fromBean > 0) {
            return fromBean;
        }
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        if (os.contains("mac")) {
            return fromSysctl();
        }
        if (os.contains("linux")) {
            return fromMeminfo(Path.of("/proc/meminfo"));
        }
        return 0;
    }

    /** {@code com.sun.management.OperatingSystemMXBean#getTotalMemorySize}, looked up reflectively. */
    private static long fromOperatingSystemBean() {
        try {
            OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
            for (Class<?> type : bean.getClass().getInterfaces()) {
                if (type.getName().equals("com.sun.management.OperatingSystemMXBean")) {
                    Method method = type.getMethod("getTotalMemorySize");
                    Object value = method.invoke(bean);
                    return value instanceof Long size && size > 0 ? size : 0;
                }
            }
            Method method = bean.getClass().getMethod("getTotalMemorySize");
            method.setAccessible(true);
            Object value = method.invoke(bean);
            return value instanceof Long size && size > 0 ? size : 0;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOG.debug("The operating-system bean does not report total memory", e);
            return 0;
        }
    }

    private static long fromSysctl() {
        try {
            Process process = new ProcessBuilder(List.of("/usr/sbin/sysctl", "-n", "hw.memsize"))
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.US_ASCII).strip();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return 0;
            }
            return Long.parseLong(output);
        } catch (IOException | NumberFormatException e) {
            LOG.debug("sysctl hw.memsize failed", e);
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        }
    }

    static long fromMeminfo(Path meminfo) {
        try {
            Matcher m = MEMINFO_TOTAL.matcher(Files.readString(meminfo, StandardCharsets.US_ASCII));
            return m.find() ? Long.parseLong(m.group(1)) * 1024 : 0;
        } catch (IOException | NumberFormatException e) {
            LOG.debug("Reading {} failed", meminfo, e);
            return 0;
        }
    }
}
