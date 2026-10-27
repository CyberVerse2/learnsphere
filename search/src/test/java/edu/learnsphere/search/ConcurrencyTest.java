package edu.learnsphere.search;

import static edu.learnsphere.search.Passages.DIMENSIONS;
import static edu.learnsphere.search.Passages.MODEL;
import static edu.learnsphere.search.Passages.passage;
import static edu.learnsphere.search.Passages.query;
import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchQuery;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 2, unit = TimeUnit.MINUTES)
class ConcurrencyTest {

    private static final String[] WORDS = {"osmosis", "membrane", "protein", "enzyme", "nucleus", "ribosome", "glucose"};

    @TempDir
    Path dir;

    private LuceneSearchIndex index;
    private ExecutorService pool;
    private final Queue<Throwable> errors = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void open() {
        index = LuceneSearchIndex.open(dir.resolve("index"), DIMENSIONS, MODEL);
        index.rebuild(Stream.empty());
        pool = Executors.newFixedThreadPool(6);
    }

    @AfterEach
    void close() throws InterruptedException {
        pool.shutdownNow();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        index.close();
    }

    /** Waits until {@code searches} counted one, failing on a search error or after a minute. */
    private void awaitSearches(AtomicInteger searches, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(1);
        while (searches.get() == 0) {
            assertThat(errors).isEmpty();
            assertThat(System.nanoTime()).as("searches of " + what + " within a minute").isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    private static IndexedChunk numbered(String course, int i) {
        return passage(course, "doc-" + course + i % 5, null,
                WORDS[i % WORDS.length] + " and " + WORDS[(i * 3) % WORDS.length] + " in passage " + i + " of " + course);
    }

    @Test
    void searchesRunWhileTwoWritersIndexAndCommit() throws Exception {
        AtomicBoolean writing = new AtomicBoolean(true);
        AtomicInteger searches = new AtomicInteger();
        List<Future<?>> writers = new ArrayList<>();
        for (String course : List.of("a", "b")) {
            writers.add(pool.submit(() -> {
                for (int batch = 0; batch < 20; batch++) {
                    List<IndexedChunk> passages = new ArrayList<>();
                    for (int i = 0; i < 25; i++) {
                        passages.add(numbered(course, batch * 25 + i));
                    }
                    index.upsert(passages);
                    index.commit();
                }
                return null;
            }));
        }
        List<Future<?>> searchers = new ArrayList<>();
        for (int s = 0; s < 4; s++) {
            int seed = s;
            searchers.add(pool.submit(() -> {
                long lastSize = 0;
                int i = seed;
                while (writing.get()) {
                    try {
                        List<SearchHit> hits = index.search(query("a", WORDS[i++ % WORDS.length]));
                        if (hits.stream().anyMatch(h -> !h.courseId().equals("a"))) {
                            errors.add(new AssertionError("Hit from another course: " + hits));
                        }
                        long size = index.size();
                        if (size < lastSize) {
                            errors.add(new AssertionError("Size went back from " + lastSize + " to " + size));
                        }
                        lastSize = size;
                        searches.incrementAndGet();
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }
            }));
        }

        try {
            for (Future<?> writer : writers) {
                writer.get(90, TimeUnit.SECONDS);
            }
        } finally {
            writing.set(false);
        }
        for (Future<?> searcher : searchers) {
            searcher.get(30, TimeUnit.SECONDS);
        }

        assertThat(errors).isEmpty();
        assertThat(searches.get()).isPositive();
        assertThat(index.size()).isEqualTo(1000);
        // 72 passages of "b" mention osmosis; keyword search returns its 40 best. Exact counts come only from
        // keyword search: the fake query vector for one word is nearly one-hot, most passages tie with it, and
        // which of the tied passages approximate nearest-neighbour search returns depends on the segment layout
        // the two writers happened to produce.
        assertThat(index.search(new SearchQuery("osmosis", null, "b", Set.of(), 40, 0, 100, 60))).hasSize(40)
                .allSatisfy(h -> {
                    assertThat(h.courseId()).isEqualTo("b");
                    assertThat(h.text()).contains("osmosis");
                });
        assertThat(index.search(query("b", "osmosis").withLimit(100))).hasSizeBetween(40, 80)
                .allSatisfy(h -> assertThat(h.courseId()).isEqualTo("b"));
    }

    @Test
    void searchesSeeEitherTheOldOrTheNewContentsDuringARebuild() throws Exception {
        index.rebuild(IntStream.range(0, 300).mapToObj(i -> passage("a", "old", null, "alpha osmosis " + i)));
        AtomicBoolean rebuilding = new AtomicBoolean(true);
        List<Future<?>> searchers = new ArrayList<>();
        AtomicInteger sawOld = new AtomicInteger();
        AtomicInteger sawNew = new AtomicInteger();
        for (int s = 0; s < 4; s++) {
            searchers.add(pool.submit(() -> {
                while (rebuilding.get()) {
                    try {
                        List<SearchHit> hits = index.search(query("a", "osmosis alpha omega"));
                        long old = hits.stream().filter(h -> h.text().startsWith("alpha")).count();
                        long fresh = hits.stream().filter(h -> h.text().startsWith("omega")).count();
                        if (hits.isEmpty() || (old > 0 && fresh > 0)) {
                            errors.add(new AssertionError("Mixed or empty results: " + hits));
                        }
                        (old > 0 ? sawOld : sawNew).incrementAndGet();
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }
            }));
        }

        try {
            // Waits for what the searches saw rather than for a fixed time, so a busy machine cannot skip a phase.
            awaitSearches(sawOld, "the old contents");
            index.rebuild(IntStream.range(0, 2_000).mapToObj(i -> passage("a", "new", null, "omega osmosis " + i)));
            awaitSearches(sawNew, "the new contents");
        } finally {
            rebuilding.set(false);
        }
        for (Future<?> searcher : searchers) {
            searcher.get(30, TimeUnit.SECONDS);
        }

        assertThat(errors).isEmpty();
        assertThat(sawOld.get()).isPositive();
        assertThat(sawNew.get()).isPositive();
        assertThat(index.size()).isEqualTo(2_000);
    }
}
