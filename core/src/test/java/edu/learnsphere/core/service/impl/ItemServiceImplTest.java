package edu.learnsphere.core.service.impl;

import static edu.learnsphere.core.service.impl.CourseServiceImplTest.assertMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.ItemsChanged;
import edu.learnsphere.core.model.Attempt;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.SessionRecord;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.service.FlagSummary;
import edu.learnsphere.core.service.ItemContent;
import edu.learnsphere.core.service.ItemCounts;
import edu.learnsphere.core.service.ItemDetail;
import edu.learnsphere.core.service.ItemFilter;
import edu.learnsphere.core.service.ItemStats;
import edu.learnsphere.core.service.ItemSummary;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.util.Ids;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ItemServiceImplTest {

    @TempDir
    Path dir;

    private ServiceHarness h;
    private ItemServiceImpl items;
    private Course course;
    private CourseModule cells;

    @BeforeEach
    void setUp() {
        h = new ServiceHarness(dir);
        items = new ItemServiceImpl(h.t.repos, h.events, h.t.clock, h.jobs);
        course = h.t.enrolledCourse("Biology");
        cells = h.t.module(course, "Cells");
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void createsADraftTaggedWithConceptsOfTheCourse() {
        Concept membrane = h.t.concept(course, cells, "Membrane");

        LearningItem item = items.create(course.id(), cells.id(), new ItemContent(ItemType.MCQ, " What surrounds a cell? ",
                "The membrane", "", List.of(" The wall ", "The nucleus"), Difficulty.EASY, Set.of(membrane.id())));

        assertThat(item.status()).isEqualTo(ItemStatus.DRAFT);
        assertThat(item.origin()).isEqualTo(ItemOrigin.MANUAL);
        assertThat(item.prompt()).isEqualTo("What surrounds a cell?");
        assertThat(item.distractors()).containsExactly("The wall", "The nucleus");
        assertThat(h.t.repos.items().get(item.id())).isEqualTo(item);
        assertThat(h.t.repos.items().conceptIds(item.id())).containsExactly(membrane.id());
        assertThat(h.events(ItemsChanged.class))
                .containsExactly(new ItemsChanged(course.id(), cells.id(), Set.of(item.id()), ChangeType.CREATED));
    }

    @Test
    void checksContentAgainstTheConventionsOfEachKindOfQuestion() {
        assertThat(create(new ItemContent(ItemType.TRUE_FALSE, "Cells divide.", " True ", "", List.of("x"), null, null)))
                .satisfies(i -> {
                    assertThat(i.answer()).isEqualTo("true");
                    assertThat(i.distractors()).isEmpty();
                });
        assertThat(create(new ItemContent(ItemType.CLOZE, "The ____ holds the DNA.", "nucleus", "", null, null, null))
                .answer()).isEqualTo("nucleus");

        assertMessage(() -> create(ItemContent.flashcard(" ", "back")), UserMessage.of("Write the front of the card."));
        assertMessage(() -> create(ItemContent.flashcard("front", "")), UserMessage.of("Write the back of the card."));
        assertMessage(() -> create(mcq(List.of("Only one"))), ItemValidator.OPTION_COUNT);
        assertMessage(() -> create(mcq(List.of("a", "b", "c", "d", "e"))), ItemValidator.OPTION_COUNT);
        assertMessage(() -> create(mcq(List.of("Wall", " "))), ItemValidator.OPTION_BLANK);
        assertMessage(() -> create(mcq(List.of("Wall", "wall"))), ItemValidator.OPTIONS_DIFFERENT);
        assertMessage(() -> create(mcq(List.of("Wall", "the membrane"))), ItemValidator.OPTIONS_DIFFERENT);
        assertMessage(() -> create(new ItemContent(ItemType.TRUE_FALSE, "Cells divide.", "Maybe", "", null, null, null)),
                ItemValidator.TRUE_OR_FALSE);
        assertMessage(() -> create(new ItemContent(ItemType.CLOZE, "No blank here.", "x", "", null, null, null)),
                ItemValidator.ONE_BLANK);
        assertMessage(() -> create(new ItemContent(ItemType.CLOZE, "____ and ____.", "x", "", null, null, null)),
                ItemValidator.ONE_BLANK);
        assertMessage(() -> create(ItemContent.flashcard("x".repeat(2_001), "back")), ItemValidator.TOO_LONG);

        Course other = h.t.course("Chemistry");
        Concept atom = h.t.concept(other, null, "Atom");
        assertMessage(() -> create(new ItemContent(ItemType.FLASHCARD, "front", "back", "", null, null, Set.of(atom.id()))),
                ItemServiceImpl.CONCEPT_NOT_IN_COURSE);
        assertMessage(() -> items.create(course.id(), h.t.module(other, "Atoms").id(), ItemContent.flashcard("f", "b")),
                UserMessage.MODULE_NOT_FOUND);
        assertThat(h.t.repos.items().listByCourse(course.id())).hasSize(2);
    }

    @Test
    void editingTheQuestionClearsItsSupportCheckButKeepsItsStatus() {
        LearningItem item = h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is a cell?");
        h.t.repos.items().setSupport(item.id(), SupportStatus.SUPPORTED, "Page 2");
        Concept membrane = h.t.concept(course, cells, "Membrane");

        LearningItem explained = items.edit(item.id(), new ItemContent(ItemType.FLASHCARD, "What is a cell?", "answer",
                "The unit of life", null, Difficulty.HARD, Set.of(membrane.id())));
        assertThat(explained.status()).isEqualTo(ItemStatus.APPROVED);
        assertThat(explained.support()).isEqualTo(SupportStatus.SUPPORTED);
        assertThat(explained.supportNote()).isEqualTo("Page 2");
        assertThat(explained.explanation()).isEqualTo("The unit of life");
        assertThat(explained.difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(h.t.repos.items().conceptIds(item.id())).containsExactly(membrane.id());

        LearningItem reworded = items.edit(item.id(), ItemContent.flashcard("What is a living cell?", "answer"));
        assertThat(reworded.status()).isEqualTo(ItemStatus.APPROVED);
        assertThat(reworded.support()).isEqualTo(SupportStatus.UNCHECKED);
        assertThat(reworded.supportNote()).isNull();
        assertThat(h.t.repos.items().conceptIds(item.id())).isEmpty();
    }

    @Test
    void movesStatusesAlongTheReviewFlow() {
        LearningItem draft = h.t.item(course, cells, ItemType.MCQ, ItemStatus.DRAFT, "Which?");
        LearningItem other = h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.DRAFT, "What?");

        items.approve(draft.id());
        assertThat(status(draft)).isEqualTo(ItemStatus.APPROVED);
        assertThatThrownBy(() -> items.reject(draft.id())).isInstanceOf(ConflictException.class);
        items.retire(draft.id());
        items.retire(draft.id());
        assertThat(status(draft)).isEqualTo(ItemStatus.RETIRED);
        assertMessage(() -> items.approve(draft.id()), ItemServiceImpl.ONLY_DRAFTS_APPROVED);
        assertThat(items.approveAll(List.of(draft.id()))).isZero();
        assertThat(status(draft)).isEqualTo(ItemStatus.RETIRED);
        items.returnToDraft(draft.id());
        assertThat(status(draft)).isEqualTo(ItemStatus.DRAFT);

        assertMessage(() -> items.retire(other.id()), ItemServiceImpl.ONLY_APPROVED_RETIRED);
        items.reject(other.id());
        items.reject(other.id());
        assertThat(status(other)).isEqualTo(ItemStatus.REJECTED);
        assertMessage(() -> items.approve(other.id()), ItemServiceImpl.ONLY_DRAFTS_APPROVED);
        assertThat(items.approveAll(List.of(other.id()))).isZero();
        assertThat(status(other)).isEqualTo(ItemStatus.REJECTED);
        assertMessage(() -> items.reject(Ids.newId()), UserMessage.ITEM_NOT_FOUND);

        assertThat(h.events(ItemsChanged.class)).extracting(ItemsChanged::itemIds)
                .containsExactly(Set.of(draft.id()), Set.of(draft.id()), Set.of(draft.id()), Set.of(other.id()));
    }

    @Test
    void approvingNeedsValidContent() {
        LearningItem broken = insert(LearningItem.draft(course.id(), cells.id(), ItemType.MCQ, "Which?", "A", "",
                List.of("B"), Difficulty.MEDIUM, null, null, ItemOrigin.AI, h.t.now()));
        LearningItem fine = h.t.item(course, cells, ItemType.MCQ, ItemStatus.DRAFT, "Which one?");
        LearningItem approved = h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "What?");

        assertMessage(() -> items.approve(broken.id()), ItemValidator.OPTION_COUNT);
        assertThat(items.approveAll(List.of(broken.id(), fine.id(), approved.id(), "missing"))).isEqualTo(1);

        assertThat(status(broken)).isEqualTo(ItemStatus.DRAFT);
        assertThat(status(fine)).isEqualTo(ItemStatus.APPROVED);
        assertThat(items.approveAll(List.of())).isZero();
        assertThat(h.events(ItemsChanged.class))
                .containsExactly(new ItemsChanged(course.id(), null, Set.of(fine.id()), ChangeType.UPDATED));
    }

    @Test
    void questionsOfSharedCoursesCanBeFlaggedButNotChanged() {
        Course shared = h.importedCourse("Geology");
        LearningItem item = h.t.item(shared, null, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is basalt?");

        for (Runnable change : List.<Runnable>of(
                () -> items.create(shared.id(), null, ItemContent.flashcard("f", "b")),
                () -> items.edit(item.id(), ItemContent.flashcard("f", "b")),
                () -> items.approve(item.id()),
                () -> items.approveAll(List.of(item.id())),
                () -> items.retire(item.id()),
                () -> items.returnToDraft(item.id()),
                () -> items.moveToModule(item.id(), null),
                () -> items.delete(item.id()))) {
            assertMessage(change, ServiceSupport.SHARED_COURSE);
        }

        Flag flag = items.addFlag(item.id(), FlagReason.WRONG_ANSWER, " The answer is granite ");
        assertThat(flag.note()).isEqualTo("The answer is granite");
        assertThat(flag.source()).isEqualTo(FlagSource.SELF);
        assertThat(items.flags(shared.id(), false)).extracting(FlagSummary::itemPrompt).containsExactly("What is basalt?");
    }

    @Test
    void listsQuestionsWithTheirConceptsModuleSourceAndFlags() {
        Concept membrane = h.t.concept(course, cells, "Membrane");
        Document document = h.t.document(course, cells, "Cell notes");
        Chunk chunk = h.t.chunk(document, 2, "The membrane surrounds the cell.");
        LearningItem sourced = insert(LearningItem.draft(course.id(), cells.id(), ItemType.FLASHCARD,
                "What surrounds the cell?", "The membrane", "", List.of(), Difficulty.MEDIUM, chunk.id(), 3, ItemOrigin.AI,
                h.t.now()));
        h.t.repos.items().setConcepts(sourced.id(), List.of(membrane.id()));
        items.addFlag(sourced.id(), FlagReason.UNCLEAR, null);
        LearningItem plain = h.t.item(course, null, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is life?");

        List<ItemSummary> all = items.list(ItemFilter.forCourse(course.id()));
        assertThat(all).extracting(ItemSummary::id).containsExactlyInAnyOrder(sourced.id(), plain.id());
        ItemSummary summary = all.stream().filter(s -> s.id().equals(sourced.id())).findFirst().orElseThrow();
        assertThat(summary.conceptNames()).containsExactly("Membrane");
        assertThat(summary.moduleTitle()).isEqualTo("Cells");
        assertThat(summary.sourceDocumentTitle()).isEqualTo("Cell notes");
        assertThat(summary.openFlagCount()).isEqualTo(1);

        assertThat(items.list(ItemFilter.reviewQueue(course.id()))).extracting(ItemSummary::id).containsExactly(sourced.id());
        assertThat(items.list(ItemFilter.forCourse(course.id()).withSearch("life"))).extracting(ItemSummary::id)
                .containsExactly(plain.id());
        assertThat(items.list(ItemFilter.forCourse(course.id()).withFlaggedOnly(true))).extracting(ItemSummary::id)
                .containsExactly(sourced.id());
        assertThat(items.list(ItemFilter.forCourse(course.id()).withConcept(membrane.id()))).hasSize(1);
        assertThatThrownBy(() -> items.list(ItemFilter.forCourse("missing"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theReviewQueueShowsDraftsWithTheirSourcePassageFlagsAndStatistics() {
        Document document = h.t.document(course, cells, "Cell notes");
        Chunk chunk = h.t.chunk(document, 2, "The membrane surrounds the cell.");
        LearningItem first = insert(LearningItem.draft(course.id(), cells.id(), ItemType.FLASHCARD,
                "What surrounds the cell?", "The membrane", "", List.of(), Difficulty.MEDIUM, chunk.id(), 3, ItemOrigin.AI,
                h.t.now()));
        LearningItem second = h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.DRAFT, "What is a cell?");
        h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "Approved");
        h.t.reviewed(first, h.t.now() + 1);
        SessionRecord session = h.t.session(course);
        attempt(first, session, true, 4_000);
        attempt(first, session, false, 2_000);
        Flag resolved = items.addFlag(first.id(), FlagReason.TYPO, "Tyop");
        items.resolveFlag(resolved.id());
        items.addFlag(first.id(), FlagReason.UNCLEAR, "");

        List<ItemDetail> queue = items.reviewQueue(course.id(), cells.id(), 10, 0);

        assertThat(queue).extracting(d -> d.item().id()).containsExactlyInAnyOrder(first.id(), second.id());
        ItemDetail detail = queue.stream().filter(d -> d.item().id().equals(first.id())).findFirst().orElseThrow();
        assertThat(detail.source().ref().documentId()).isEqualTo(document.id());
        assertThat(detail.source().ref().documentTitle()).isEqualTo("Cell notes");
        assertThat(detail.source().ref().pageStart()).isEqualTo(3);
        assertThat(detail.source().text()).isEqualTo("The membrane surrounds the cell.");
        assertThat(detail.flags()).hasSize(2);
        ItemStats stats = detail.stats();
        assertThat(stats.attempts()).isEqualTo(2);
        assertThat(stats.correct()).isEqualTo(1);
        assertThat(stats.accuracy()).isEqualTo(0.5);
        assertThat(stats.phase()).isEqualTo(ReviewPhase.REVIEW);
        assertThat(stats.openFlags()).isEqualTo(1);
        assertThat(stats.classLearners()).isZero();

        assertThat(items.reviewQueue(course.id(), null, 1, 1)).hasSize(1);
        assertThat(items.reviewQueue(course.id(), null, 10, 5)).isEmpty();
        assertThatThrownBy(() -> items.reviewQueue(course.id(), null, 0, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThat(items.get(second.id()).stats().attempts()).isZero();
        assertThat(items.get(second.id()).source()).isNull();
    }

    @Test
    void listsDetailsOfFilteredItemsInAnyStatus() {
        Document document = h.t.document(course, cells, "Cell notes");
        Chunk chunk = h.t.chunk(document, 2, "The membrane surrounds the cell.");
        LearningItem sourced = insert(LearningItem.draft(course.id(), cells.id(), ItemType.FLASHCARD,
                "What surrounds the cell?", "The membrane", "", List.of(), Difficulty.MEDIUM, chunk.id(), 2, ItemOrigin.AI,
                h.t.now()));
        LearningItem approved = h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "Approved");
        h.t.item(course, cells, ItemType.MCQ, ItemStatus.APPROVED, "Another type");
        items.reject(sourced.id());
        items.addFlag(approved.id(), FlagReason.UNCLEAR, "Which cell?");

        List<ItemDetail> flashcards = items.listDetails(ItemFilter.forCourse(course.id()).withTypes(ItemType.FLASHCARD));

        assertThat(flashcards).extracting(d -> d.item().id()).containsExactlyInAnyOrder(sourced.id(), approved.id());
        ItemDetail rejected = flashcards.stream().filter(d -> d.item().id().equals(sourced.id())).findFirst().orElseThrow();
        assertThat(rejected.item().status()).isEqualTo(ItemStatus.REJECTED);
        assertThat(rejected.source().text()).isEqualTo("The membrane surrounds the cell.");
        ItemDetail flagged = flashcards.stream().filter(d -> d.item().id().equals(approved.id())).findFirst().orElseThrow();
        assertThat(flagged.flags()).extracting(Flag::reason).containsExactly(FlagReason.UNCLEAR);
        assertThat(items.listDetails(ItemFilter.forCourse(course.id()).withTypes(ItemType.FLASHCARD).withPage(1, 1)))
                .hasSize(1);
        assertThat(items.listDetails(ItemFilter.forCourse(course.id()).withStatuses(ItemStatus.DRAFT))).isEmpty();
        assertThatThrownBy(() -> items.listDetails(ItemFilter.forCourse("missing"))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void statisticsComeFromTheProgressSourceWhenThereIsOne() {
        LearningItem item = h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.DRAFT, "What is a cell?");
        ItemStats fromProgress = new ItemStats(item.id(), item.type(), item.prompt(), item.status(), 12, 9, 0.75, 0.8,
                3_000L, h.t.now(), null, null, null, 0, 0, 30, 0.6);

        items = new ItemServiceImpl(h.t.repos, h.events, h.t.clock, h.jobs, id -> fromProgress);
        assertThat(items.get(item.id()).stats()).isEqualTo(fromProgress);

        items = new ItemServiceImpl(h.t.repos, h.events, h.t.clock, h.jobs, id -> {
            throw new IllegalStateException("broken");
        });
        assertThat(items.get(item.id()).stats().attempts()).isZero();
    }

    @Test
    void countsQuestionsByStatusSupportAndFlags() {
        LearningItem unsupported = h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.DRAFT, "A");
        h.t.repos.items().setSupport(unsupported.id(), SupportStatus.UNSUPPORTED, "Not on page 2");
        LearningItem flagged = h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "B");
        h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "C");
        h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.RETIRED, "D");
        items.addFlag(flagged.id(), FlagReason.OTHER, null);
        items.addFlag(flagged.id(), FlagReason.TYPO, null);

        assertThat(items.counts(course.id())).isEqualTo(new ItemCounts(1, 2, 0, 1, 1, 1));
    }

    @Test
    void flagsAreListedAndResolved() {
        LearningItem item = h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is a cell?");
        assertMessage(() -> items.addFlag(item.id(), FlagReason.OTHER, "x".repeat(1_001)), ItemServiceImpl.NOTE_TOO_LONG);
        Flag flag = items.addFlag(item.id(), FlagReason.WRONG_ANSWER, "Check page 3");

        assertThat(items.flags(course.id(), false)).extracting(f -> f.flag().id(), FlagSummary::moduleTitle)
                .containsExactly(tuple(flag.id(), "Cells"));
        items.resolveFlag(flag.id());
        items.resolveFlag(flag.id());
        assertThat(items.flags(course.id(), false)).isEmpty();
        assertThat(items.flags(course.id(), true)).extracting(f -> f.flag().isOpen()).containsExactly(false);
        assertMessage(() -> items.resolveFlag(Ids.newId()), UserMessage.NOT_AVAILABLE);
        assertThat(h.events(ItemsChanged.class)).hasSize(2);
    }

    @Test
    void movesQuestionsBetweenModulesOfTheirCourse() {
        LearningItem item = h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is a cell?");
        CourseModule tissues = h.t.module(course, "Tissues");

        items.moveToModule(item.id(), tissues.id());
        assertThat(h.t.repos.items().get(item.id()).moduleId()).isEqualTo(tissues.id());
        items.moveToModule(item.id(), null);
        assertThat(h.t.repos.items().get(item.id()).moduleId()).isNull();
        Course other = h.t.course("Chemistry");
        assertMessage(() -> items.moveToModule(item.id(), h.t.module(other, "Atoms").id()), UserMessage.MODULE_NOT_FOUND);
    }

    @Test
    void deletingAQuestionStopsWorkOnIt() {
        LearningItem item = h.t.item(course, cells, ItemType.FLASHCARD, ItemStatus.DRAFT, "What is a cell?");
        h.jobs.enqueue(JobSpec.of(JobKind.REGENERATE_ITEM, item.id(), null));

        items.delete(item.id());

        assertThat(h.t.repos.items().find(item.id())).isEmpty();
        assertThat(h.jobs(JobKind.REGENERATE_ITEM, JobStatus.CANCELLED)).hasSize(1);
        assertThat(h.events(ItemsChanged.class))
                .containsExactly(new ItemsChanged(course.id(), cells.id(), Set.of(item.id()), ChangeType.DELETED));
    }

    private LearningItem create(ItemContent content) {
        return items.create(course.id(), cells.id(), content);
    }

    private static ItemContent mcq(List<String> distractors) {
        return new ItemContent(ItemType.MCQ, "What surrounds a cell?", "The membrane", "", distractors, null, null);
    }

    private LearningItem insert(LearningItem item) {
        h.t.repos.items().insert(item);
        return item;
    }

    private ItemStatus status(LearningItem item) {
        return h.t.repos.items().get(item.id()).status();
    }

    private void attempt(LearningItem item, SessionRecord session, boolean correct, long durationMs) {
        h.t.repos.sessions().insertAttempt(new Attempt(Ids.newId(), item.id(), session.id(), "", correct, correct ? 1 : 0,
                correct ? Rating.GOOD : Rating.AGAIN, null, false, durationMs, GradedBy.RULE, null, false, h.t.now(),
                h.t.now()));
    }
}
