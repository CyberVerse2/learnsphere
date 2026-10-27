package edu.learnsphere.ai.runtime;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
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
 * present, otherwise from {@code sysctl hw.memsize} (macOS), {@code /proc/meminfo} (Linux) or
 * {@code GlobalMemoryStatusEx} in kernel32 through the foreign function API (Windows; needs native access,
 * which the app enables). When none of these works it is reported as 0 (unknown), and only the smaller
 * model is offered.
 */
public final class HardwareProbe {

    private static final Logger LOG = LoggerFactory.getLogger(HardwareProbe.class);
    private static final Pattern MEMINFO_TOTAL = Pattern.compile("MemTotal:\\s+(\\d+)\\s+kB");

    /** {@code MEMORYSTATUSEX} from the Windows API; {@code ullTotalPhys} is physical memory in bytes. */
    private static final StructLayout MEMORY_STATUS = MemoryLayout.structLayout(
            ValueLayout.JAVA_INT.withName("dwLength"),
            ValueLayout.JAVA_INT.withName("dwMemoryLoad"),
            ValueLayout.JAVA_LONG.withName("ullTotalPhys"),
            ValueLayout.JAVA_LONG.withName("ullAvailPhys"),
            ValueLayout.JAVA_LONG.withName("ullTotalPageFile"),
            ValueLayout.JAVA_LONG.withName("ullAvailPageFile"),
            ValueLayout.JAVA_LONG.withName("ullTotalVirtual"),
            ValueLayout.JAVA_LONG.withName("ullAvailVirtual"),
            ValueLayout.JAVA_LONG.withName("ullAvailExtendedVirtual"));

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
        if (os.contains("windows")) {
            return fromGlobalMemoryStatus();
        }
        return 0;
    }

    /** {@code BOOL GlobalMemoryStatusEx(LPMEMORYSTATUSEX)} in kernel32, called through the foreign function API. */
    @SuppressWarnings("restricted")
    static long fromGlobalMemoryStatus() {
        try (Arena arena = Arena.ofConfined()) {
            Linker linker = Linker.nativeLinker();
            MemorySegment function = SymbolLookup.libraryLookup("kernel32", arena)
                    .find("GlobalMemoryStatusEx")
                    .orElse(null);
            if (function == null) {
                return 0;
            }
            MethodHandle globalMemoryStatusEx = linker.downcallHandle(function,
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MemorySegment status = arena.allocate(MEMORY_STATUS);
            status.set(ValueLayout.JAVA_INT, MEMORY_STATUS.byteOffset(MemoryLayout.PathElement.groupElement("dwLength")),
                    (int) MEMORY_STATUS.byteSize());
            int ok = (int) globalMemoryStatusEx.invokeExact(status);
            if (ok == 0) {
                return 0;
            }
            long total = status.get(ValueLayout.JAVA_LONG,
                    MEMORY_STATUS.byteOffset(MemoryLayout.PathElement.groupElement("ullTotalPhys")));
            return Math.max(total, 0);
        } catch (VirtualMachineError e) {
            throw e;
        } catch (Throwable e) {
            // Not Windows, native access disabled, or the call unavailable: memory stays unknown.
            LOG.debug("GlobalMemoryStatusEx failed", e);
            return 0;
        }
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
