package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewStateRepositoryTest extends RepositoryTestBase {

    private static final long DAY = 86_400_000L;

    private ReviewState reviewed(LearningItem item, long dueAt) {
        return new ReviewState(item.id(), ReviewPhase.REVIEW, 4.5, 5.2, dueAt, dueAt - DAY, 3, 1, 4, 0, t.now(), t.now());
    }

    @Test
    void upsertsAndFinds() {
        LearningItem item = t.item(t.course("C"), null, ItemType.MCQ, ItemStatus.APPROVED, "q");
        ReviewState first = ReviewState.newItem(item.id(), t.now());
        repos.reviews().upsert(first);
        assertThat(repos.reviews().find(item.id())).contains(first);

        ReviewState second = reviewed(item, t.now() + DAY);
        repos.reviews().upsert(second);
        assertThat(repos.reviews().find(item.id())).contains(second);
        assertThat(repos.reviews().findAll(List.of(item.id(), "missing"))).containsOnlyKeys(item.id());
        assertThat(repos.reviews().delete(item.id())).isTrue();
        assertThat(repos.reviews().find(item.id())).isEmpty();
    }

    @Test
    void listsAndCountsDueApprovedReviews() {
        Course course = t.course("C");
        CourseModule module = t.module(course, "M");
        LearningItem overdue = t.item(course, module, ItemType.MCQ, ItemStatus.APPROVED, "overdue");
        LearningItem dueNow = t.item(course, null, ItemType.MCQ, ItemStatus.APPROVED, "now");
        LearningItem later = t.item(course, module, ItemType.MCQ, ItemStatus.APPROVED, "later");
        LearningItem retired = t.item(course, module, ItemType.MCQ, ItemStatus.RETIRED, "retired");
        LearningItem fresh = t.item(course, module, ItemType.MCQ, ItemStatus.APPROVED, "fresh");
        repos.reviews().upsertAll(List.of(reviewed(overdue, t.now() - DAY), reviewed(dueNow, t.now()),
                reviewed(later, t.now() + 3 * DAY), reviewed(retired, t.now() - DAY), ReviewState.newItem(fresh.id(), t.now())));

        StudyScope inCourse = StudyScope.course(course.id());
        assertThat(repos.reviews().listDue(inCourse, t.now(), 10)).extracting(ReviewState::itemId)
                .containsExactly(overdue.id(), dueNow.id());
        assertThat(repos.reviews().listDue(StudyScope.module(course.id(), module.id()), t.now(), 10))
                .extracting(ReviewState::itemId).containsExactly(overdue.id());
        assertThat(repos.reviews().countDue(inCourse, t.now())).isEqualTo(2);
        assertThat(repos.reviews().countDue(inCourse, t.now() + 4 * DAY)).isEqualTo(3);
        assertThat(repos.reviews().countDueByCourse(inCourse, t.now())).containsExactlyEntriesOf(Map.of(course.id(), 2));
        assertThat(repos.reviews().dueTimesBetween(inCourse, t.now() + 1, t.now() + 5 * DAY))
                .containsExactly(t.now() + 3 * DAY);
        assertThat(repos.reviews().nextDueAfter(inCourse, t.now())).contains(t.now() + 3 * DAY);
        assertThat(repos.reviews().nextDueAfter(inCourse, t.now() + 3 * DAY)).isEmpty();
        assertThat(repos.reviews().listByCourse(course.id())).extracting(ReviewState::itemId).doesNotContain(retired.id()).hasSize(4);
    }

    @Test
    void storesTheLearningStep() {
        LearningItem item = t.item(t.course("C"), null, ItemType.FLASHCARD, ItemStatus.APPROVED, "q");
        ReviewState learning = new ReviewState(item.id(), ReviewPhase.LEARNING, 0.4, 6.1, t.now() + 600_000, t.now(), 1, 0,
                0, 1, t.now(), t.now());
        repos.reviews().upsert(learning);
        assertThat(repos.reviews().find(item.id())).hasValueSatisfying(s -> assertThat(s.step()).isEqualTo(1));
    }

    @Test
    void enrolledScopeLeavesOutLeftArchivedAndUnenrolledCourses() {
        Course studying = t.enrolledCourse("Studying");
        Course left = t.enrolledCourse("Left");
        Course archived = t.enrolledCourse("Archived");
        Course ownUnenrolled = t.course("Own, not studied");
        LearningItem a = t.item(studying, null, ItemType.MCQ, ItemStatus.APPROVED, "a");
        LearningItem b = t.item(left, null, ItemType.MCQ, ItemStatus.APPROVED, "b");
        LearningItem c = t.item(archived, null, ItemType.MCQ, ItemStatus.APPROVED, "c");
        LearningItem d = t.item(ownUnenrolled, null, ItemType.MCQ, ItemStatus.APPROVED, "d");
        for (LearningItem item : List.of(a, b, c, d)) {
            repos.reviews().upsert(reviewed(item, t.now() - DAY));
        }
        LearningItem later = t.item(studying, null, ItemType.MCQ, ItemStatus.APPROVED, "later");
        repos.reviews().upsert(reviewed(later, t.now() + 2 * DAY));
        LearningItem laterElsewhere = t.item(ownUnenrolled, null, ItemType.MCQ, ItemStatus.APPROVED, "elsewhere");
        repos.reviews().upsert(reviewed(laterElsewhere, t.now() + DAY));
        repos.courses().leave(left.id());
        t.archive(archived);

        StudyScope enrolled = StudyScope.enrolled();
        assertThat(repos.reviews().listDue(enrolled, t.now(), 10)).extracting(ReviewState::itemId).containsExactly(a.id());
        assertThat(repos.reviews().countDue(enrolled, t.now())).isEqualTo(1);
        assertThat(repos.reviews().countDueByCourse(enrolled, t.now())).containsExactlyEntriesOf(Map.of(studying.id(), 1));
        assertThat(repos.reviews().dueTimesBetween(enrolled, t.now() - 2 * DAY, t.now() + 3 * DAY))
                .containsExactly(t.now() - DAY, t.now() + 2 * DAY);
        assertThat(repos.reviews().nextDueAfter(enrolled, t.now())).contains(t.now() + 2 * DAY);
        assertThat(repos.reviews().listApproved(enrolled)).extracting(ReviewState::itemId)
                .containsExactlyInAnyOrder(a.id(), later.id());

        repos.courses().enrol(left.id(), t.now());
        assertThat(repos.reviews().countDue(enrolled, t.now())).as("enrolled again").isEqualTo(2);
    }
}
