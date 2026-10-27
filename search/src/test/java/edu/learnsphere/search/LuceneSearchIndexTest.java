package edu.learnsphere.search;

import static edu.learnsphere.search.Passages.DIMENSIONS;
import static edu.learnsphere.search.Passages.EMBEDDER;
import static edu.learnsphere.search.Passages.MODEL;
import static edu.learnsphere.search.Passages.filler;
import static edu.learnsphere.search.Passages.hit;
import static edu.learnsphere.search.Passages.ids;
import static edu.learnsphere.search.Passages.passage;
import static edu.learnsphere.search.Passages.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchQuery;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LuceneSearchIndexTest {

    @TempDir
    Path dir;

    private LuceneSearchIndex index;

    @BeforeEach
    void openReadyIndex() {
        index = LuceneSearchIndex.open(dir.resolve("index"), DIMENSIONS, MODEL);
        index.rebuild(Stream.empty());
    }

    @AfterEach
    void close() {
        index.close();
    }

    @Test
    void findsPassagesByWordsAndByMeaning() {
        IndexedChunk mitochondria = passage("bio", "doc-1", null,
                "Mitochondria release energy from glucose through cellular respiration.");
        IndexedChunk ribosomes = passage("bio", "doc-1", null, "Ribosomes build proteins from amino acids.");
        index.upsert(List.of(mitochondria, ribosomes));
        index.commit();

        List<SearchHit> hits = index.search(query("bio", "How do mitochondria release energy?"));

        assertThat(hits).isNotEmpty();
        SearchHit top = hits.getFirst();
        assertThat(top.chunkId()).isEqualTo(mitochondria.chunkId());
        assertThat(top.keywordRank()).isEqualTo(1);
        assertThat(top.vectorRank()).isEqualTo(1);
        assertThat(top.score()).isCloseTo(2.0 / 61, within(1e-12));
        assertThat(top.documentId()).isEqualTo("doc-1");
        assertThat(top.courseId()).isEqualTo("bio");
        assertThat(top.moduleId()).isNull();
        assertThat(top.documentTitle()).isEqualTo(mitochondria.documentTitle());
        assertThat(top.pageStart()).isEqualTo(mitochondria.pageStart());
        assertThat(top.pageEnd()).isEqualTo(mitochondria.pageEnd());
        assertThat(top.sectionTitle()).isEqualTo(mitochondria.sectionTitle());
        assertThat(top.text()).isEqualTo(mitochondria.text());
    }

    @Test
    void neverReturnsPassagesOfAnotherCourse() {
        // Course "other" holds many passages that match the question better than anything in "bio", in both rankings.
        List<IndexedChunk> other = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            other.add(passage("other", "doc-o" + i % 3, null, "Photosynthesis photosynthesis chlorophyll reactions " + i));
        }
        IndexedChunk weak = passage("bio", "doc-b", null, "Leaves contain chlorophyll in their chloroplasts.");
        List<IndexedChunk> bioFillers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            bioFillers.add(passage("bio", "doc-b", null, filler(i)));
        }
        index.upsert(other);
        index.upsert(List.of(weak));
        index.upsert(bioFillers);
        index.commit();

        // Few candidates per ranking: filtering after ranking would leave "bio" with nothing.
        List<SearchHit> hits = index.search(query("bio", "photosynthesis chlorophyll reactions", 3, 3, 10));

        assertThat(hits).isNotEmpty().allSatisfy(h -> assertThat(h.courseId()).isEqualTo("bio"));
        SearchHit top = hit(hits, weak);
        assertThat(top.keywordRank()).isEqualTo(1);
        assertThat(top.vectorRank()).isEqualTo(1);
        assertThat(hits.stream().filter(h -> h.vectorRank() != null)).hasSize(3);

        assertThat(index.search(query("other", "chloroplasts leaves"))).allSatisfy(h -> assertThat(h.courseId()).isEqualTo("other"));
        assertThat(index.search(query("missing-course", "photosynthesis"))).isEmpty();
    }

    @Test
    void keywordOnlyAndVectorOnlyMatchesBothSurface() {
        // Shares the stemmed word "membran" with the question but none of its fake-embedding words.
        IndexedChunk byWords = passage("bio", "doc-1", null, "Every membrane is selectively permeable.");
        // Embedded as if it were about membranes, but its text never says so (as a paraphrase would).
        IndexedChunk byMeaning = passage("bio", "doc-1", null, "Plant cells swell when they take up water.",
                "membranes around plant cells");
        List<IndexedChunk> passages = new ArrayList<>(List.of(byWords, byMeaning));
        // Fillers sit nearer the question than byWords in the vector ranking, without its words.
        for (int i = 0; i < 20; i++) {
            passages.add(passage("bio", "doc-2", null, filler(i), "membranes " + filler(i)));
        }
        index.upsert(passages);
        index.commit();

        List<SearchHit> hits = index.search(query("bio", "membranes", 3, 3, 10));

        SearchHit keywordOnly = hit(hits, byWords);
        assertThat(keywordOnly.keywordRank()).isEqualTo(1);
        assertThat(keywordOnly.vectorRank()).isNull();
        assertThat(keywordOnly.score()).isCloseTo(1.0 / 61, within(1e-12));
        SearchHit vectorOnly = hit(hits, byMeaning);
        assertThat(vectorOnly.vectorRank()).isEqualTo(1);
        assertThat(vectorOnly.keywordRank()).isNull();
        assertThat(vectorOnly.score()).isCloseTo(1.0 / 61, within(1e-12));
        // Equal scores: the keyword result comes first.
        assertThat(ids(hits).subList(0, 2)).containsExactly(byWords.chunkId(), byMeaning.chunkId());
    }

    @Test
    void searchesWithOnlyOneRankingWhenTheOtherHasNothingToUse() {
        IndexedChunk passage = passage("bio", "doc-1", null, "Enzymes lower the activation energy of reactions.");
        index.upsert(List.of(passage));
        index.commit();

        List<SearchHit> keywordOnly = index.search(new SearchQuery("enzymes", null, "bio", Set.of(), 40, 40, 24, 60));
        assertThat(keywordOnly).singleElement().satisfies(h -> {
            assertThat(h.keywordRank()).isEqualTo(1);
            assertThat(h.vectorRank()).isNull();
        });

        // Only stop words: no keyword clause, the vector ranking still answers.
        List<SearchHit> vectorOnly = index.search(new SearchQuery("the of and", EMBEDDER.vector("enzymes"), "bio",
                Set.of(), 40, 40, 24, 60));
        assertThat(vectorOnly).singleElement().satisfies(h -> {
            assertThat(h.keywordRank()).isNull();
            assertThat(h.vectorRank()).isEqualTo(1);
        });

        assertThat(index.search(new SearchQuery("enzymes", EMBEDDER.vector("enzymes"), "bio", Set.of(), 0, 0, 24, 60)))
                .isEmpty();
    }

    @Test
    void fusesBothRankingsAndKeepsTheLimit() {
        List<IndexedChunk> passages = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            passages.add(passage("bio", "doc-1", null, "Cell division step " + i + " involves chromosomes and spindles."));
        }
        index.upsert(passages);
        index.commit();

        List<SearchHit> hits = index.search(query("bio", "chromosomes spindles cell division", 10, 10, 5));

        assertThat(hits).hasSize(5);
        for (SearchHit h : hits) {
            double expected = (h.keywordRank() == null ? 0 : 1.0 / (60 + h.keywordRank()))
                    + (h.vectorRank() == null ? 0 : 1.0 / (60 + h.vectorRank()));
            assertThat(h.score()).isCloseTo(expected, within(1e-12));
        }
        assertThat(hits).extracting(SearchHit::score).isSortedAccordingTo((a, b) -> Double.compare(b, a));
    }

    @Test
    void restrictsToModulesWhenAsked() {
        IndexedChunk inA = passage("bio", "doc-1", "module-a", "Osmosis moves water across membranes.");
        IndexedChunk inB = passage("bio", "doc-2", "module-b", "Osmosis keeps plant cells firm.");
        IndexedChunk noModule = passage("bio", "doc-3", null, "Osmosis explained with diagrams.");
        index.upsert(List.of(inA, inB, noModule));
        index.commit();

        assertThat(ids(index.search(query("bio", "osmosis").inModules(Set.of("module-a")))))
                .containsExactly(inA.chunkId());
        assertThat(ids(index.search(query("bio", "osmosis").inModules(Set.of("module-a", "module-b")))))
                .containsExactlyInAnyOrder(inA.chunkId(), inB.chunkId());
        assertThat(ids(index.search(query("bio", "osmosis"))))
                .containsExactlyInAnyOrder(inA.chunkId(), inB.chunkId(), noModule.chunkId());
    }

    @Test
    void writesBecomeVisibleAfterCommit() {
        IndexedChunk passage = passage("bio", "doc-1", null, "Neurons transmit electrical signals.");
        index.upsert(List.of(passage));

        assertThat(index.search(query("bio", "neurons"))).isEmpty();
        assertThat(index.size()).isZero();

        index.commit();

        assertThat(ids(index.search(query("bio", "neurons")))).containsExactly(passage.chunkId());
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void upsertReplacesAPassageWithTheSameId() {
        IndexedChunk original = passage("bio", "doc-1", null, "Neurons transmit electrical signals.");
        index.upsert(List.of(original));
        index.commit();
        IndexedChunk edited = new IndexedChunk(original.chunkId(), "doc-1", "bio", null, "Renamed", 3, 4, null,
                "Synapses pass chemical messages.", EMBEDDER.vector("Synapses pass chemical messages."));

        index.upsert(List.of(edited));
        index.commit();

        assertThat(index.size()).isEqualTo(1);
        assertThat(index.search(query("bio", "neurons electrical")).stream().filter(h -> h.keywordRank() != null)).isEmpty();
        assertThat(index.search(query("bio", "synapses"))).singleElement().satisfies(h -> {
            assertThat(h.text()).isEqualTo("Synapses pass chemical messages.");
            assertThat(h.documentTitle()).isEqualTo("Renamed");
            assertThat(h.sectionTitle()).isNull();
            assertThat(h.pageStart()).isEqualTo(3);
        });
    }

    @Test
    void deleteRemovesPassagesByIdDocumentAndCourse() {
        IndexedChunk a1 = passage("bio", "doc-a", null, "Osmosis moves water.");
        IndexedChunk a2 = passage("bio", "doc-a", null, "Osmosis and diffusion differ.");
        IndexedChunk b1 = passage("bio", "doc-b", null, "Osmosis in red blood cells.");
        IndexedChunk b2 = passage("bio", "doc-b", null, "Osmosis in plant roots.");
        IndexedChunk c1 = passage("chem", "doc-c", null, "Osmosis pressure equations.");
        index.upsert(List.of(a1, a2, b1, b2, c1));
        index.commit();
        assertThat(index.size()).isEqualTo(5);

        index.delete(List.of(b2.chunkId(), "not-indexed"));
        index.commit();
        assertThat(ids(index.search(query("bio", "osmosis")))).containsExactlyInAnyOrder(a1.chunkId(), a2.chunkId(), b1.chunkId());

        index.deleteByDocument("doc-a");
        index.commit();
        assertThat(ids(index.search(query("bio", "osmosis")))).containsExactly(b1.chunkId());

        index.deleteByCourse("bio");
        index.commit();
        assertThat(index.search(query("bio", "osmosis"))).isEmpty();
        assertThat(ids(index.search(query("chem", "osmosis")))).containsExactly(c1.chunkId());
        assertThat(index.size()).isEqualTo(1);
    }

    @Test
    void normalizesVectorsThatAreNotUnitLength() {
        IndexedChunk unit = passage("bio", "doc-1", null, "Genes are made of DNA.");
        float[] scaled = unit.embedding().clone();
        for (int i = 0; i < scaled.length; i++) {
            scaled[i] *= 3;
        }
        IndexedChunk longer = new IndexedChunk(unit.chunkId(), "doc-1", "bio", null, "", 1, 1, null, unit.text(), scaled);
        index.upsert(List.of(longer));
        index.commit();

        float[] question = EMBEDDER.vector("Genes are made of DNA.");
        for (int i = 0; i < question.length; i++) {
            question[i] *= 0.25f;
        }
        List<SearchHit> hits = index.search(new SearchQuery("genes", question, "bio", Set.of(), 40, 40, 24, 60));
        assertThat(hits).singleElement().satisfies(h -> assertThat(h.vectorRank()).isEqualTo(1));
    }

    @Test
    void rejectsUnusableEmbeddingsWithoutWritingAnyOfTheBatch() {
        IndexedChunk good = passage("bio", "doc-1", null, "Good passage about osmosis.");
        IndexedChunk shortVector = new IndexedChunk("bad", "doc-1", "bio", null, "", 1, 1, null, "Bad", new float[DIMENSIONS - 1]);
        float[] nan = good.embedding().clone();
        nan[3] = Float.NaN;

        assertThatThrownBy(() -> index.upsert(List.of(good, shortVector)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(Integer.toString(DIMENSIONS));
        assertThatThrownBy(() -> index.upsert(List.of(new IndexedChunk("zero", "d", "bio", null, "", 1, 1, null, "Zero",
                new float[DIMENSIONS])))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.upsert(List.of(new IndexedChunk("nan", "d", "bio", null, "", 1, 1, null, "NaN", nan))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> index.search(SearchQuery.forCourse("bio", "osmosis", new float[3])))
                .isInstanceOf(IllegalArgumentException.class);

        index.commit();
        assertThat(index.size()).isZero();
    }

    @Test
    void handlesLongQuestionsAndQueryText() {
        index.upsert(List.of(passage("bio", "doc-1", null, "Osmosis moves water across membranes.")));
        index.commit();
        StringBuilder pasted = new StringBuilder("osmosis ");
        for (int i = 0; i < 5_000; i++) {
            pasted.append("word").append(i).append(' ');
        }
        pasted.append("AND OR NOT \"unbalanced ( [ * ? ~ ^ : \\ /");

        assertThat(index.search(query("bio", pasted.toString()))).hasSize(1);
        assertThat(index.search(query("bio", "*:* OR title:\"x\"~2 [a TO z]"))).hasSize(1);
    }

    @Test
    void anIndexThatNeedsARebuildReturnsNothingUntilRebuilt() {
        Path other = dir.resolve("fresh");
        try (LuceneSearchIndex fresh = LuceneSearchIndex.open(other, DIMENSIONS, MODEL)) {
            assertThat(fresh.needsRebuild()).isTrue();
            IndexedChunk passage = passage("bio", "doc-1", null, "Osmosis moves water.");
            fresh.upsert(List.of(passage));
            fresh.commit();
            assertThat(fresh.search(query("bio", "osmosis"))).isEmpty();

            fresh.rebuild(Stream.of(passage));

            assertThat(fresh.needsRebuild()).isFalse();
            assertThat(ids(fresh.search(query("bio", "osmosis")))).containsExactly(passage.chunkId());
        }
    }

    @Test
    void reportsItsShapeAndRefusesUseAfterClose() {
        assertThat(index.dimensions()).isEqualTo(DIMENSIONS);
        assertThat(index.embeddingModel()).isEqualTo(MODEL);
        assertThat(LuceneSearchIndex.maxDimensions()).isGreaterThanOrEqualTo(1024);

        index.close();
        index.close();

        assertThatThrownBy(() -> index.search(query("bio", "x"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> index.upsert(List.of(passage("bio", "d", null, "x")))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> index.commit()).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> index.size()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesUnsupportedShapes() {
        assertThatThrownBy(() -> LuceneSearchIndex.open(dir.resolve("x"), 0, MODEL)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LuceneSearchIndex.open(dir.resolve("x"), LuceneSearchIndex.maxDimensions() + 1, MODEL))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LuceneSearchIndex.open(dir.resolve("x"), 8, " ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void searchHitsDoNotShareState() {
        IndexedChunk passage = passage("bio", "doc-1", null, "Osmosis moves water.");
        index.upsert(List.of(passage));
        index.commit();
        float[] vector = EMBEDDER.vector("osmosis");
        float[] copy = Arrays.copyOf(vector, vector.length);

        index.search(SearchQuery.forCourse("bio", "osmosis", vector));

        assertThat(vector).containsExactly(copy);
    }
}
