package edu.learnsphere.search;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.ai.fake.FakeEmbedder;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchQuery;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Indexing and search latency over 50,000 passages of about 250 words with 1024-dimension vectors (the size of
 * the bundled embedding model's), spread over courses of very different sizes. Run with
 * {@code ./gradlew :search:benchmark}; prints a Markdown summary and saves it where the system property
 * {@code learnsphere.benchmark.report} points.
 */
@Tag("benchmark")
class SearchBenchmark {

    private static final int PASSAGES = 50_000;
    private static final int DIMENSIONS = 1024;
    private static final int VOCABULARY = 12_000;
    private static final int WARM_UP = 200;
    private static final int MEASURED = 500;
    private static final String[] SYLLABLES = {
        "ka", "lo", "mi", "ne", "ru", "ta", "vi", "so", "pe", "du", "ga", "ri", "zo", "fe", "hu", "ba", "ke", "mo",
        "ni", "pa", "se", "to", "ul", "an", "or", "es", "il", "um", "ex", "tri",
    };

    @TempDir
    Path dir;

    private final FakeEmbedder embedder = new FakeEmbedder(DIMENSIONS);
    private final String[] words = vocabulary();
    private final double[] zipf = zipfCumulative(VOCABULARY);
    private final StringBuilder report = new StringBuilder();

    /** 25,000 passages in one big course, 24,750 over nine mid-size courses, 250 in a small one. */
    private static String course(int i) {
        if (i < 25_000) {
            return "big";
        }
        if (i < 49_750) {
            return "mid-" + i % 9;
        }
        return "small";
    }

    private IndexedChunk passage(int i) {
        Random random = new Random(1_000_003L * i);
        int length = 200 + random.nextInt(100);
        StringBuilder text = new StringBuilder(length * 8);
        for (int w = 0; w < length; w++) {
            text.append(words[zipfWord(random)]).append(w % 17 == 16 ? ". " : " ");
        }
        String body = text.toString().strip();
        return new IndexedChunk("chunk-" + i, "doc-" + i / 40, course(i), "module-" + i / 400, "Document " + i / 40,
                i % 300 + 1, i % 300 + 2, "Section " + words[i % 500], body, embedder.vector(body));
    }

    private SearchQuery question(Random random, String course) {
        int length = 6 + random.nextInt(8);
        StringBuilder text = new StringBuilder("What is");
        for (int w = 0; w < length; w++) {
            // Mostly mid-frequency words, the way questions name specific ideas.
            text.append(' ').append(words[50 + Math.min(VOCABULARY - 51, zipfWord(random))]);
        }
        String q = text.append('?').toString();
        return SearchQuery.forCourse(course, q, embedder.vector(q));
    }

    @Test
    void fiftyThousandPassages() throws Exception {
        Path folder = dir.resolve("index");
        line("# Search benchmark: %,d passages, %d-dimension vectors", PASSAGES, DIMENSIONS);
        line("");
        line("Courses: one of 25,000 passages, nine of 2,750, one of 250. Passages of 200 to 300 words.");
        line("Java %s, %d processors available, max heap %d MB.", Runtime.version(),
                Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().maxMemory() >> 20);
        line("");

        long generateStart = System.nanoTime();
        long generated = IntStream.range(0, 2_000).mapToObj(this::passage).count();
        double generateMsPerPassage = millis(generateStart) / generated;

        long rebuildStart = System.nanoTime();
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder, DIMENSIONS, embedder.modelName())) {
            index.rebuild(IntStream.range(0, PASSAGES).mapToObj(this::passage));
            assertThat(index.size()).isEqualTo(PASSAGES);
        }
        double rebuildMs = millis(rebuildStart) - generateMsPerPassage * PASSAGES;
        long bytesOnDisk = folderSize(folder);

        long openStart = System.nanoTime();
        LuceneSearchIndex index = LuceneSearchIndex.open(folder, DIMENSIONS, embedder.modelName());
        double openMs = millis(openStart);
        try (index) {
            assertThat(index.needsRebuild()).isFalse();
            line("| Step | Time |");
            line("|---|---|");
            line("| Rebuild %,d passages (index work only, close included) | %,.1f s (%,.0f passages/s) |", PASSAGES,
                    rebuildMs / 1000, PASSAGES / (rebuildMs / 1000));
            line("| Open the existing index (checksums of every file verified) | %,.0f ms |", openMs);
            line("| Index size on disk | %,d MB |", bytesOnDisk >> 20);
            line("");

            line("Hybrid search, default query (keyword top 40 + vector top 40, fused, 24 returned), single thread, "
                    + "%d warm-up then %d measured queries:", WARM_UP, MEASURED);
            line("");
            line("| Course searched | p50 | p95 | p99 | max |");
            line("|---|---|---|---|---|");
            latency(index, "big", "25,000 passages");
            latency(index, "mid-3", "2,750 passages");
            latency(index, "small", "250 passages");
            line("");

            concurrent(index);
            incremental(index);
        }
        print();
    }

    private void latency(LuceneSearchIndex index, String course, String label) {
        Random random = new Random(course.hashCode());
        for (int i = 0; i < WARM_UP; i++) {
            index.search(question(random, course));
        }
        long[] nanos = new long[MEASURED];
        int hits = 0;
        for (int i = 0; i < MEASURED; i++) {
            SearchQuery q = question(random, course);
            long start = System.nanoTime();
            List<SearchHit> result = index.search(q);
            nanos[i] = System.nanoTime() - start;
            hits += result.size();
            for (SearchHit hit : result) {
                assertThat(hit.courseId()).isEqualTo(course);
            }
        }
        Arrays.sort(nanos);
        line("| %s (%s) | %s | %s | %s | %s |", course, label, ms(percentile(nanos, 0.50)), ms(percentile(nanos, 0.95)),
                ms(percentile(nanos, 0.99)), ms(nanos[nanos.length - 1]));
        assertThat(hits).isPositive();
    }

    private void concurrent(LuceneSearchIndex index) throws Exception {
        int threads = 4;
        int perThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            long start = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int seed = t;
                futures.add(pool.submit(() -> {
                    Random random = new Random(seed);
                    for (int i = 0; i < perThread; i++) {
                        index.search(question(random, i % 2 == 0 ? "big" : "mid-" + i % 9));
                    }
                }));
            }
            for (Future<?> f : futures) {
                f.get(5, TimeUnit.MINUTES);
            }
            double seconds = millis(start) / 1000;
            line("Concurrent: %d threads, %,d searches in %.2f s = %,.0f searches/s.", threads, threads * perThread,
                    seconds, threads * perThread / seconds);
            line("");
        } finally {
            pool.shutdownNow();
        }
    }

    private void incremental(LuceneSearchIndex index) {
        List<IndexedChunk> document = IntStream.range(PASSAGES, PASSAGES + 40).mapToObj(this::passage).toList();
        long upsertStart = System.nanoTime();
        index.upsert(document);
        index.commit();
        double upsertMs = millis(upsertStart);
        long deleteStart = System.nanoTime();
        index.deleteByDocument(document.getFirst().documentId());
        index.commit();
        double deleteMs = millis(deleteStart);
        line("| Import-time step | Time |");
        line("|---|---|");
        line("| Upsert one 40-passage document + commit + refresh | %,.0f ms |", upsertMs);
        line("| Delete that document + commit + refresh | %,.0f ms |", deleteMs);
        assertThat(index.size()).isEqualTo(PASSAGES);
    }

    // -----------------------------------------------------------------------------------------------------

    private int zipfWord(Random random) {
        int found = Arrays.binarySearch(zipf, random.nextDouble());
        return Math.min(VOCABULARY - 1, found >= 0 ? found : -found - 1);
    }

    private static double[] zipfCumulative(int n) {
        double[] cumulative = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            sum += 1.0 / Math.pow(i + 1, 1.05);
            cumulative[i] = sum;
        }
        for (int i = 0; i < n; i++) {
            cumulative[i] /= sum;
        }
        return cumulative;
    }

    private static String[] vocabulary() {
        String[] words = new String[VOCABULARY];
        Random random = new Random(7);
        for (int i = 0; i < VOCABULARY; i++) {
            StringBuilder word = new StringBuilder();
            int syllables = 2 + random.nextInt(3);
            for (int s = 0; s < syllables; s++) {
                word.append(SYLLABLES[random.nextInt(SYLLABLES.length)]);
            }
            words[i] = word.append(i % 10 == 0 ? "" : Integer.toString(i, 36).substring(0, 1)).toString();
        }
        return words;
    }

    private static long percentile(long[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p * sorted.length) - 1)];
    }

    private static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.2f ms", nanos / 1e6);
    }

    private static double millis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1e6;
    }

    private static long folderSize(Path folder) throws IOException {
        try (Stream<Path> files = Files.list(folder)) {
            return files.filter(Files::isRegularFile).mapToLong(p -> {
                try {
                    return Files.size(p);
                } catch (IOException e) {
                    return 0;
                }
            }).sum();
        }
    }

    private void line(String format, Object... args) {
        report.append(String.format(Locale.ROOT, format, args)).append('\n');
    }

    private void print() throws IOException {
        System.out.println(report);
        String target = System.getProperty("learnsphere.benchmark.report");
        if (target != null) {
            Path file = Path.of(target);
            Files.createDirectories(file.getParent());
            Files.writeString(file, report);
        }
    }
}
