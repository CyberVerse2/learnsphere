package edu.learnsphere.core.learning;

import static edu.learnsphere.core.learning.StudyData.DAY;
import static edu.learnsphere.core.learning.StudyData.MINUTE;
import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.db.StudyScope;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.model.SessionRecord;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.StudyNext;
import edu.learnsphere.core.testing.TestDb;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StudyPlannerTest {

    @TempDir
    Path dir;
    TestDb t;
    StudyData data;
    StudyQueries queries;
    MasteryCalculator mastery;
    StudyPlanner planner;
    Course course;

    @BeforeEach
    void open() {
        t = TestDb.open(dir);
        data = new StudyData(t);
        queries = new StudyQueries(t.db);
        mastery = new MasteryCalculator(queries);
        planner = new StudyPlanner(t.repos, queries, mastery, t.clock);
        course = t.enrolledCourse("Biology");
    }

    @AfterEach
    void close() {
        t.close();
    }

    private List<String> plan(SessionKind kind, StudyScope scope, int size, int newPerDay) {
        return planner.plan(kind, scope, size, newPerDay).stream().map(p -> p.item().prompt()).toList();
    }

    @Test
    void studyNextPutsTheLeastRememberedDueItemsFirst() {
        long now = t.now();
        LearningItem fading = data.item(course, null, ItemType.MCQ, "fading");
        LearningItem solid = data.item(course, null, ItemType.MCQ, "solid");
        LearningItem middle = data.item(course, null, ItemType.MCQ, "middle");
        data.state(solid, 200, 10, now - MINUTE);
        data.state(fading, 1, 10, now - MINUTE);
        data.state(middle, 20, 10, now - MINUTE);
        data.state(data.item(course, null, ItemType.MCQ, "not due"), 2, 1, now + DAY);

        assertThat(plan(SessionKind.STUDY_NEXT, StudyScope.enrolled(), 20, 0)).containsExactly("fading", "middle", "solid");
    }

    @Test
    void newItemsComeInModuleOrderWithinTheDailyLimit() {
        CourseModule second = t.module(course, "Second");
        CourseModule first = t.module(course, "First");
        t.repos.modules().reorder(course.id(), List.of(first.id(), second.id()));
        for (int i = 1; i <= 4; i++) {
            data.item(course, second, ItemType.FLASHCARD, "second " + i);
        }
        for (int i = 1; i <= 4; i++) {
            data.item(course, first, ItemType.FLASHCARD, "first " + i);
        }

        assertThat(plan(SessionKind.STUDY_NEXT, StudyScope.enrolled(), 20, 5))
                .containsExactly("first 1", "first 2", "first 3", "first 4", "second 1");

        // Two items were met for the first time today: three are left.
        SessionRecord session = t.session(course);
        List<LearningItem> unseen = t.repos.items().listUnseen(StudyScope.enrolled(), 2);
        unseen.forEach(item -> data.attempt(item, session, true, t.now()));
        assertThat(planner.newItemsLeftToday(5)).isEqualTo(3);
        // Items attempted before today do not count.
        data.attempt(t.repos.items().listUnseen(StudyScope.enrolled(), 3).get(2), session, true, t.now() - 2 * DAY);
        assertThat(planner.newItemsLeftToday(5)).isEqualTo(3);
    }

    @Test
    void theSessionSizeCapsReviewsBeforeNewItems() {
        long now = t.now();
        for (int i = 0; i < 30; i++) {
            data.state(data.item(course, null, ItemType.MCQ, "due " + i), 5, 6, now - i * MINUTE);
        }
        data.item(course, null, ItemType.MCQ, "brand new");

        List<PlannedItem> plan = planner.plan(SessionKind.STUDY_NEXT, StudyScope.enrolled(), 20, 15);
        assertThat(plan).hasSize(20).allMatch(p -> p.reason() == PlannedItem.Reason.DUE);
    }

    @Test
    void newItemsAreSpreadBetweenReviews() {
        long now = t.now();
        for (int i = 0; i < 4; i++) {
            data.state(data.item(course, null, ItemType.MCQ, "review " + i), 1 + i, 10, now - MINUTE);
        }
        data.item(course, null, ItemType.MCQ, "new 0");
        data.item(course, null, ItemType.MCQ, "new 1");

        assertThat(plan(SessionKind.STUDY_NEXT, StudyScope.enrolled(), 20, 15))
                .containsExactly("review 0", "new 0", "review 1", "review 2", "new 1", "review 3");
    }

    @Test
    void reviewsAlternateBetweenConcepts() {
        long now = t.now();
        Concept osmosis = t.concept(course, null, "Osmosis");
        Concept mitosis = t.concept(course, null, "Mitosis");
        LearningItem o1 = data.item(course, null, ItemType.MCQ, "o1");
        LearningItem o2 = data.item(course, null, ItemType.MCQ, "o2");
        LearningItem m1 = data.item(course, null, ItemType.MCQ, "m1");
        LearningItem m2 = data.item(course, null, ItemType.MCQ, "m2");
        data.tag(o1, osmosis);
        data.tag(o2, osmosis);
        data.tag(m1, mitosis);
        data.tag(m2, mitosis);
        // Retrievability order: o1 < o2 < m1 < m2.
        data.state(o1, 1, 10, now - MINUTE);
        data.state(o2, 2, 10, now - MINUTE);
        data.state(m1, 3, 10, now - MINUTE);
        data.state(m2, 4, 10, now - MINUTE);
        // Attempts keep the concepts strong, so they add no weak-concept items.
        SessionRecord session = t.session(course);
        for (LearningItem item : List.of(o1, o2, m1, m2)) {
            data.attempt(item, session, true, now - 10 * DAY);
        }

        assertThat(plan(SessionKind.STUDY_NEXT, StudyScope.enrolled(), 20, 0)).containsExactly("o1", "m1", "o2", "m2");
    }

    @Test
    void weakConceptsFillTheSessionAfterDueItems() {
        long now = t.now();
        Concept weak = t.concept(course, null, "Weak");
        Concept strong = t.concept(course, null, "Strong");
        SessionRecord session = t.session(course);
        LearningItem w1 = data.item(course, null, ItemType.MCQ, "weak 1");
        LearningItem w2 = data.item(course, null, ItemType.MCQ, "weak just seen");
        LearningItem s1 = data.item(course, null, ItemType.MCQ, "strong 1");
        LearningItem due = data.item(course, null, ItemType.MCQ, "due");
        data.tag(w1, weak);
        data.tag(w2, weak);
        data.tag(s1, strong);
        data.state(w1, 30, 2, now + 20 * DAY);
        data.state(w2, 30, 0.01, now + 20 * DAY);
        data.state(s1, 30, 2, now + 20 * DAY);
        data.state(due, 3, 4, now - MINUTE);
        for (int i = 0; i < 4; i++) {
            data.attempt(w1, session, false, now - (5 - i) * DAY);
            data.attempt(s1, session, true, now - (5 - i) * DAY);
        }
        data.item(course, null, ItemType.MCQ, "new");

        List<ConceptMastery> concepts = mastery.conceptMastery(StudyScope.enrolled(), now);
        assertThat(concepts).extracting(ConceptMastery::name).containsExactly("Weak", "Strong");
        assertThat(concepts.getFirst().isWeak()).isTrue();
        assertThat(concepts.getLast().isWeak()).isFalse();

        List<PlannedItem> plan = planner.plan(SessionKind.STUDY_NEXT, StudyScope.enrolled(), 20, 15);
        assertThat(plan).extracting(p -> p.item().prompt()).containsExactly("due", "new", "weak 1");
        assertThat(plan).extracting(PlannedItem::reason)
                .containsExactly(PlannedItem.Reason.DUE, PlannedItem.Reason.NEW, PlannedItem.Reason.WEAK_CONCEPT);

        assertThat(plan(SessionKind.WEAK_CONCEPTS, StudyScope.course(course.id()), 20, 15))
                .containsExactly("weak 1", "weak just seen");
    }

    @Test
    void weakConceptSessionsAreEmptyWithoutWeakConcepts() {
        data.state(data.item(course, null, ItemType.MCQ, "fine"), 30, 1, t.now() + DAY);
        assertThat(planner.plan(SessionKind.WEAK_CONCEPTS, StudyScope.enrolled(), 20, 15)).isEmpty();
    }

    @Test
    void moduleQuizTakesDueThenNewThenTheRest() {
        long now = t.now();
        CourseModule module = t.module(course, "Cells");
        CourseModule other = t.module(course, "Other");
        data.state(data.item(course, module, ItemType.MCQ, "practised"), 10, 1, now + 9 * DAY);
        data.state(data.item(course, module, ItemType.CLOZE, "due"), 2, 3, now - MINUTE);
        data.item(course, module, ItemType.TRUE_FALSE, "new");
        data.item(course, other, ItemType.MCQ, "elsewhere");

        assertThat(plan(SessionKind.MODULE_QUIZ, StudyScope.module(course.id(), module.id()), 20, 15))
                .containsExactlyInAnyOrder("due", "new", "practised");
        assertThat(plan(SessionKind.MODULE_QUIZ, StudyScope.module(course.id(), module.id()), 2, 15))
                .containsExactlyInAnyOrder("due", "new");
        assertThat(plan(SessionKind.MODULE_QUIZ, StudyScope.module(course.id(), module.id()), 2, 0))
                .as("without new items left today, the rest fills the session")
                .containsExactlyInAnyOrder("due", "practised");
    }

    @Test
    void practiceSessionsKeepToTheDailyNewItemLimit() {
        long now = t.now();
        CourseModule module = t.module(course, "Cells");
        for (int i = 0; i < 30; i++) {
            data.item(course, module, i % 2 == 0 ? ItemType.MCQ : ItemType.FLASHCARD, "new " + i);
        }
        for (int i = 0; i < 3; i++) {
            data.state(data.item(course, module, ItemType.FLASHCARD, "practised " + i), 10, 1, now + 9 * DAY);
        }
        StudyScope cells = StudyScope.module(course.id(), module.id());

        List<PlannedItem> quiz = planner.plan(SessionKind.MODULE_QUIZ, cells, 20, 15);
        assertThat(quiz).hasSize(18);
        assertThat(quiz).filteredOn(PlannedItem::isNew).hasSize(15);
        List<PlannedItem> cards = planner.plan(SessionKind.FLASHCARDS, cells, 20, 15);
        assertThat(cards).filteredOn(PlannedItem::isNew).hasSize(15);
        assertThat(cards).filteredOn(p -> !p.isNew()).hasSize(3);

        // Ten items met for the first time today, in any kind of session, leave five.
        SessionRecord session = t.session(course);
        t.repos.items().listUnseen(StudyScope.enrolled(), 10).forEach(item -> data.attempt(item, session, true, t.now()));
        assertThat(planner.plan(SessionKind.MODULE_QUIZ, cells, 20, 15)).filteredOn(PlannedItem::isNew).hasSize(5);
        assertThat(planner.plan(SessionKind.FLASHCARDS, cells, 20, 15)).filteredOn(PlannedItem::isNew).hasSize(5);

        // With the day's allowance used up, only items met before are offered.
        assertThat(planner.plan(SessionKind.MODULE_QUIZ, cells, 20, 10)).noneMatch(PlannedItem::isNew).isNotEmpty();
        assertThat(planner.plan(SessionKind.FLASHCARDS, cells, 20, 10)).noneMatch(PlannedItem::isNew)
                .extracting(p -> p.item().prompt()).containsExactlyInAnyOrder("practised 0", "practised 1", "practised 2");
    }

    @Test
    void flashcardSessionsHoldOnlyFlashcards() {
        data.item(course, null, ItemType.FLASHCARD, "card 1");
        data.item(course, null, ItemType.MCQ, "question");
        data.item(course, null, ItemType.FLASHCARD, "card 2");
        assertThat(plan(SessionKind.FLASHCARDS, StudyScope.course(course.id()), 20, 15)).containsExactly("card 1", "card 2");
    }

    @Test
    void acrossCoursesLeavesOutLeftArchivedAndUnenrolledCourses() {
        long now = t.now();
        Course left = t.enrolledCourse("Left");
        Course archived = t.enrolledCourse("Archived");
        Course mine = t.course("Mine, not studied");
        t.repos.courses().leave(left.id());
        t.archive(archived);
        for (Course c : List.of(course, left, archived, mine)) {
            data.state(data.item(c, null, ItemType.MCQ, c.title() + " due"), 2, 3, now - MINUTE);
            data.item(c, null, ItemType.MCQ, c.title() + " new");
        }

        assertThat(plan(SessionKind.STUDY_NEXT, StudyScope.enrolled(), 20, 15))
                .containsExactly("Biology due", "Biology new");
        StudyNext next = planner.studyNext(null, StudyScope.enrolled(), 15);
        assertThat(next.dueCount()).isEqualTo(1);
        assertThat(next.newCount()).isEqualTo(1);
        assertThat(plan(SessionKind.STUDY_NEXT, StudyScope.course(mine.id()), 20, 15))
                .containsExactly("Mine, not studied due", "Mine, not studied new");
    }

    @Test
    void onlyApprovedItemsAreStudied() {
        t.item(course, null, ItemType.MCQ, ItemStatus.DRAFT, "draft");
        t.item(course, null, ItemType.MCQ, ItemStatus.RETIRED, "retired");
        t.item(course, null, ItemType.MCQ, ItemStatus.REJECTED, "rejected");
        data.item(course, null, ItemType.MCQ, "approved");
        for (SessionKind kind : SessionKind.values()) {
            List<String> prompts = plan(kind, StudyScope.course(course.id()), 20, 15);
            assertThat(prompts).doesNotContain("draft", "retired", "rejected");
        }
        assertThat(plan(SessionKind.MODULE_QUIZ, StudyScope.course(course.id()), 20, 15)).containsExactly("approved");
    }

    @Test
    void studyNextCountsDueAndNewAndEstimatesTime() {
        long now = t.now();
        for (int i = 0; i < 3; i++) {
            data.state(data.item(course, null, ItemType.MCQ, "due " + i), 2, 3, now - MINUTE);
        }
        data.state(data.item(course, null, ItemType.MCQ, "later"), 2, 0, now + 2 * DAY);
        List<LearningItem> fresh = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            fresh.add(data.item(course, null, ItemType.MCQ, "new " + i));
        }

        StudyNext next = planner.studyNext(course.id(), StudyScope.course(course.id()), 15);
        assertThat(next.courseId()).isEqualTo(course.id());
        assertThat(next.dueCount()).isEqualTo(3);
        assertThat(next.newCount()).isEqualTo(15);
        assertThat(next.nextReviewAt()).isEqualTo(now + 2 * DAY);
        // 3 reviews at 20 s plus 15 new items at 30 s, rounded up.
        assertThat(next.estimatedMinutes()).isEqualTo(9);
        assertThat(planner.studyNext(null, StudyScope.enrolled(), 0).newCount()).isZero();
        assertThat(fresh).hasSize(20);
    }
}
