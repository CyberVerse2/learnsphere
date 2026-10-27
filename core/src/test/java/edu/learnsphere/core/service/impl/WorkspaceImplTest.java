package edu.learnsphere.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.events.SettingChanged;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.service.AssistantState;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.DocumentSummary;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.ModelOption;
import edu.learnsphere.core.service.ProfileContext;
import edu.learnsphere.core.service.ProfileSummary;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.spi.AiCapabilities;
import edu.learnsphere.core.spi.AiRuntime;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.Capabilities;
import edu.learnsphere.core.spi.Chunker;
import edu.learnsphere.core.spi.ConceptExtractor;
import edu.learnsphere.core.spi.Embedder;
import edu.learnsphere.core.spi.FigureDescriber;
import edu.learnsphere.core.spi.GroundedAnswerer;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.spi.ItemGenerator;
import edu.learnsphere.core.spi.ModelChoice;
import edu.learnsphere.core.spi.PageReader;
import edu.learnsphere.core.spi.PageRenderer;
import edu.learnsphere.core.spi.PassageLocator;
import edu.learnsphere.core.spi.PdfInspector;
import edu.learnsphere.core.spi.PdfTextExtractor;
import edu.learnsphere.core.spi.Reranker;
import edu.learnsphere.core.spi.SearchIndex;
import edu.learnsphere.core.spi.SearchIndexFactory;
import edu.learnsphere.core.spi.ShortAnswerGrader;
import edu.learnsphere.core.spi.SupportChecker;
import edu.learnsphere.core.testing.TestDb;
import edu.learnsphere.core.util.Ids;
import edu.learnsphere.core.util.Subscription;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceImplTest {

    @TempDir
    Path dir;

    private TestDb t;
    private EventBus events;
    private ProfileContext context;
    private final AiRuntime runtime = mock(AiRuntime.class);
    private final Embedder embedder = mock(Embedder.class);
    private final SearchIndex index = mock(SearchIndex.class);
    private final List<List<String>> rebuilt = new CopyOnWriteArrayList<>();
    private Capabilities capabilities;

    @BeforeEach
    void setUp() {
        t = TestDb.open(dir.resolve("db"));
        events = new EventBus();
        String id = Ids.newId();
        context = new ProfileContext(new ProfileSummary(id, "Ada", 10, false, t.now(), null),
                AppPaths.at(dir.resolve("home")).profile(id), t.repos, events, t.clock);

        when(embedder.dimensions()).thenReturn(2);
        when(embedder.modelName()).thenReturn("embed-v1");
        when(runtime.status()).thenReturn(new AiStatus(AiStatus.State.READY, ModelChoice.CHAT_4B, null));
        when(runtime.addListener(any())).thenReturn(Subscription.NONE);
        when(runtime.availableModels()).thenReturn(Set.of(ModelChoice.CHAT_4B));
        when(runtime.recommendedModel()).thenReturn(ModelChoice.CHAT_4B);
        when(index.embeddingModel()).thenReturn("embed-v1");
        org.mockito.Mockito.doAnswer(call -> {
            Stream<IndexedChunk> chunks = call.getArgument(0);
            rebuilt.add(chunks.map(IndexedChunk::chunkId).toList());
            return null;
        }).when(index).rebuild(any());

        SearchIndexFactory factory = (folder, dimensions, model) -> {
            assertThat(folder).isEqualTo(context.paths().indexDir());
            assertThat(dimensions).isEqualTo(2);
            assertThat(model).isEqualTo("embed-v1");
            return index;
        };
        capabilities = Capabilities.builder()
                .pdf(mock(PdfInspector.class), mock(PdfTextExtractor.class), mock(Chunker.class), mock(PageRenderer.class),
                        mock(PassageLocator.class))
                .searchIndexFactory(factory)
                .ai(new AiCapabilities(runtime, embedder, mock(Reranker.class), mock(GroundedAnswerer.class),
                        mock(ConceptExtractor.class), mock(ItemGenerator.class), mock(SupportChecker.class),
                        mock(ShortAnswerGrader.class), mock(PageReader.class), mock(FigureDescriber.class)))
                .build();
    }

    @AfterEach
    void tearDown() {
        events.close();
        t.close();
    }

    @Test
    void rebuildsAnIndexThatNeedsItFromTheStoredPassages() {
        Course course = t.course("Biology");
        Document document = t.document(course, null, "Cells");
        Chunk chunk = t.chunk(document, 0, "Cells are the unit of life.");
        t.chunk(document, 1, "Not embedded yet.");
        t.repos.chunks().updateEmbeddings(Map.of(chunk.id(), new float[] {1f, 0f}), "embed-v1");
        t.repos.documents().markReady(document.id());
        when(index.needsRebuild()).thenReturn(true);

        try (WorkspaceImpl workspace = WorkspaceImpl.open(context, capabilities)) {
            JobQueueImplTest.await(() -> !rebuilt.isEmpty());
            assertThat(rebuilt).containsExactly(List.of(chunk.id()));
            JobQueueImplTest.await(() -> workspace.jobs().active().isEmpty());
            assertThat(t.repos.jobs().listByStatus(Set.of(JobStatus.DONE)))
                    .singleElement().extracting(j -> j.kind()).isEqualTo(JobKind.REBUILD_INDEX);
            verify(runtime).start(ModelChoice.AUTO);
        }
        verify(runtime).stop();
        verify(index).close();
    }

    @Test
    void leavesAnUpToDateIndexAlone() {
        when(index.needsRebuild()).thenReturn(false);
        try (WorkspaceImpl workspace = WorkspaceImpl.open(context, capabilities)) {
            assertThat(workspace.jobs().active()).extracting(JobUpdate::kind).doesNotContain(JobKind.REBUILD_INDEX);
        }
        verify(index, never()).rebuild(any());
    }

    @Test
    void storesSettingsAndAnnouncesChanges() {
        List<String> changed = new CopyOnWriteArrayList<>();
        events.subscribe(SettingChanged.class, e -> changed.add(e.key()));
        try (WorkspaceImpl workspace = WorkspaceImpl.open(context, capabilities)) {
            workspace.settings().set(SettingKeys.SESSION_SIZE, 30);
            assertThat(workspace.settings().current().sessionSize()).isEqualTo(30);
            assertThatThrownBy(() -> workspace.settings().set(SettingKeys.SESSION_SIZE, 1))
                    .isInstanceOf(InvalidInputException.class);
            workspace.settings().reset(SettingKeys.SESSION_SIZE);
            assertThat(workspace.settings().get(SettingKeys.SESSION_SIZE)).isEqualTo(20);
        }
        events.awaitIdle(Duration.ofSeconds(5));
        assertThat(changed).containsExactly(SettingKeys.SESSION_SIZE.key(), SettingKeys.SESSION_SIZE.key());
    }

    @Test
    void reportsTheAssistantAndRestartsOnlyWhenTheModelChanges() {
        try (WorkspaceImpl workspace = WorkspaceImpl.open(context, capabilities)) {
            assertThat(workspace.assistant().status().state()).isEqualTo(AssistantState.READY);
            assertThat(workspace.assistant().modelOptions()).extracting(ModelOption::choice)
                    .containsExactly(ModelChoice.AUTO, ModelChoice.CHAT_4B, ModelChoice.CHAT_9B);

            workspace.assistant().setModelChoice(ModelChoice.CHAT_4B);
            verify(runtime, never()).start(ModelChoice.CHAT_4B);
            workspace.assistant().setModelChoice(ModelChoice.CHAT_9B);
            verify(runtime).start(ModelChoice.CHAT_9B);
            assertThat(workspace.settings().get(SettingKeys.MODEL_CHOICE)).isEqualTo(ModelChoice.CHAT_9B);
        }
    }

    @Test
    void createsCoursesAndQueuesDocumentsWhoseImportWasInterrupted() {
        Course course = t.course("Biology");
        Document waiting = t.document(course, null, "Cells");
        Document ready = t.document(course, null, "Tissues");
        t.repos.documents().markReady(ready.id());

        try (WorkspaceImpl workspace = WorkspaceImpl.open(context, capabilities)) {
            assertThat(workspace.jobs().active())
                    .extracting(JobUpdate::kind, JobUpdate::subjectId)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(JobKind.INGEST_DOCUMENT, waiting.id()));

            Course created = workspace.courses().create(CourseDraft.titled("Chemistry"));
            workspace.courses().addModule(created.id(), "Atoms");
            assertThat(workspace.courses().get(created.id()).modules()).extracting(m -> m.title())
                    .containsExactly("Atoms");
            assertThat(workspace.documents().list(course.id())).extracting(DocumentSummary::title)
                    .containsExactlyInAnyOrder("Cells", "Tissues");
            assertThat(workspace.items().counts(created.id()).total()).isZero();
        }
    }
}
