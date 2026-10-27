package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.model.SupportStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LearningItemRepositoryTest extends RepositoryTestBase {

    @Test
    void roundTripsEveryField() {
        Course course = t.course("C");
        CourseModule module = t.module(course, "M");
        Document document = t.document(course, module, "Doc");
        Chunk chunk = t.chunk(document, 0, "Mitochondria make ATP.");
        LearningItem item = LearningItem.draft(course.id(), module.id(), ItemType.MCQ, "What makes ATP?", "Mitochondria",
                "Page 1 says so.", List.of("Ribosomes", "Nucleus", "Golgi"), Difficulty.HARD, chunk.id(), 1,
                ItemOrigin.AI, t.now());
        repos.items().insert(item);

        assertThat(repos.items().get(item.id())).isEqualTo(item);
        assertThat(repos.items().listBySourceChunk(chunk.id())).containsExactly(item);
    }

    @Test
    void updatesContentAndStatus() {
        Course course = t.course("C");
        LearningItem item = t.item(course, null, ItemType.FLASHCARD, ItemStatus.DRAFT, "Front");
        LearningItem edited = new LearningItem(item.id(), item.courseId(), null, ItemType.SHORT_ANSWER, "Explain",
                "Because", "Why", List.of(), Difficulty.EASY, null, null, ItemOrigin.MANUAL, ItemStatus.APPROVED,
                SupportStatus.SUPPORTED, "Matches page 2", item.createdAt(), item.updatedAt());

        assertThat(repos.items().update(edited)).isTrue();
        assertThat(repos.items().get(item.id())).usingRecursiveComparison().ignoringFields("updatedAt").isEqualTo(edited);

        assertThat(repos.items().setStatus(item.id(), ItemStatus.RETIRED)).isTrue();
        assertThat(repos.items().setSupport(item.id(), SupportStatus.UNSUPPORTED, "Not on the page")).isTrue();
        LearningItem stored = repos.items().get(item.id());
        assertThat(stored.status()).isEqualTo(ItemStatus.RETIRED);
        assertThat(stored.support()).isEqualTo(SupportStatus.UNSUPPORTED);
        assertThat(stored.supportNote()).isEqualTo("Not on the page");
    }

    @Test
    void filtersWithItemQuery() {
        Course course = t.course("C");
        CourseModule m1 = t.module(course, "M1");
        CourseModule m2 = t.module(course, "M2");
        LearningItem mcq = t.item(course, m1, ItemType.MCQ, ItemStatus.DRAFT, "Which organelle?");
        LearningItem card = t.item(course, m2, ItemType.FLASHCARD, ItemStatus.APPROVED, "100% pure");
        LearningItem cloze = t.item(course, m2, ItemType.CLOZE, ItemStatus.DRAFT, "The ____ divides");
        Concept concept = t.concept(course, m2, "Division");
        repos.items().setConcepts(cloze.id(), List.of(concept.id()));
        repos.flags().insert(Flag.open(card.id(), FlagReason.TYPO, "", FlagSource.SELF, t.now()));
        t.item(t.course("Other"), null, ItemType.MCQ, ItemStatus.DRAFT, "elsewhere");

        ItemQuery base = ItemQuery.forCourse(course.id());
        assertThat(repos.items().list(base)).containsExactlyInAnyOrder(mcq, card, cloze);
        assertThat(repos.items().list(base.withModule(m2.id()))).containsExactlyInAnyOrder(card, cloze);
        assertThat(repos.items().list(base.withStatuses(ItemStatus.DRAFT))).containsExactlyInAnyOrder(mcq, cloze);
        assertThat(repos.items().list(base.withTypes(ItemType.CLOZE, ItemType.MCQ))).containsExactlyInAnyOrder(mcq, cloze);
        assertThat(repos.items().list(base.withConcept(concept.id()))).containsExactly(cloze);
        assertThat(repos.items().list(base.withSearch("organelle"))).containsExactly(mcq);
        assertThat(repos.items().list(base.withSearch("100%"))).containsExactly(card);
        assertThat(repos.items().list(base.withSearch("10_%"))).isEmpty();
        assertThat(repos.items().list(base.withFlaggedOnly(true))).containsExactly(card);
        assertThat(repos.items().list(base.withSupport(SupportStatus.UNCHECKED))).hasSize(3);
        assertThat(repos.items().list(base.withPage(2, 0))).hasSize(2);
        assertThat(repos.items().list(base.withPage(2, 2))).hasSize(1);
        assertThat(repos.items().list(ItemQuery.all())).hasSize(4);
    }

    @Test
    void listsAndCountsByStatus() {
        Course course = t.course("C");
        CourseModule module = t.module(course, "M");
        LearningItem a = t.item(course, module, ItemType.MCQ, ItemStatus.APPROVED, "a");
        LearningItem b = t.item(course, module, ItemType.MCQ, ItemStatus.DRAFT, "b");
        LearningItem c = t.item(course, null, ItemType.MCQ, ItemStatus.DRAFT, "c");

        assertThat(repos.items().listByCourse(course.id(), ItemStatus.DRAFT)).containsExactlyInAnyOrder(b, c);
        assertThat(repos.items().listByCourse(course.id())).hasSize(3);
        assertThat(repos.items().listByModule(module.id(), ItemStatus.APPROVED)).containsExactly(a);
        assertThat(repos.items().listByIds(List.of(c.id(), a.id(), "missing"))).containsExactly(c, a);
        assertThat(repos.items().setStatus(List.of(b.id(), c.id()), ItemStatus.APPROVED)).isEqualTo(2);

        Map<ItemStatus, Integer> counts = repos.items().countByStatus(course.id());
        assertThat(counts).containsEntry(ItemStatus.APPROVED, 3).containsEntry(ItemStatus.DRAFT, 0);
        assertThat(counts).containsOnlyKeys(ItemStatus.values());
    }

    @Test
    void listsUnseenApprovedItemsInModuleOrder() {
        Course course = t.course("C");
        CourseModule first = t.module(course, "First");
        CourseModule second = t.module(course, "Second");
        LearningItem late = t.item(course, second, ItemType.MCQ, ItemStatus.APPROVED, "late");
        t.clock.advance(java.time.Duration.ofMillis(1));
        LearningItem early = t.item(course, first, ItemType.MCQ, ItemStatus.APPROVED, "early");
        t.clock.advance(java.time.Duration.ofMillis(1));
        LearningItem loose = t.item(course, null, ItemType.MCQ, ItemStatus.APPROVED, "loose");
        LearningItem seen = t.item(course, first, ItemType.MCQ, ItemStatus.APPROVED, "seen");
        t.clock.advance(java.time.Duration.ofMillis(1));
        LearningItem newState = t.item(course, first, ItemType.MCQ, ItemStatus.APPROVED, "new state");
        t.item(course, first, ItemType.MCQ, ItemStatus.DRAFT, "draft");
        repos.reviews().upsert(new ReviewState(seen.id(), edu.learnsphere.core.model.ReviewPhase.REVIEW, 1, 5,
                t.now(), t.now(), 1, 0, 1, 0, t.now(), t.now()));
        repos.reviews().upsert(ReviewState.newItem(newState.id(), t.now()));

        assertThat(repos.items().listUnseen(StudyScope.course(course.id()), 10)).containsExactly(early, newState, late, loose);
        assertThat(repos.items().listUnseen(StudyScope.module(course.id(), second.id()), 10)).containsExactly(late);
        assertThat(repos.items().countUnseen(StudyScope.course(course.id()))).isEqualTo(4);
        assertThat(repos.items().listUnseen(StudyScope.enrolled(), 10)).as("not enrolled").isEmpty();
        repos.courses().enrol(course.id(), t.now());
        assertThat(repos.items().listUnseen(StudyScope.enrolled(), 1)).containsExactly(early);
    }

    @Test
    void listsUnseenItemsOnlyFromCoursesBeingStudied() {
        Course studying = t.enrolledCourse("Studying");
        Course left = t.enrolledCourse("Left");
        Course archived = t.enrolledCourse("Archived");
        Course ownUnenrolled = t.course("Own, not studied");
        LearningItem a = t.item(studying, null, ItemType.MCQ, ItemStatus.APPROVED, "a");
        t.item(left, null, ItemType.MCQ, ItemStatus.APPROVED, "b");
        t.item(archived, null, ItemType.MCQ, ItemStatus.APPROVED, "c");
        LearningItem d = t.item(ownUnenrolled, null, ItemType.MCQ, ItemStatus.APPROVED, "d");
        repos.courses().leave(left.id());
        t.archive(archived);

        assertThat(repos.items().listUnseen(StudyScope.enrolled(), 10)).containsExactly(a);
        assertThat(repos.items().countUnseen(StudyScope.enrolled())).isEqualTo(1);
        assertThat(repos.items().listUnseen(StudyScope.courses(List.of(studying.id(), ownUnenrolled.id())), 10))
                .containsExactlyInAnyOrder(a, d);
        assertThat(repos.items().listUnseen(StudyScope.courses(List.of()), 10)).isEmpty();
    }

    @Test
    void storesEmbeddingsAndClearsThemWhenTheTextChanges() {
        Course course = t.course("C");
        CourseModule module = t.module(course, "M");
        LearningItem a = t.item(course, module, ItemType.MCQ, ItemStatus.DRAFT, "a");
        LearningItem b = t.item(course, module, ItemType.MCQ, ItemStatus.APPROVED, "b");
        LearningItem rejected = t.item(course, module, ItemType.MCQ, ItemStatus.REJECTED, "r");

        assertThat(repos.items().listMissingEmbedding(module.id(), "m1", 10)).containsExactlyInAnyOrder(a, b);
        repos.items().updateEmbeddings(Map.of(a.id(), new float[] {1f, 0f}, b.id(), new float[] {0f, 1f},
                rejected.id(), new float[] {1f, 1f}), "m1");

        assertThat(repos.items().embeddingsForModule(module.id(), "m1")).containsOnlyKeys(a.id(), b.id());
        assertThat(repos.items().embeddingsForModule(module.id(), "m1").get(a.id())).containsExactly(1f, 0f);
        assertThat(repos.items().embeddingsForModule(module.id(), "m2")).isEmpty();
        assertThat(repos.items().listMissingEmbedding(module.id(), "m1", 10)).isEmpty();
        assertThat(repos.items().listMissingEmbedding(module.id(), "m2", 10)).hasSize(2);

        LearningItem current = repos.items().get(a.id());
        repos.items().update(current.withSupport(edu.learnsphere.core.model.SupportStatus.SUPPORTED, "ok", t.now()));
        assertThat(repos.items().embeddingsForModule(module.id(), "m1")).as("same text keeps it").containsKey(a.id());
        repos.items().update(new LearningItem(current.id(), current.courseId(), current.moduleId(), current.type(),
                "a reworded", current.answer(), current.explanation(), current.distractors(), current.difficulty(),
                current.sourceChunkId(), current.sourcePage(), current.origin(), current.status(), current.support(),
                current.supportNote(), current.createdAt(), t.now()));
        assertThat(repos.items().embeddingsForModule(module.id(), "m1")).containsOnlyKeys(b.id());
    }

    @Test
    void linksConcepts() {
        Course course = t.course("C");
        LearningItem item = t.item(course, null, ItemType.MCQ, ItemStatus.APPROVED, "q");
        LearningItem other = t.item(course, null, ItemType.MCQ, ItemStatus.APPROVED, "r");
        Concept a = t.concept(course, null, "A");
        Concept b = t.concept(course, null, "B");

        repos.items().setConcepts(item.id(), List.of(a.id(), b.id()));
        repos.items().setConcepts(other.id(), List.of(b.id()));
        assertThat(repos.items().conceptIds(item.id())).containsExactlyInAnyOrder(a.id(), b.id());
        assertThat(repos.items().conceptLinks(course.id()))
                .containsOnlyKeys(item.id(), other.id())
                .hasEntrySatisfying(other.id(), ids -> assertThat(ids).containsExactly(b.id()));
        assertThat(repos.items().itemIdsForConcept(b.id())).containsExactlyInAnyOrder(item.id(), other.id());
        assertThat(repos.items().itemIdsForConcepts(List.of(a.id()))).containsExactly(item.id());

        repos.items().setConcepts(item.id(), List.of(b.id()));
        assertThat(repos.items().conceptIds(item.id())).containsExactly(b.id());

        assertThat(repos.items().delete(item.id())).isTrue();
        assertThat(repos.items().itemIdsForConcept(b.id())).containsExactly(other.id());
    }

    @Test
    void escapesLikeWildcards() {
        assertThat(LearningItemRepository.escapeLike("50%_off!")).isEqualTo("50!%!_off!!");
    }
}
