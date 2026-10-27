package edu.learnsphere.core.learning;

import edu.learnsphere.core.model.Attempt;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.model.SessionRecord;
import edu.learnsphere.core.testing.TestDb;
import edu.learnsphere.core.util.Ids;
import java.time.Duration;
import java.util.Arrays;

/** Study data builders on top of {@link TestDb} for the learning and practice tests. */
public final class StudyData {

    public static final long MINUTE = 60_000L;
    public static final long DAY = 86_400_000L;

    private final TestDb t;

    public StudyData(TestDb t) {
        this.t = t;
    }

    /**
     * An approved item; the clock moves on a millisecond so items keep their creation order.
     */
    public LearningItem item(Course course, CourseModule module, ItemType type, String prompt) {
        LearningItem item = t.item(course, module, type, ItemStatus.APPROVED, prompt);
        t.clock.advance(Duration.ofMillis(1));
        return item;
    }

    /** Tags {@code item} with concepts. */
    public void tag(LearningItem item, Concept... concepts) {
        t.repos.items().setConcepts(item.id(), Arrays.stream(concepts).map(Concept::id).toList());
    }

    /** Stores a review state last reviewed {@code daysAgo} days ago with {@code stability}, due at {@code dueAt}. */
    public ReviewState state(LearningItem item, double stability, double daysAgo, long dueAt) {
        long last = t.now() - (long) (daysAgo * DAY);
        ReviewState state = new ReviewState(item.id(), ReviewPhase.REVIEW, stability, 5, dueAt, last, 3, 0,
                Math.max(1, (int) stability), 0, last, last);
        t.repos.reviews().upsert(state);
        return state;
    }

    /** Records an attempt made at {@code at} (and leaves the review state alone). */
    public Attempt attempt(LearningItem item, SessionRecord session, boolean correct, long at) {
        Attempt attempt = new Attempt(Ids.newId(), item.id(), session.id(), correct ? "right" : "wrong", correct,
                correct ? 1 : 0, correct ? Rating.GOOD : Rating.AGAIN, null, false, 10_000, GradedBy.RULE, null, false, at, at);
        t.repos.sessions().insertAttempt(attempt);
        return attempt;
    }
}
