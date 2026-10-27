package edu.learnsphere.core.service.impl.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.error.UnavailableException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.spi.AiStatus;
import edu.learnsphere.core.spi.ItemDraft;
import edu.learnsphere.core.spi.PageText;
import edu.learnsphere.core.spi.Passage;
import edu.learnsphere.core.spi.TextBlock;
import edu.learnsphere.core.util.CancellationToken;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The pipelines' pure helpers, the study-assistant wait and the concept-origin repository methods. */
class PipelineHelpersTest {

    @TempDir
    Path dir;

    // ---- PipelineText --------------------------------------------------------------------------------

    @Test
    void turnsReadMarkdownIntoHeadingAwareBlocks() {
        PageText page = PipelineText.fromMarkdown(4, """
                ## Cell cycle

                Cells divide
                in two phases.
                - Interphase
                1. Mitosis

                # Summary
                """);

        assertThat(page.pageNumber()).isEqualTo(4);
        assertThat(page.blocks()).extracting(TextBlock::text)
                .containsExactly("Cell cycle", "Cells divide in two phases.", "- Interphase", "1. Mitosis", "Summary");
        assertThat(page.blocks().get(0).bold()).isTrue();
        assertThat(page.blocks().get(0).fontSize()).isGreaterThan(PipelineText.BODY_FONT_SIZE);
        assertThat(page.blocks().get(4).fontSize()).isGreaterThan(page.blocks().get(0).fontSize());
        assertThat(page.blocks().get(1).lineCount()).isEqualTo(2);
        assertThat(PipelineText.fromMarkdown(1, null).blocks()).isEmpty();
    }

    @Test
    void addsFigureNotesAsTheirOwnLines() {
        PageText page = PageText.ofPlainText(2, "Text.");

        assertThat(PipelineText.withFigures(page, null)).isSameAs(page);
        assertThat(PipelineText.withFigures(page, "").blocks()).hasSize(1);
        assertThat(PipelineText.withFigures(page, "A graph.\n\nFigure: A table.").blocks()).extracting(TextBlock::text)
                .containsExactly("Text.", "Figure: A graph.", "Figure: A table.");
    }

    @Test
    void shortensAtWordsAndPreviewsTheFirstLineWithoutMarkers() {
        assertThat(PipelineText.shorten("  one   two  ", 20)).isEqualTo("one two");
        assertThat(PipelineText.shorten("alpha beta gamma delta", 12)).isEqualTo("alpha beta…");
        assertThat(PipelineText.shorten("x".repeat(30), 10)).isEqualTo("x".repeat(9) + "…");
        assertThat(PipelineText.preview("\n\nCells divide [1] in two [2][3].\nMore", 100)).isEqualTo("Cells divide in two.");
        assertThat(PipelineText.preview(null, 10)).isEmpty();
        assertThat(PipelineText.visibleChars(" a b\n")).isEqualTo(2);
    }

    @Test
    void measuresCosineAndSpreadsPicksEvenly() {
        assertThat(PipelineText.cosine(new float[] {1, 0}, new float[] {1, 0})).isEqualTo(1.0);
        assertThat(PipelineText.cosine(new float[] {1, 0}, new float[] {0, 1})).isZero();
        assertThat(PipelineText.cosine(new float[] {0, 0}, new float[] {1, 1})).isZero();
        assertThat(PipelineText.cosine(new float[] {1}, new float[] {1, 1})).isZero();
        assertThat(PipelineText.evenlySpaced(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9), 3)).containsExactly(1, 5, 9);
        assertThat(PipelineText.evenlySpaced(List.of(1, 2, 3), 5)).containsExactly(1, 2, 3);
        assertThat(PipelineText.evenlySpaced(List.of(1, 2, 3), 1)).containsExactly(2);
    }

    // ---- ItemDrafts ----------------------------------------------------------------------------------

    @Test
    void keepsDraftsThatFollowTheirTypesRules() {
        List<Passage> passages = List.of(new Passage(1, "c1", "d", "Notes", 3, 3, null, "Text", 0));
        List<Chunk> chunks = List.of(new Chunk("c1", "d", "course", null, 0, 3, 3, null, "Text", 1, null, null, 0, 0));
        List<ItemDraft> drafts = List.of(
                new ItemDraft(ItemType.MCQ, " Which? ", " B ", "", List.of("A", " a ", "b", "", "C", "D", "E"), null, 1,
                        List.of(" Cells ", "")),
                new ItemDraft(ItemType.MCQ, "Which?", "B", "", List.of("b", "A"), null, 1, List.of()),
                new ItemDraft(ItemType.CLOZE, "____ and ____", "x", "", List.of(), null, 1, List.of()),
                new ItemDraft(ItemType.CLOZE, "Cells ____.", "divide", "", List.of(), null, 1, List.of()),
                new ItemDraft(ItemType.TRUE_FALSE, "Cells divide.", "False", "", List.of(), null, 1, List.of()),
                new ItemDraft(ItemType.FLASHCARD, "  ", "x", "", List.of(), null, 1, List.of()),
                new ItemDraft(ItemType.SHORT_ANSWER, "Why?", "Because.", "", List.of(), null, 2, List.of()),
                new ItemDraft(ItemType.FLASHCARD, "Front", "Back", "", List.of(), null, 1, List.of()));

        List<ItemDrafts.Candidate> kept = ItemDrafts.candidates(drafts, passages, chunks,
                Set.of(ItemType.MCQ, ItemType.CLOZE, ItemType.TRUE_FALSE, ItemType.SHORT_ANSWER));

        assertThat(kept).extracting(ItemDrafts.Candidate::type)
                .containsExactly(ItemType.MCQ, ItemType.CLOZE, ItemType.TRUE_FALSE);
        assertThat(kept.get(0).prompt()).isEqualTo("Which?");
        assertThat(kept.get(0).answer()).isEqualTo("B");
        assertThat(kept.get(0).distractors()).containsExactly("A", "C", "D", "E");
        assertThat(kept.get(0).conceptNames()).containsExactly("Cells");
        assertThat(kept.get(0).chunk().id()).isEqualTo("c1");
        assertThat(kept.get(0).passage().pageStart()).isEqualTo(3);
        assertThat(kept.get(2).answer()).isEqualTo("false");
        assertThatThrownBy(() -> ItemDrafts.candidates(drafts, passages, List.of(), Set.of(ItemType.MCQ)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- BackgroundAi --------------------------------------------------------------------------------

    @Test
    void retriesOnceTheAssistantIsReadyAndGivesUpWhenItCannotRun() {
        try (PipelineFixture f = new PipelineFixture(dir)) {
            BackgroundAi ai = new BackgroundAi(f.assistant, Duration.ofSeconds(5));
            AtomicInteger calls = new AtomicInteger();
            f.ai.runtime().setStatus(new AiStatus(AiStatus.State.STARTING, null, null));
            Thread starter = Thread.ofVirtual().start(() -> {
                PipelineFixture.await(() -> calls.get() > 0, "the first request");
                f.ai.runtime().start(null);
            });

            String answer = ai.call(() -> {
                if (calls.incrementAndGet() == 1) {
                    throw new UnavailableException(UserMessage.ASSISTANT_STARTING, "starting");
                }
                return "ok";
            }, CancellationToken.create());

            assertThat(answer).isEqualTo("ok");
            assertThat(calls).hasValue(2);
            starter.interrupt();

            f.ai.runtime().setStatus(new AiStatus(AiStatus.State.UNAVAILABLE, null, null));
            AtomicInteger refused = new AtomicInteger();
            assertThatThrownBy(() -> ai.call(() -> {
                refused.incrementAndGet();
                throw new UnavailableException(UserMessage.ASSISTANT_UNAVAILABLE, "gone");
            }, CancellationToken.create())).isInstanceOf(UnavailableException.class);
            assertThat(refused).hasValue(1);
        }
    }

    @Test
    void stopsWaitingWhenTheJobIsCancelled() {
        try (PipelineFixture f = new PipelineFixture(dir)) {
            f.ai.runtime().setStatus(new AiStatus(AiStatus.State.STARTING, null, null));
            BackgroundAi ai = new BackgroundAi(f.assistant, Duration.ofMinutes(5));
            CancellationToken token = CancellationToken.create();
            Thread.ofVirtual().start(() -> {
                BackgroundAi.sleep(Duration.ofMillis(50), CancellationToken.NONE);
                token.cancel();
            });

            assertThatThrownBy(() -> ai.call(() -> {
                throw new UnavailableException(UserMessage.ASSISTANT_STARTING, "starting");
            }, token)).isInstanceOf(CancellationException.class);
        }
    }

    // ---- concept origin ------------------------------------------------------------------------------

    @Test
    void recordsWhoWroteEachConceptAndRemovesOnlyUnusedExtractedOnes() {
        try (PipelineFixture f = new PipelineFixture(dir)) {
            Course course = f.t.course("Biology");
            CourseModule module = f.t.module(course, "Cells");
            CourseModule other = f.t.module(course, "Genes");
            Concept kept = f.t.concept(course, module, "Kept");
            Concept tagged = f.t.concept(course, module, "Tagged");
            Concept unused = f.t.concept(course, module, "Unused");
            Concept manual = f.t.concept(course, module, "Manual");
            Concept elsewhere = f.t.concept(course, other, "Elsewhere");
            List<String> extracted = List.of(kept.id(), tagged.id(), unused.id(), elsewhere.id());

            assertThat(f.repos.concepts().setOrigin(extracted, ItemOrigin.AI)).isEqualTo(4);
            assertThat(f.repos.concepts().setOrigin(extracted, ItemOrigin.AI)).isZero();
            assertThat(f.repos.concepts().setOrigin(List.of(), ItemOrigin.AI)).isZero();
            assertThat(f.repos.concepts().extractedIds(module.id()))
                    .containsExactlyInAnyOrder(kept.id(), tagged.id(), unused.id());
            LearningItem item = f.t.item(course, module, ItemType.FLASHCARD, ItemStatus.DRAFT, "Q");
            f.repos.items().setConcepts(item.id(), List.of(tagged.id()));

            assertThat(f.repos.concepts().deleteUnusedExtracted(module.id(), List.of(kept.id()))).isEqualTo(1);

            assertThat(f.repos.concepts().listByModule(module.id())).extracting(Concept::id)
                    .containsExactlyInAnyOrder(kept.id(), tagged.id(), manual.id());
            assertThat(f.repos.concepts().find(elsewhere.id())).isPresent();
            assertThat(f.repos.concepts().deleteUnusedExtracted(module.id(), List.of())).isEqualTo(1);
            assertThat(f.repos.concepts().find(kept.id())).isEmpty();
        }
    }
}
