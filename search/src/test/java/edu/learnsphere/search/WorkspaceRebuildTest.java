package edu.learnsphere.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import edu.learnsphere.ai.fake.FakeAi;
import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.service.ProfileContext;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.impl.WorkspaceImpl;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.core.spi.Chunker;
import edu.learnsphere.core.spi.PageRenderer;
import edu.learnsphere.core.spi.PassageLocator;
import edu.learnsphere.core.spi.PdfInspector;
import edu.learnsphere.core.spi.PdfTextExtractor;
import edu.learnsphere.core.spi.SearchHit;
import edu.learnsphere.core.testing.TestDb;
import edu.learnsphere.core.util.Ids;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Lucene index inside a real workspace: opened by the factory and filled by the workspace's rebuild job. */
class WorkspaceRebuildTest {

    @TempDir
    Path dir;

    private TestDb t;
    private EventBus events;
    private ProfileContext context;
    private Capabilities capabilities;

    @BeforeEach
    void setUp() {
        t = TestDb.open(dir.resolve("db"));
        events = new EventBus();
        String id = Ids.newId();
        context = new ProfileContext(new ProfileSummary(id, "Ada", 10, false, t.now(), null),
                AppPaths.at(dir.resolve("home")).profile(id), t.repos, events, t.clock);
        capabilities = Capabilities.builder()
                .pdf(mock(PdfInspector.class), mock(PdfTextExtractor.class), mock(Chunker.class), mock(PageRenderer.class),
                        mock(PassageLocator.class))
                .searchIndexFactory(new LuceneSearchIndexFactory())
                .ai(FakeAi.create().capabilities())
                .build();
    }

    @AfterEach
    void tearDown() {
        events.close();
        t.close();
    }

    @Test
    void aNewProfileIndexIsRebuiltFromStoredPassagesAndReopensReady() {
        Course course = t.course("Biology");
        Document document = t.document(course, null, "Cells");
        Chunk osmosis = t.chunk(document, 0, "Osmosis moves water across a membrane.");
        Chunk enzymes = t.chunk(document, 1, "Enzymes speed up chemical reactions.");
        String model = capabilities.embedder().modelName();
        t.repos.chunks().updateEmbeddings(Map.of(
                osmosis.id(), Passages.EMBEDDER.vector(osmosis.text()),
                enzymes.id(), Passages.EMBEDDER.vector(enzymes.text())), model);
        t.repos.documents().markReady(document.id());

        try (WorkspaceImpl workspace = WorkspaceImpl.open(context, capabilities)) {
            await(() -> !workspace.index().needsRebuild());
            await(() -> workspace.jobs().active().isEmpty());

            List<SearchHit> hits = workspace.index().search(Passages.query(course.id(), "osmosis membrane"));
            assertThat(hits.getFirst().chunkId()).isEqualTo(osmosis.id());
            assertThat(hits.getFirst().documentTitle()).isEqualTo("Cells");
            assertThat(workspace.index().size()).isEqualTo(2);
        }
        assertThat(t.repos.jobs().listByStatus(Set.of(JobStatus.DONE)))
                .singleElement().extracting(j -> j.kind()).isEqualTo(JobKind.REBUILD_INDEX);

        try (WorkspaceImpl workspace = WorkspaceImpl.open(context, capabilities)) {
            assertThat(workspace.index().needsRebuild()).isFalse();
            assertThat(workspace.jobs().active()).isEmpty();
            assertThat(workspace.index().search(Passages.query(course.id(), "enzymes")).getFirst().chunkId())
                    .isEqualTo(enzymes.id());
        }
    }

    private static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met in time");
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
