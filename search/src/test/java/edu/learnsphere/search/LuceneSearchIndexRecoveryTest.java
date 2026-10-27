package edu.learnsphere.search;

import static edu.learnsphere.search.Passages.DIMENSIONS;
import static edu.learnsphere.search.Passages.MODEL;
import static edu.learnsphere.search.Passages.ids;
import static edu.learnsphere.search.Passages.passage;
import static edu.learnsphere.search.Passages.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.spi.SearchQuery;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class LuceneSearchIndexRecoveryTest {

    @TempDir
    Path dir;

    private Path folder() {
        return dir.resolve("profile").resolve("index");
    }

    /** Builds a complete index of 40 passages in {@link #folder()}, closes it and returns the passages. */
    private List<IndexedChunk> completeIndex() {
        List<IndexedChunk> passages = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            passages.add(passage("bio", "doc-" + i % 4, null, "Osmosis moves water across membrane number " + i + "."));
        }
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            index.rebuild(passages.stream());
            assertThat(index.size()).isEqualTo(40);
        }
        return passages;
    }

    @Test
    void opensAMissingFolderEmptyAndNeedingARebuild() {
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(Files.isDirectory(folder())).isTrue();
            assertThat(index.needsRebuild()).isTrue();
            assertThat(index.size()).isZero();
            assertThat(index.search(query("bio", "osmosis"))).isEmpty();
        }
    }

    @Test
    void reopensACompleteIndexAsItWasIncludingWritesPendingAtClose() {
        completeIndex();
        IndexedChunk pending = passage("bio", "doc-9", null, "Diffusion spreads particles evenly.");
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isFalse();
            assertThat(index.size()).isEqualTo(40);
            index.upsert(List.of(pending));
        }

        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isFalse();
            assertThat(index.size()).isEqualTo(41);
            assertThat(ids(index.search(query("bio", "diffusion particles"))).getFirst()).isEqualTo(pending.chunkId());
        }
    }

    @Test
    void anIndexForOtherDimensionsIsClearedAndTakesTheNewShape() {
        try (LuceneSearchIndex small = LuceneSearchIndex.open(folder(), 8, MODEL)) {
            small.rebuild(Stream.of(new IndexedChunk("c1", "d", "bio", null, "", 1, 1, null, "Osmosis",
                    new float[] {1, 0, 0, 0, 0, 0, 0, 0})));
        }

        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isTrue();
            assertThat(index.size()).isZero();
            IndexedChunk passage = passage("bio", "doc-1", null, "Osmosis moves water.");
            index.rebuild(Stream.of(passage));
            assertThat(ids(index.search(query("bio", "osmosis")))).containsExactly(passage.chunkId());
        }
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isFalse();
        }
    }

    @Test
    void anIndexFromAnotherEmbedderIsCleared() {
        completeIndex();

        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, "another-embedder")) {
            assertThat(index.needsRebuild()).isTrue();
            assertThat(index.size()).isZero();
            assertThat(index.embeddingModel()).isEqualTo("another-embedder");
        }
        // And back: the index now belongs to "another-embedder" and its rebuild never ran.
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isTrue();
        }
    }

    @Test
    void anIndexFilledOnlyPartlyBeforeARestartIsRebuiltAgain() {
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isTrue();
            index.upsert(List.of(passage("bio", "doc-1", null, "Osmosis moves water.")));
            index.commit();
        }

        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isTrue();
            assertThat(index.size()).isZero();
        }
    }

    @Test
    void aRebuildThatStopsHalfwayIsRunAgainAfterARestart() {
        AtomicInteger served = new AtomicInteger();
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            Stream<IndexedChunk> stopsAfterTen = Stream.generate(() -> {
                if (served.incrementAndGet() > 10) {
                    throw new CancellationException("job cancelled");
                }
                return passage("bio", "doc-1", null, "Osmosis passage " + served.get());
            });
            assertThatThrownBy(() -> index.rebuild(stopsAfterTen)).isInstanceOf(CancellationException.class);
            assertThat(index.needsRebuild()).isTrue();
            assertThat(index.size()).isZero();
        }

        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isTrue();
            assertThat(index.size()).isZero();
        }
    }

    /** Ways the files of a closed index get damaged. */
    enum Damage {
        TRUNCATED_COMMIT_FILE, GARBLED_COMMIT_FILE, FLIPPED_DATA_BYTE, TRUNCATED_DATA_FILE, MISSING_DATA_FILE, ONLY_JUNK
    }

    @ParameterizedTest
    @EnumSource(Damage.class)
    void aDamagedIndexIsClearedAndRebuilds(Damage damage) throws IOException {
        List<IndexedChunk> passages = completeIndex();
        damage(folder(), damage);

        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isTrue();
            assertThat(index.size()).isZero();
            assertThat(index.search(query("bio", "osmosis"))).isEmpty();

            index.rebuild(passages.stream());

            assertThat(index.needsRebuild()).isFalse();
            assertThat(index.size()).isEqualTo(passages.size());
            assertThat(index.search(query("bio", "osmosis membrane"))).hasSize(24);
        }
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isFalse();
            assertThat(index.size()).isEqualTo(passages.size());
        }
    }

    @Test
    void anIndexMarkedAsDamagedWhileOpenIsClearedOnTheNextOpen() throws IOException {
        completeIndex();
        Files.writeString(folder().resolve(LuceneSearchIndex.REBUILD_MARKER), "damaged");

        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isTrue();
            assertThat(index.size()).isZero();
            assertThat(folder().resolve(LuceneSearchIndex.REBUILD_MARKER)).doesNotExist();
        }
    }

    /** Removes the segment-info files of the committed passages: a commit or reopen must read them again. */
    private void loseSegmentInfo() throws IOException {
        List<Path> infos = files(folder()).stream().filter(p -> p.getFileName().toString().endsWith(".si")).toList();
        assertThat(infos).isNotEmpty();
        for (Path info : infos) {
            Files.delete(info);
        }
    }

    private static void assertAsksForARestart(ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LearnSphereException.class,
                e -> assertThat(e.userMessage()).isEqualTo(SearchFailures.NEEDS_RESTART));
    }

    @Test
    void damageFoundWhileOpenAsksForARestartAndTheNextOpenStartsEmpty() throws IOException {
        List<IndexedChunk> passages = completeIndex();
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.search(query("bio", "osmosis"))).isNotEmpty();
            loseSegmentInfo();
            index.upsert(List.of(passage("bio", "doc-9", null, "Diffusion spreads particles evenly.")));

            assertAsksForARestart(index::commit);

            assertThat(index.needsRebuild()).isTrue();
            assertThat(folder().resolve(LuceneSearchIndex.REBUILD_MARKER)).exists();
            // Rather than answers from damaged passages, nothing until the rebuild.
            assertThat(index.search(query("bio", "osmosis"))).isEmpty();
        }

        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isTrue();
            assertThat(index.size()).isZero();
            assertThat(folder().resolve(LuceneSearchIndex.REBUILD_MARKER)).doesNotExist();

            index.rebuild(passages.stream());

            assertThat(index.size()).isEqualTo(passages.size());
        }
    }

    @Test
    void anUndoneRebuildThatCannotReturnToTheLastCommitStopsWritesAndTheNextOpenStartsEmpty() throws IOException {
        List<IndexedChunk> passages = completeIndex();
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            // Damage nothing has noticed yet; the rebuild is cancelled and must go back to the damaged commit.
            loseSegmentInfo();
            AtomicInteger served = new AtomicInteger();
            Stream<IndexedChunk> cancelledHalfway = Stream.generate(() -> {
                if (served.incrementAndGet() > 5) {
                    throw new CancellationException("job cancelled");
                }
                return passages.get(served.get());
            });

            assertThatThrownBy(() -> index.rebuild(cancelledHalfway)).isInstanceOf(CancellationException.class);

            assertThat(index.needsRebuild()).isTrue();
            assertThat(folder().resolve(LuceneSearchIndex.REBUILD_MARKER)).exists();
            assertThat(index.search(query("bio", "osmosis"))).isEmpty();
            assertAsksForARestart(index::size);
            assertAsksForARestart(() -> index.upsert(List.of(passage("bio", "doc-9", null, "Diffusion."))));
            assertAsksForARestart(() -> index.deleteByDocument("doc-1"));
            assertAsksForARestart(index::commit);
        }

        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isTrue();
            assertThat(index.size()).isZero();
        }
    }

    /** Ways damage comes to light while the index is open. */
    enum FoundBy {
        /** A commit that must sync the lost file; the writer is left with changes it cannot commit. */
        COMMIT,
        /** An undone rebuild that cannot reopen the last commit; the index is left without a writer. */
        UNDONE_REBUILD
    }

    @ParameterizedTest
    @EnumSource(FoundBy.class)
    void aRebuildRepairsDamageFoundWhileOpenWithoutARestart(FoundBy foundBy) throws IOException {
        List<IndexedChunk> passages = completeIndex();
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            loseSegmentInfo();
            switch (foundBy) {
                case COMMIT -> {
                    index.upsert(List.of(passage("bio", "doc-9", null, "Diffusion spreads particles evenly.")));
                    assertAsksForARestart(index::commit);
                }
                case UNDONE_REBUILD -> assertThatThrownBy(() -> index.rebuild(Stream.generate(() -> {
                    throw new CancellationException("job cancelled");
                }))).isInstanceOf(CancellationException.class);
            }
            assertThat(index.needsRebuild()).isTrue();

            index.rebuild(passages.stream());

            assertThat(index.needsRebuild()).isFalse();
            assertThat(folder().resolve(LuceneSearchIndex.REBUILD_MARKER)).doesNotExist();
            assertThat(index.size()).isEqualTo(passages.size());
            assertThat(index.search(query("bio", "osmosis membrane"))).hasSize(24);
            IndexedChunk added = passage("bio", "doc-9", null, "Diffusion spreads particles evenly.");
            index.upsert(List.of(added));
            index.commit();
            assertThat(ids(index.search(query("bio", "diffusion particles"))).getFirst()).isEqualTo(added.chunkId());
        }

        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isFalse();
            assertThat(index.size()).isEqualTo(passages.size() + 1);
        }
    }

    @Test
    void anIndexOpenElsewhereIsRefusedAndLeftUntouched() {
        List<IndexedChunk> passages = completeIndex();
        try (LuceneSearchIndex first = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThatThrownBy(() -> LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL))
                    .isInstanceOf(UncheckedIOException.class);
            // Even a mismatched or marked open must not clear an index someone else holds.
            assertThatThrownBy(() -> LuceneSearchIndex.open(folder(), DIMENSIONS, "another-embedder"))
                    .isInstanceOf(UncheckedIOException.class);

            assertThat(first.size()).isEqualTo(passages.size());
            first.upsert(List.of(passage("bio", "doc-1", null, "Still writable.")));
            first.commit();
        }
        try (LuceneSearchIndex index = LuceneSearchIndex.open(folder(), DIMENSIONS, MODEL)) {
            assertThat(index.needsRebuild()).isFalse();
            assertThat(index.size()).isEqualTo(passages.size() + 1);
        }
    }

    @Test
    void failsClearlyWhenTheFolderCannotBeCreated() throws IOException {
        Path blocker = Files.writeString(dir.resolve("not-a-folder"), "x");

        assertThatThrownBy(() -> LuceneSearchIndex.open(blocker.resolve("index"), DIMENSIONS, MODEL))
                .isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void theFactoryOpensIndexes() {
        SearchIndex index = new LuceneSearchIndexFactory().open(folder(), DIMENSIONS, MODEL);
        try (index) {
            assertThat(index).isInstanceOf(LuceneSearchIndex.class);
            assertThat(index.needsRebuild()).isTrue();
            IndexedChunk passage = passage("bio", "doc-1", null, "Osmosis moves water.");
            index.rebuild(Stream.of(passage));
            assertThat(index.search(new SearchQuery("osmosis", null, "bio", Set.of(), 40, 0, 24, 60))).hasSize(1);
        }
    }

    private static void damage(Path folder, Damage damage) throws IOException {
        Path commit = files(folder).stream().filter(p -> p.getFileName().toString().startsWith("segments_")).findFirst()
                .orElseThrow();
        Path largest = files(folder).stream()
                .filter(p -> !p.getFileName().toString().startsWith("segments_"))
                .max(Comparator.comparingLong(LuceneSearchIndexRecoveryTest::size))
                .orElseThrow();
        switch (damage) {
            case TRUNCATED_COMMIT_FILE -> truncate(commit, size(commit) / 2);
            case GARBLED_COMMIT_FILE -> Files.writeString(commit, "this is not an index", StandardCharsets.UTF_8);
            case FLIPPED_DATA_BYTE -> {
                try (RandomAccessFile file = new RandomAccessFile(largest.toFile(), "rw")) {
                    long middle = file.length() / 2;
                    file.seek(middle);
                    int b = file.read();
                    file.seek(middle);
                    file.write(b ^ 0x5A);
                }
            }
            case TRUNCATED_DATA_FILE -> truncate(largest, size(largest) - 100);
            case MISSING_DATA_FILE -> Files.delete(largest);
            case ONLY_JUNK -> {
                for (Path file : files(folder)) {
                    Files.delete(file);
                }
                Files.writeString(folder.resolve("segments_7"), "junk", StandardCharsets.UTF_8);
                Files.writeString(folder.resolve("_3.cfs"), "more junk", StandardCharsets.UTF_8);
            }
        }
    }

    private static List<Path> files(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("write.lock"))
                    .toList();
        }
    }

    private static long size(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void truncate(Path file, long length) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "rw")) {
            f.setLength(length);
        }
    }
}
