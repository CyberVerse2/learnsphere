package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Document;
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
}
