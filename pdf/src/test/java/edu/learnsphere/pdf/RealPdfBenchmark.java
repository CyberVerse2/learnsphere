package edu.learnsphere.pdf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import edu.learnsphere.core.spi.ChunkDraft;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.PdfInspection;
import edu.learnsphere.core.util.CancellationToken;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Measures inspection, extraction and chunking of real PDFs, which cost more per page than the generated book
 * of {@link LargeDocumentTest}: many fonts, kerned text and drawings. Runs only when
 * {@code LEARNSPHERE_PDF_BENCHMARK} lists PDF files (separated by the platform's path separator, ':' on macOS
 * and Linux):
 *
 * <pre>{@code
 * LEARNSPHERE_PDF_BENCHMARK="/path/to/a.pdf:/path/to/b.pdf" \
 *     ./gradlew :pdf:test --tests edu.learnsphere.pdf.RealPdfBenchmark --rerun
 * }</pre>
 *
 * <p>{@code LEARNSPHERE_PDF_BENCHMARK_PAGES} limits how many pages of each file are extracted (a few more are
 * read, to compare running headers with the following pages). Each file is extracted twice: the first run
 * includes loading its fonts and, for the first file, warming up the JVM; the second shows the steady cost.
 * Times are wall-clock and the calling thread's CPU time, and go to the test output
 * ({@code build/test-results/test/TEST-edu.learnsphere.pdf.RealPdfBenchmark.xml}) together with the first
 * section titles found, for a quick look at heading detection.
 */
@EnabledIfEnvironmentVariable(named = "LEARNSPHERE_PDF_BENCHMARK", matches = ".+")
class RealPdfBenchmark {

    @Test
    void measuresRealFiles() throws Exception {
        List<Path> files = Arrays.stream(Objects.requireNonNull(System.getenv("LEARNSPHERE_PDF_BENCHMARK"))
                .split(File.pathSeparator)).map(String::strip).filter(s -> !s.isEmpty()).map(Path::of).toList();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        for (Path pdf : files) {
            long cpu0 = threads.getCurrentThreadCpuTime();
            PdfInspection inspection = new PdfBoxInspector().inspect(pdf);
            long cpu1 = threads.getCurrentThreadCpuTime();
            int pageCount = inspection.pageCount();
            System.out.printf(Locale.ROOT, "%s: %d pages, %.1f MB, inspect %.1f ms/page cpu%n", pdf.getFileName(), pageCount,
                    Files.size(pdf) / 1e6, (cpu1 - cpu0) / 1e6 / pageCount);
            int limit = Math.min(pageCount, maxPages());
            List<PageText> pages = List.of();
            for (String run : List.of("first", "second")) {
                long wall = System.nanoTime();
                long cpu = threads.getCurrentThreadCpuTime();
                pages = extract(pdf, limit);
                long cpuDone = threads.getCurrentThreadCpuTime();
                long wallDone = System.nanoTime();
                System.out.printf(Locale.ROOT, "  extract %d pages, %s run: %d ms wall, %d ms cpu (%.1f ms/page cpu)%n",
                        pages.size(), run, (wallDone - wall) / 1_000_000, (cpuDone - cpu) / 1_000_000,
                        (cpuDone - cpu) / 1e6 / pages.size());
            }
            assertEquals(limit, pages.size());
            long cpu = threads.getCurrentThreadCpuTime();
            List<ChunkDraft> chunks = new SectionChunker().chunk(pages);
            long cpuDone = threads.getCurrentThreadCpuTime();
            List<String> sections = chunks.stream().map(ChunkDraft::sectionTitle).filter(Objects::nonNull).distinct().toList();
            System.out.printf(Locale.ROOT, "  chunk: %d ms cpu, %d passages, %d sections; first: %s%n", (cpuDone - cpu) / 1_000_000,
                    chunks.size(), sections.size(), sections.subList(0, Math.min(12, sections.size())));
        }
    }

    private static int maxPages() {
        String limit = System.getenv("LEARNSPHERE_PDF_BENCHMARK_PAGES");
        return limit == null || limit.isBlank() ? Integer.MAX_VALUE : Integer.parseInt(limit.strip());
    }

    /** The first {@code limit} pages of {@code pdf}. */
    private static List<PageText> extract(Path pdf, int limit) throws Exception {
        List<PageText> pages = new ArrayList<>();
        CancellationToken stop = CancellationToken.create();
        try {
            new PdfBoxTextExtractor().extract(pdf, page -> {
                if (pages.size() < limit) {
                    pages.add(page);
                }
                if (pages.size() >= limit) {
                    stop.cancel();
                }
            }, stop);
        } catch (CancellationException enough) {
            // the limit was reached
        }
        return pages;
    }
}
