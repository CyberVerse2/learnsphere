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
import org.junit.jupiter.api.Test;

class ReviewStateRepositoryTest extends RepositoryTestBase {

    private static final long DAY = 86_400_000L;

    private ReviewState reviewed(LearningItem item, long dueAt) {
        return new ReviewState(item.id(), ReviewPhase.REVIEW, 4.5, 5.2, dueAt, dueAt - DAY, 3, 1, 4, t.now(), t.now());
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

        assertThat(repos.reviews().listDue(course.id(), null, t.now(), 10)).extracting(ReviewState::itemId)
                .containsExactly(overdue.id(), dueNow.id());
        assertThat(repos.reviews().listDue(course.id(), module.id(), t.now(), 10)).extracting(ReviewState::itemId)
                .containsExactly(overdue.id());
        assertThat(repos.reviews().countDue(course.id(), null, t.now())).isEqualTo(2);
        assertThat(repos.reviews().countDue(null, null, t.now() + 4 * DAY)).isEqualTo(3);
        assertThat(repos.reviews().countDueByCourse(t.now())).containsExactlyEntriesOf(java.util.Map.of(course.id(), 2));
        assertThat(repos.reviews().dueTimesBetween(course.id(), t.now() + 1, t.now() + 5 * DAY))
                .containsExactly(t.now() + 3 * DAY);
        assertThat(repos.reviews().nextDueAfter(course.id(), t.now())).contains(t.now() + 3 * DAY);
        assertThat(repos.reviews().nextDueAfter(course.id(), t.now() + 3 * DAY)).isEmpty();
        assertThat(repos.reviews().listByCourse(course.id())).extracting(ReviewState::itemId).doesNotContain(retired.id()).hasSize(4);
    }
}
