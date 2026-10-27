package edu.learnsphere.search;

import edu.learnsphere.ai.fake.FakeEmbedder;
import edu.learnsphere.core.db.ChunkRepository.ChunkWithTitle;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.spi.SearchQuery;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/** Test passages embedded with the {@link FakeEmbedder}. */
final class Passages {

    static final FakeEmbedder EMBEDDER = new FakeEmbedder();
    static final int DIMENSIONS = EMBEDDER.dimensions();
    static final String MODEL = EMBEDDER.modelName();

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private Passages() {
    }

    /** A passage of {@code course}/{@code document} whose embedding is the fake embedding of its own text. */
    static IndexedChunk passage(String course, String document, String module, String text) {
        return passage(course, document, module, text, text);
    }

    /** A passage whose embedding is the fake embedding of {@code meaning} (a paraphrase the words don't show). */
    static IndexedChunk passage(String course, String document, String module, String text, String meaning) {
        int n = SEQUENCE.incrementAndGet();
        return new IndexedChunk("chunk-" + n, document, course, module, "Title of " + document, n % 50 + 1, n % 50 + 2,
                "Section " + n % 7, text, EMBEDDER.vector(meaning));
    }

    /** The default course search for {@code text}, with its fake query embedding. */
    static SearchQuery query(String course, String text) {
        return SearchQuery.forCourse(course, text, EMBEDDER.vector(text));
    }

    /** A query with explicit candidate counts. */
    static SearchQuery query(String course, String text, int keywordTopK, int vectorTopK, int limit) {
        return new SearchQuery(text, EMBEDDER.vector(text), course, Set.of(), keywordTopK, vectorTopK, limit, 60);
    }

    /** The passage the workspace's rebuild job feeds the index for a stored chunk. */
    static IndexedChunk fromRow(ChunkWithTitle row) {
        Chunk chunk = row.chunk();
        return new IndexedChunk(chunk.id(), chunk.documentId(), chunk.courseId(), chunk.moduleId(), row.documentTitle(),
                chunk.pageStart(), chunk.pageEnd(), chunk.sectionTitle(), chunk.text(), chunk.embedding());
    }

    static List<String> ids(List<SearchHit> hits) {
        return hits.stream().map(SearchHit::chunkId).toList();
    }

    static SearchHit hit(List<SearchHit> hits, IndexedChunk passage) {
        return hits.stream().filter(h -> h.chunkId().equals(passage.chunkId())).findFirst()
                .orElseThrow(() -> new AssertionError("No hit for " + passage.chunkId() + " in " + ids(hits)));
    }

    /** Filler text on a topic far from the tests' questions. */
    static String filler(int i) {
        String[] topics = {
            "Glaciers carve valleys as they slowly advance across mountain ranges.",
            "Medieval guilds regulated trade and apprenticeships in growing towns.",
            "Supply and demand curves meet at the market equilibrium price.",
            "Sonnets traditionally follow fourteen lines with a strict rhyme scheme.",
            "Volcanic eruptions release ash, gases and molten lava from the crust.",
            "Binary search halves the remaining interval on every comparison.",
            "Impressionist painters captured light with quick visible brushstrokes.",
            "Tectonic plates drift a few centimetres every year.",
        };
        return topics[i % topics.length] + " Part " + i + ".";
    }
}
