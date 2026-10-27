package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Attempt;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.SessionRecord;
import edu.learnsphere.core.util.Ids;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class StudySessionRepositoryTest extends RepositoryTestBase {

    private Attempt attempt(LearningItem item, SessionRecord session, boolean correct, double score) {
        Attempt attempt = new Attempt(Ids.newId(), item.id(), session.id(), "answer", correct, score,
                correct ? Rating.GOOD : Rating.AGAIN, Confidence.FAIRLY_SURE, false, 4200, GradedBy.RULE, null, false,
                t.now(), t.now());
        repos.sessions().insertAttempt(attempt);
        t.clock.advance(Duration.ofSeconds(1));
        return attempt;
    }

    @Test
    void startsAndEndsSessions() {
        Course course = t.course("C");
        SessionRecord session = t.session(course);
        assertThat(repos.sessions().findSession(session.id())).contains(session);

        t.clock.advance(Duration.ofMinutes(12));
        assertThat(repos.sessions().endSession(session.id(), t.now())).isTrue();
        assertThat(repos.sessions().findSession(session.id()).orElseThrow().endedAt()).isEqualTo(t.now());

        SessionRecord crossCourse = t.session(null);
        assertThat(repos.sessions().listSessions(course.id(), 10)).containsExactly(repos.sessions().findSession(session.id()).orElseThrow());
        assertThat(repos.sessions().listSessions(null, 10)).extracting(SessionRecord::id).containsExactly(crossCourse.id(), session.id());
    }

    @Test
    void countsItemsFirstAttemptedSinceATime() {
        Course course = t.course("C");
        Course other = t.course("D");
        LearningItem old = t.item(course, null, ItemType.MCQ, ItemStatus.APPROVED, "old");
        LearningItem fresh = t.item(course, null, ItemType.MCQ, ItemStatus.APPROVED, "fresh");
        LearningItem elsewhere = t.item(other, null, ItemType.MCQ, ItemStatus.APPROVED, "elsewhere");
        SessionRecord session = t.session(null);
        attempt(old, session, true, 1);
        long startOfDay = t.now();
        attempt(old, session, false, 0);
        attempt(fresh, session, true, 1);
        attempt(fresh, session, true, 1);
        attempt(elsewhere, session, true, 1);

        assertThat(repos.sessions().countItemsFirstAttemptedSince(startOfDay)).isEqualTo(2);
        assertThat(repos.sessions().countItemsFirstAttemptedSince(t.now())).isZero();
    }

    @Test
    void roundTripsAttempts() {
        Course course = t.course("C");
        LearningItem item = t.item(course, null, ItemType.SHORT_ANSWER, ItemStatus.APPROVED, "Explain");
        SessionRecord session = t.session(course);
        Attempt graded = new Attempt(Ids.newId(), item.id(), session.id(), "My words", false, 0.5, Rating.HARD, null,
                true, 30_000, GradedBy.AI, "Missing the second step.", false, t.now(), t.now());
        repos.sessions().insertAttempt(graded);
        t.clock.advance(Duration.ofSeconds(1));
        Attempt second = attempt(item, session, true, 1);

        assertThat(repos.sessions().findAttempt(graded.id())).contains(graded);
        assertThat(repos.sessions().listAttemptsForItem(item.id(), 10)).containsExactly(second, graded);
        assertThat(repos.sessions().listAttemptsForSession(session.id())).containsExactly(graded, second);
        assertThat(repos.sessions().listAttemptsForCourse(course.id(), second.createdAt())).containsExactly(second);
        assertThat(repos.sessions().attemptTimesSince(0)).hasSize(2);
        assertThat(repos.sessions().countAttemptsSince(course.id(), 0)).isEqualTo(2);
        assertThat(repos.sessions().countAttemptsSince(null, 0)).isEqualTo(2);

        assertThat(repos.sessions().markDisputed(graded.id(), Rating.GOOD)).isTrue();
        Attempt disputed = repos.sessions().findAttempt(graded.id()).orElseThrow();
        assertThat(disputed.disputed()).isTrue();
        assertThat(disputed.rating()).isEqualTo(Rating.GOOD);
    }

    @Test
    void summarizesAttemptsByConceptAndItem() {
        Course course = t.course("C");
        LearningItem a = t.item(course, null, ItemType.MCQ, ItemStatus.APPROVED, "a");
        LearningItem b = t.item(course, null, ItemType.MCQ, ItemStatus.APPROVED, "b");
        Concept concept = t.concept(course, null, "Energy");
        repos.items().setConcepts(a.id(), List.of(concept.id()));
        repos.items().setConcepts(b.id(), List.of(concept.id()));
        SessionRecord session = t.session(course);
        attempt(a, session, false, 0);
        attempt(a, session, true, 1);
        attempt(b, session, true, 1);

        List<StudySessionRepository.ConceptAttempt> recent = repos.sessions().recentAttemptsByConcept(course.id(), 2);
        assertThat(recent).hasSize(2);
        assertThat(recent).extracting(StudySessionRepository.ConceptAttempt::itemId).containsExactly(b.id(), a.id());
        assertThat(recent).extracting(StudySessionRepository.ConceptAttempt::rank).containsExactly(1, 2);

        StudySessionRepository.ItemAttemptStats stats = repos.sessions().itemStats(course.id()).get(a.id());
        assertThat(stats.attempts()).isEqualTo(2);
        assertThat(stats.correct()).isEqualTo(1);
        assertThat(stats.accuracy()).isEqualTo(0.5);
        assertThat(stats.averageScore()).isEqualTo(0.5);
        assertThat(stats.averageDurationMs()).isEqualTo(4200);
    }
}
