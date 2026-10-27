package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.PageSource;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChunkRepositoryTest extends RepositoryTestBase {

    @Test
    void roundTripsChunksWithEmbeddings() {
        Document document = t.document(t.course("C"), null, "Doc");
        Chunk chunk = Chunk.create(document.id(), document.courseId(), null, 0, 1, 2, "Intro", "Cells divide.", 3, t.now())
                .withEmbedding(new float[] {0.6f, 0.8f}, "test-embedder", t.now());
        repos.chunks().insertAll(List.of(chunk));

        Chunk stored = repos.chunks().find(chunk.id()).orElseThrow();
        assertThat(stored).isEqualTo(chunk);
        assertThat(stored.embedding()).containsExactly(0.6f, 0.8f);
        assertThat(repos.chunks().listByIds(List.of(chunk.id(), "missing"))).containsExactly(chunk);
    }

    @Test
    void listsByDocumentModuleAndCourseInReadingOrder() {
        Course course = t.course("C");
        CourseModule module = t.module(course, "M");
        Document document = t.document(course, module, "Doc");
        Chunk second = t.chunk(document, 1, "second");
        Chunk first = t.chunk(document, 0, "first");

        assertThat(repos.chunks().listByDocument(document.id())).containsExactly(first, second);
        assertThat(repos.chunks().listByCourse(course.id())).containsExactly(first, second);
        assertThat(repos.chunks().listByModule(module.id())).isEmpty();
        repos.documents().markReady(document.id());
        assertThat(repos.chunks().listByModule(module.id())).containsExactly(first, second);
        assertThat(repos.chunks().countByDocument(document.id())).isEqualTo(2);
    }

    @Test
    void storesEmbeddingsLater() {
        Document document = t.document(t.course("C"), null, "Doc");
        Chunk a = t.chunk(document, 0, "a");
        Chunk b = t.chunk(document, 1, "b");
        assertThat(repos.chunks().listMissingEmbedding(document.id())).containsExactly(a, b);

        repos.chunks().updateEmbeddings(Map.of(a.id(), new float[] {1f, 0f}), "model-x");

        assertThat(repos.chunks().listMissingEmbedding(document.id())).extracting(Chunk::id).containsExactly(b.id());
        Chunk stored = repos.chunks().find(a.id()).orElseThrow();
        assertThat(stored.embedding()).containsExactly(1f, 0f);
        assertThat(stored.embeddingModel()).isEqualTo("model-x");
    }

    @Test
    void streamsIndexableChunksWithTitles() {
        Document ready = t.document(t.course("C"), null, "Ready doc");
        Document pending = t.document(t.course("D"), null, "Pending doc");
        Chunk a = t.chunk(ready, 0, "a");
        t.chunk(ready, 1, "b no embedding");
        Chunk c = t.chunk(pending, 0, "c");
        repos.chunks().updateEmbeddings(Map.of(a.id(), new float[] {1f}, c.id(), new float[] {1f}), "m");
        repos.documents().markReady(ready.id());

        List<ChunkRepository.ChunkWithTitle> seen = repos.chunks().withIndexable("m", stream -> stream.toList());
        long otherModel = repos.chunks().withIndexable("other", stream -> stream.count());
        assertThat(otherModel).isZero();

        assertThat(seen).singleElement().satisfies(row -> {
            assertThat(row.chunk().id()).isEqualTo(a.id());
            assertThat(row.documentTitle()).isEqualTo("Ready doc");
        });
        assertThat(repos.chunks().listWithTitles(List.of(c.id()))).singleElement()
                .satisfies(row -> assertThat(row.documentTitle()).isEqualTo("Pending doc"));
    }

    @Test
    void findsChunksWithoutAnEmbeddingFromTheCurrentEmbedder() {
        Document document = t.document(t.course("C"), null, "Doc");
        Chunk a = t.chunk(document, 0, "a");
        Chunk b = t.chunk(document, 1, "b");
        Chunk c = t.chunk(document, 2, "c");
        repos.chunks().updateEmbeddings(Map.of(a.id(), new float[] {1f}), "old");
        repos.chunks().updateEmbeddings(Map.of(b.id(), new float[] {1f}), "new");

        assertThat(repos.chunks().listWithStaleEmbedding("new", 10)).extracting(Chunk::id).containsExactly(a.id(), c.id());
        assertThat(repos.chunks().listWithStaleEmbedding("new", 1)).extracting(Chunk::id).containsExactly(a.id());
        assertThat(repos.chunks().countWithStaleEmbedding("new")).isEqualTo(2);
    }

    @Test
    void deletesByDocument() {
        Document document = t.document(t.course("C"), null, "Doc");
        t.chunk(document, 0, "a");
        t.chunk(document, 1, "b");
        assertThat(repos.chunks().deleteByDocument(document.id())).isEqualTo(2);
        assertThat(repos.chunks().listByDocument(document.id())).isEmpty();
    }

    @Test
    void replacingADocumentsChunksLinksItemsToTheChunkCoveringTheirPage() {
        Course course = t.course("C");
        Document document = t.document(course, null, "Doc");
        Document other = t.document(course, null, "Other");
        Chunk one = chunk(document, 0, 1, 1, "one");
        Chunk twoThree = chunk(document, 1, 2, 3, "two three");
        Chunk fourFive = chunk(document, 2, 4, 5, "four five");
        Chunk elsewhere = t.chunk(other, 0, "elsewhere");
        LearningItem fromOne = item(course, one, 1);
        LearningItem fromThree = item(course, twoThree, 3);
        LearningItem withoutPage = item(course, fourFive, null);
        LearningItem fromFive = item(course, fourFive, 5);
        LearningItem fromOther = item(course, elsewhere, 1);
        t.clock.advance(Duration.ofMillis(1));
        List<Chunk> fresh = List.of(unsaved(0, document, 1, 1, "One."), unsaved(1, document, 2, 3, "Two, three."),
                unsaved(2, document, 3, 4, "Three, four."));

        assertThat(repos.chunks().replaceForDocument(document.id(), fresh)).isEqualTo(4);

        assertThat(repos.chunks().listByDocument(document.id())).containsExactlyElementsOf(fresh);
        assertThat(source(fromOne)).isEqualTo(fresh.get(0).id());
        // Page 3 is on two new chunks; the one sharing more pages with the old chunk wins.
        assertThat(source(fromThree)).isEqualTo(fresh.get(1).id());
        // Without a page, the first page of the old chunk counts.
        assertThat(source(withoutPage)).isEqualTo(fresh.get(2).id());
        // Page 5 is on no new chunk; the nearest one is used.
        assertThat(source(fromFive)).isEqualTo(fresh.get(2).id());
        assertThat(source(fromOther)).isEqualTo(elsewhere.id());
        assertThat(repos.items().get(fromFive.id())).satisfies(item -> {
            assertThat(item.sourcePage()).isEqualTo(5);
            assertThat(item.updatedAt()).isEqualTo(fromFive.updatedAt());
        });
        assertThat(repos.chunks().listByDocument(other.id())).containsExactly(elsewhere);

        assertThat(repos.chunks().replaceForDocument(document.id(), List.of())).isZero();
        assertThat(repos.chunks().countByDocument(document.id())).isZero();
        assertThat(source(fromOne)).isNull();
        assertThat(source(fromOther)).isEqualTo(elsewhere.id());
    }

    @Test
    void replacingRefusesChunksOfAnotherDocument() {
        Course course = t.course("C");
        Document document = t.document(course, null, "Doc");
        Chunk kept = t.chunk(document, 0, "kept");
        Document other = t.document(course, null, "Other");

        assertThatThrownBy(() -> repos.chunks().replaceForDocument(document.id(), List.of(unsaved(0, other, 1, 1, "x"))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repos.chunks().listByDocument(document.id())).containsExactly(kept);
    }

    @Test
    void chunksAreOutdatedOnceAPageChangesAfterThem() {
        Document document = t.document(t.course("C"), null, "Doc");
        repos.documents().upsertPage(new DocumentPage(document.id(), 1, "Teh cell", PageSource.AI_READ, false, null, true,
                t.now(), t.now()));
        assertThat(repos.chunks().isOutdated(document.id())).isFalse();

        t.clock.advance(Duration.ofMillis(1));
        t.chunk(document, 0, "Teh cell");
        repos.documents().markPagesReviewed(document.id(), List.of(1));
        assertThat(repos.chunks().isOutdated(document.id())).isFalse();

        t.clock.advance(Duration.ofMillis(1));
        repos.documents().updatePageText(document.id(), 1, "The cell", true);
        assertThat(repos.chunks().isOutdated(document.id())).isTrue();

        t.clock.advance(Duration.ofMillis(1));
        repos.chunks().replaceForDocument(document.id(), List.of(unsaved(0, document, 1, 1, "The cell")));
        assertThat(repos.chunks().isOutdated(document.id())).isFalse();
    }

    /** A stored chunk covering pages {@code first}-{@code last}. */
    private Chunk chunk(Document document, int ordinal, int first, int last, String text) {
        Chunk chunk = unsaved(ordinal, document, first, last, text);
        repos.chunks().insert(chunk);
        return chunk;
    }

    /** A chunk covering pages {@code first}-{@code last}, not stored. */
    private Chunk unsaved(int ordinal, Document document, int first, int last, String text) {
        return Chunk.create(document.id(), document.courseId(), document.moduleId(), ordinal, first, last, null, text,
                text.length() / 4, t.now());
    }

    private LearningItem item(Course course, Chunk source, Integer page) {
        LearningItem item = LearningItem.draft(course.id(), null, ItemType.FLASHCARD, "What is on page " + page + "?",
                "This", "", List.of(), Difficulty.MEDIUM, source.id(), page, ItemOrigin.AI, t.now());
        repos.items().insert(item);
        return item;
    }

    private String source(LearningItem item) {
        return repos.items().get(item.id()).sourceChunkId();
    }
}
