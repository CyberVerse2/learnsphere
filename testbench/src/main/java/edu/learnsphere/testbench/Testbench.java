package edu.learnsphere.testbench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Entry point of the evaluation harness. Usage: {@code Testbench <report.md>}; the runtime folder comes
 * from the {@code learnsphere.runtime} system property.
 *
 * <p>Evaluation suites register in {@link #SUITES}; each writes one section of the report.
 */
public final class Testbench {

    /** One evaluation suite: appends a Markdown section to the report. */
    @FunctionalInterface
    public interface Suite {
        void run(Path runtime, StringBuilder report) throws Exception;
    }

    static final List<Suite> SUITES = List.of();

    private Testbench() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println("Usage: Testbench <report.md>");
            System.exit(2);
        }
        Path report = Path.of(args[0]);
        String runtimeProperty = System.getProperty("learnsphere.runtime", "");
        Path runtime = runtimeProperty.isBlank() ? null : Path.of(runtimeProperty);

        StringBuilder out = new StringBuilder()
                .append("# LearnSphere model evaluation\n\n")
                .append("- Run: ").append(ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)).append('\n')
                .append("- Runtime folder: ").append(runtime == null ? "not set" : runtime)
                .append(runtime != null && Files.isDirectory(runtime) ? "" : " (missing)").append("\n\n");
        if (SUITES.isEmpty()) {
            out.append("No evaluation suites are registered.\n");
        }
        for (Suite suite : SUITES) {
            suite.run(runtime, out);
        }
        write(report, out.toString());
        System.out.println("Report written to " + report);
    }

    private static void write(Path report, String content) throws IOException {
        Path parent = report.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(report, content, StandardCharsets.UTF_8);
    }
}
