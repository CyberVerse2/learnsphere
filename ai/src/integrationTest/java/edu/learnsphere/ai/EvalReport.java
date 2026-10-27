package edu.learnsphere.ai;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A Markdown section of the model evaluation, written to {@code build/reports/ai-eval/<name>.md} (and
 * echoed to standard output) so prompt changes can be compared run to run.
 */
public final class EvalReport {

    private final String name;
    private final List<String> lines = new ArrayList<>();

    public EvalReport(String name, String title) {
        this.name = name;
        lines.add("# " + title);
        lines.add("");
    }

    public synchronized EvalReport line(String text) {
        lines.add(text);
        return this;
    }

    public synchronized EvalReport line(String format, Object... args) {
        lines.add(String.format(Locale.ROOT, format, args));
        return this;
    }

    public static String pct(double fraction) {
        return String.format(Locale.ROOT, "%.0f%%", fraction * 100);
    }

    public static String ms(double millis) {
        return String.format(Locale.ROOT, "%.0f ms", millis);
    }

    public static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    public static double mean(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    /** Writes the section file and prints it. */
    public synchronized void write() {
        String text = String.join("\n", lines) + "\n";
        Path folder = Path.of(System.getProperty("user.dir")).resolve("build").resolve("reports").resolve("ai-eval");
        try {
            Files.createDirectories(folder);
            Files.writeString(folder.resolve(name + ".md"), text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        System.out.println(text);
    }
}
