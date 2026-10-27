package edu.learnsphere.search;

import static edu.learnsphere.search.Passages.DIMENSIONS;
import static edu.learnsphere.search.Passages.EMBEDDER;
import static edu.learnsphere.search.Passages.MODEL;
import static edu.learnsphere.search.Passages.ids;
import static edu.learnsphere.search.Passages.passage;
import static edu.learnsphere.search.Passages.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchQuery;
import edu.learnsphere.core.testing.TestDb;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Rebuilding from the chunks table, the way the workspace's rebuild job does, and rebuild failure handling. */
class RebuildTest {

    private static final String[] BIOLOGY = {
        "Cells are the basic unit of life and every organism is made of one or more cells.",
        "The cell membrane controls what enters and leaves the cell through selective permeability.",
        "Mitochondria release energy from glucose in a process called cellular respiration.",
        "Ribosomes read messenger RNA and assemble amino acids into proteins.",
        "Osmosis is the movement of water across a membrane from low to high solute concentration.",
        "Plant cells have a rigid cell wall made of cellulose that keeps their shape.",
        "Chloroplasts capture light energy and convert it into chemical energy during photosynthesis.",
        "The nucleus stores DNA and controls the activities of the cell.",
        "Enzymes are proteins that speed up chemical reactions without being used up.",
        "Diffusion spreads particles from regions of high to low concentration.",
    };
    private static final String[] HISTORY = {
        "The printing press spread ideas quickly across Renaissance Europe.",
        "Trade routes connected cities and carried goods, ideas and diseases.",
        "The industrial revolution moved work from farms into factories.",
        "Steam engines powered factories, ships and railways.",
        "Cells of monks copied manuscripts by hand before printing.",
    };

    @TempDir
    Path dir;

    private TestDb t;
    private Course biology;
    private Course history;
    private final List<LuceneSearchIndex> opened = new ArrayList<>();

    @BeforeEach
    void fillDatabase() {
        t = TestDb.open(dir.resolve("db"));
        biology = t.course("Biology");
        history = t.course("History");
        CourseModule cells = t.module(biology, "Cells");
        CourseModule energy = t.module(biology, "Energy");
        readyDocument(biology, cells, "Cell structure", BIOLOGY, 0, 6);
        readyDocument(biology, energy, "Cell energy", BIOLOGY, 6, 10);
        readyDocument(history, null, "Early modern Europe", HISTORY, 0, HISTORY.length);
        // Not ready yet, and embedded by another embedder: neither belongs in the index.
        Document importing = t.document(biology, cells, "Still importing");
        embed(List.of(t.chunk(importing, 0, "Osmosis in a document that is still importing.")), MODEL);
        Document stale = t.document(history, null, "Old vectors");
        embed(List.of(t.chunk(stale, 0, "Steam engines with vectors from an older embedder.")), "older-embedder");
        t.repos.documents().markReady(stale.id());
    }

    @AfterEach
    void close() {
        opened.forEach(LuceneSearchIndex::close);
        t.close();
    }

    private void readyDocument(Course course, CourseModule module, String title, String[] texts, int from, int to) {
        t.clock.advance(Duration.ofMinutes(1));
        Document document = t.document(course, module, title);
        List<Chunk> chunks = new ArrayList<>();
        for (int i = from; i < to; i++) {
            chunks.add(t.chunk(document, i - from, texts[i]));
        }
        embed(chunks, MODEL);
        t.repos.documents().markReady(document.id());
    }

    private void embed(List<Chunk> chunks, String model) {
        Map<String, float[]> vectors = new HashMap<>();
        for (Chunk chunk : chunks) {
            vectors.put(chunk.id(), EMBEDDER.vector(chunk.text()));
        }
        t.repos.chunks().updateEmbeddings(vectors, model);
    }

    private LuceneSearchIndex open(String name) {
        LuceneSearchIndex index = LuceneSearchIndex.open(dir.resolve(name), DIMENSIONS, MODEL);
        opened.add(index);
        return index;
    }

    /** What the workspace's REBUILD_INDEX job does. */
    private void rebuildFromDatabase(LuceneSearchIndex index) {
        t.repos.chunks().withIndexable(index.embeddingModel(), rows -> {
            index.rebuild(rows.map(Passages::fromRow));
            return null;
        });
    }

    /** The indexable passages in the order the rebuild reads them. */
    private List<IndexedChunk> indexable() {
        return t.repos.chunks().withIndexable(MODEL, rows -> rows.map(Passages::fromRow).toList());
    }

    private List<SearchQuery> questions() {
        List<SearchQuery> questions = new ArrayList<>();
        for (String text : List.of("How do cells get energy?", "What does the membrane do?", "osmosis water",
                "proteins and enzymes", "printing press ideas", "cells", "steam factories railways")) {
            questions.add(query(biology.id(), text));
            questions.add(query(history.id(), text));
            questions.add(query(biology.id(), text, 3, 3, 4));
        }
        return questions;
    }

    @Test
    void rebuildingFromTheDatabaseGivesTheSameResultsAsIndexingDocumentByDocument() {
        // Built the way imports build it: one document's passages at a time, committed after each.
        LuceneSearchIndex incremental = open("incremental");
        incremental.rebuild(Stream.empty());
        Map<String, List<IndexedChunk>> byDocument = new LinkedHashMap<>();
        for (IndexedChunk passage : indexable()) {
            byDocument.computeIfAbsent(passage.documentId(), d -> new ArrayList<>()).add(passage);
        }
        byDocument.values().forEach(passages -> {
            incremental.upsert(passages);
            incremental.commit();
        });
        LuceneSearchIndex rebuilt = open("rebuilt");
        assertThat(rebuilt.needsRebuild()).isTrue();

        rebuildFromDatabase(rebuilt);

        assertThat(rebuilt.needsRebuild()).isFalse();
        assertThat(rebuilt.size()).isEqualTo(incremental.size()).isEqualTo(BIOLOGY.length + HISTORY.length);
        for (SearchQuery question : questions()) {
            List<SearchHit> expected = incremental.search(question);
            assertThat(expected).isNotEmpty();
            assertThat(rebuilt.search(question)).as(question.text()).isEqualTo(expected);
        }

        // Rebuilding an index in place gives the same results again.
        rebuildFromDatabase(incremental);
        for (SearchQuery question : questions()) {
            assertThat(incremental.search(question)).as(question.text()).isEqualTo(rebuilt.search(question));
        }
    }

    @Test
    void theRebuildHoldsOnlyReadyPassagesFromTheCurrentEmbedder() {
        LuceneSearchIndex index = open("index");

        rebuildFromDatabase(index);

        assertThat(index.size()).isEqualTo(BIOLOGY.length + HISTORY.length);
        assertThat(index.search(query(biology.id(), "still importing"))).noneMatch(h -> h.text().contains("importing"));
        assertThat(index.search(query(history.id(), "older embedder"))).noneMatch(h -> h.text().contains("older"));
        SearchHit osmosis = index.search(query(biology.id(), "osmosis")).getFirst();
        assertThat(osmosis.text()).isEqualTo(BIOLOGY[4]);
        assertThat(osmosis.documentTitle()).isEqualTo("Cell structure");
        assertThat(osmosis.pageStart()).isEqualTo(5);
        assertThat(osmosis.sectionTitle()).isEqualTo("Section 4");
        assertThat(osmosis.moduleId()).isNotNull();
    }

    @Test
    void aFailedRebuildLeavesThePreviousContents() {
        LuceneSearchIndex index = open("index");
        rebuildFromDatabase(index);
        List<SearchHit> before = index.search(query(biology.id(), "osmosis membrane"));
        AtomicInteger served = new AtomicInteger();
        Stream<IndexedChunk> cancelledHalfway = Stream.generate(() -> {
            if (served.incrementAndGet() > 5) {
                throw new CancellationException("job cancelled");
            }
            return passage(biology.id(), "doc-new", null, "Replacement passage " + served.get());
        });

        assertThatThrownBy(() -> index.rebuild(cancelledHalfway)).isInstanceOf(CancellationException.class);

        assertThat(index.needsRebuild()).isFalse();
        assertThat(index.size()).isEqualTo(BIOLOGY.length + HISTORY.length);
        assertThat(index.search(query(biology.id(), "osmosis membrane"))).isEqualTo(before);
        // The index keeps working after the failed rebuild.
        IndexedChunk later = passage(biology.id(), "doc-later", null, "Cytoplasm fills the cell.");
        index.upsert(List.of(later));
        index.commit();
        assertThat(ids(index.search(query(biology.id(), "cytoplasm"))).getFirst()).isEqualTo(later.chunkId());
        index.close();

        try (LuceneSearchIndex reopened = LuceneSearchIndex.open(dir.resolve("index"), DIMENSIONS, MODEL)) {
            assertThat(reopened.needsRebuild()).isFalse();
            assertThat(reopened.size()).isEqualTo(BIOLOGY.length + HISTORY.length + 1);
        }
    }

    @Test
    void searchesSeeThePreviousContentsUntilTheRebuildCommitsAndWritesWaitForIt() throws Exception {
        LuceneSearchIndex index = open("index");
        IndexedChunk old = passage("bio", "doc-old", null, "Osmosis in the old edition.");
        index.rebuild(Stream.of(old));
        CountDownLatch halfway = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        List<IndexedChunk> fresh = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fresh.add(passage("bio", "doc-new", null, "Osmosis in the new edition, part " + i + "."));
        }
        Stream<IndexedChunk> pausing = fresh.stream().peek(p -> {
            if (p == fresh.get(10)) {
                halfway.countDown();
                await(resume);
            }
        });

        CompletableFuture<Void> rebuild = CompletableFuture.runAsync(() -> index.rebuild(pausing));
        assertThat(halfway.await(10, TimeUnit.SECONDS)).isTrue();

        assertThat(ids(index.search(query("bio", "osmosis edition")))).containsExactly(old.chunkId());
        assertThat(index.size()).isEqualTo(1);
        IndexedChunk added = passage("bio", "doc-added", null, "Osmosis added while rebuilding.");
        CompletableFuture<Void> write = CompletableFuture.runAsync(() -> index.upsert(List.of(added)));
        Thread.sleep(200);
        assertThat(write).isNotDone();

        resume.countDown();
        rebuild.get(10, TimeUnit.SECONDS);
        write.get(10, TimeUnit.SECONDS);

        assertThat(index.size()).isEqualTo(20);
        index.commit();
        List<String> after = ids(index.search(query("bio", "osmosis edition").withLimit(50)));
        assertThat(after).hasSize(21).contains(added.chunkId()).doesNotContain(old.chunkId());
    }

    @Test
    void theRebuildSkipsPassagesWhoseEmbeddingCannotBeUsed() {
        LuceneSearchIndex index = open("index");
        IndexedChunk good = passage("bio", "doc-1", null, "Osmosis moves water.");
        IndexedChunk wrongLength = new IndexedChunk("short", "doc-1", "bio", null, "", 1, 1, null, "Osmosis short",
                new float[] {1f, 0f});
        IndexedChunk zeros = new IndexedChunk("zeros", "doc-1", "bio", null, "", 1, 1, null, "Osmosis zeros",
                new float[DIMENSIONS]);

        index.rebuild(Stream.of(wrongLength, good, zeros));

        assertThat(index.needsRebuild()).isFalse();
        assertThat(ids(index.search(query("bio", "osmosis")))).containsExactly(good.chunkId());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
