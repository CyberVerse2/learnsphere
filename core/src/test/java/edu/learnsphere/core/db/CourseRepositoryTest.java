package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.EnrolmentStatus;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.model.Visibility;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CourseRepositoryTest extends RepositoryTestBase {

    @Test
    void insertsAndFindsACourse() {
        Course course = t.course("Cell biology");

        assertThat(repos.courses().find(course.id())).contains(course);
        assertThat(repos.courses().get(course.id())).isEqualTo(course);
        assertThat(repos.courses().exists(course.id())).isTrue();
        assertThat(repos.courses().findByPackageId(course.packageId())).contains(course);
        assertThatThrownBy(() -> repos.courses().get("missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updatesDetailsAndStampsUpdatedAt() {
        Course course = t.course("Draft");
        t.clock.advance(Duration.ofMinutes(5));

        assertThat(repos.courses().updateDetails(course.id(), "Genetics", "Basics", "Long text", "Grace")).isTrue();
        assertThat(repos.courses().setCoverHue(course.id(), 42)).isTrue();
        assertThat(repos.courses().setVisibility(course.id(), Visibility.PROTECTED)).isTrue();
        assertThat(repos.courses().setStatus(course.id(), CourseStatus.ARCHIVED)).isTrue();

        Course updated = repos.courses().get(course.id());
        assertThat(updated.title()).isEqualTo("Genetics");
        assertThat(updated.subtitle()).isEqualTo("Basics");
        assertThat(updated.description()).isEqualTo("Long text");
        assertThat(updated.authorName()).isEqualTo("Grace");
        assertThat(updated.coverHue()).isEqualTo(42);
        assertThat(updated.visibility()).isEqualTo(Visibility.PROTECTED);
        assertThat(updated.status()).isEqualTo(CourseStatus.ARCHIVED);
        assertThat(updated.updatedAt()).isEqualTo(t.now());
        assertThat(updated.createdAt()).isEqualTo(course.createdAt());
    }

    @Test
    void fullUpdateWritesEveryMutableColumn() {
        Course course = t.course("Old");
        Course changed = new Course(course.id(), "New", "Sub", "Desc", "Author", CourseOrigin.IMPORTED,
                Visibility.SHAREABLE, CourseStatus.PUBLISHED, 300, 3, 1000L, "pkg", 2000L, course.createdAt(), 0);
        assertThat(repos.courses().update(changed)).isTrue();
        Course stored = repos.courses().get(course.id());
        assertThat(stored).usingRecursiveComparison().ignoringFields("updatedAt").isEqualTo(changed);
    }

    @Test
    void listsMostRecentlyOpenedFirst() {
        Course a = t.course("A");
        Course b = t.course("B");
        Course c = t.course("C");
        repos.courses().markOpened(b.id(), t.now() + 10);
        repos.courses().markOpened(a.id(), t.now() + 5);

        assertThat(repos.courses().list()).extracting(Course::id).containsExactly(b.id(), a.id(), c.id());
        assertThat(repos.courses().listByOrigin(CourseOrigin.LOCAL)).hasSize(3);
        assertThat(repos.courses().listByOrigin(CourseOrigin.IMPORTED)).isEmpty();
    }

    @Test
    void deletingACourseCascadesToItsContent() {
        Course course = t.course("Doomed");
        CourseModule module = t.module(course, "M1");
        Document document = t.document(course, module, "Doc");
        t.chunk(document, 0, "Some text");
        LearningItem item = t.item(course, module, ItemType.FLASHCARD, ItemStatus.APPROVED, "Front");
        repos.reviews().upsert(ReviewState.newItem(item.id(), t.now()));
        repos.courses().enrol(course.id(), t.now());

        assertThat(repos.courses().delete(course.id())).isTrue();

        assertThat(repos.modules().listByCourse(course.id())).isEmpty();
        assertThat(repos.documents().find(document.id())).isEmpty();
        assertThat(repos.chunks().countByCourse(course.id())).isZero();
        assertThat(repos.items().find(item.id())).isEmpty();
        assertThat(repos.reviews().find(item.id())).isEmpty();
        assertThat(repos.courses().findEnrolment(course.id())).isEmpty();
    }

    @Test
    void publishesVersionsWithTheirItems() {
        Course course = t.course("Published");
        LearningItem one = t.item(course, null, ItemType.MCQ, ItemStatus.APPROVED, "Q1");
        LearningItem two = t.item(course, null, ItemType.CLOZE, ItemStatus.APPROVED, "Q2 ____");

        CourseVersion v1 = repos.courses().publishVersion(course.id(), "First", List.of(one.id()), t.now());
        CourseVersion v2 = repos.courses().publishVersion(course.id(), "Second", List.of(one.id(), two.id()), t.now() + 1);

        assertThat(v1.number()).isEqualTo(1);
        assertThat(v2.number()).isEqualTo(2);
        assertThat(repos.courses().listVersions(course.id())).extracting(CourseVersion::number).containsExactly(2, 1);
        assertThat(repos.courses().latestVersion(course.id())).contains(v2);
        assertThat(repos.courses().versionItemIds(v2.id())).containsExactlyInAnyOrder(one.id(), two.id());
        assertThat(repos.courses().nextVersionNumber(course.id())).isEqualTo(3);
        Course stored = repos.courses().get(course.id());
        assertThat(stored.versionNumber()).isEqualTo(2);
        assertThat(stored.status()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(stored.publishedAt()).isEqualTo(t.now() + 1);
    }

    @Test
    void enrolsLeavesAndReEnrols() {
        Course course = t.course("Studied");

        assertThat(repos.courses().enrol(course.id(), t.now()).status()).isEqualTo(EnrolmentStatus.ACTIVE);
        assertThat(repos.courses().isEnrolled(course.id())).isTrue();
        assertThat(repos.courses().listEnrolled()).extracting(Course::id).containsExactly(course.id());
        assertThat(repos.courses().listRecentlyOpened(5)).extracting(Course::id).containsExactly(course.id());

        assertThat(repos.courses().leave(course.id())).isTrue();
        assertThat(repos.courses().isEnrolled(course.id())).isFalse();
        assertThat(repos.courses().listActiveEnrolments()).isEmpty();

        String firstId = repos.courses().findEnrolment(course.id()).orElseThrow().id();
        assertThat(repos.courses().enrol(course.id(), t.now() + 1).id()).isEqualTo(firstId);
        assertThat(repos.courses().isEnrolled(course.id())).isTrue();
    }

    @Test
    void countsEverythingACourseCardShows() {
        Course course = t.course("Counted");
        CourseModule module = t.module(course, "M");
        t.module(course, "M2");
        Document ready = t.document(course, module, "Ready");
        repos.documents().markReady(ready.id());
        t.document(course, module, "Queued");
        LearningItem due = t.item(course, module, ItemType.MCQ, ItemStatus.APPROVED, "due");
        LearningItem later = t.item(course, module, ItemType.MCQ, ItemStatus.APPROVED, "later");
        t.item(course, module, ItemType.MCQ, ItemStatus.APPROVED, "unseen");
        t.item(course, module, ItemType.MCQ, ItemStatus.DRAFT, "draft");
        repos.reviews().upsert(new ReviewState(due.id(), ReviewPhase.REVIEW, 3, 5, t.now() - 1, t.now() - 100, 2, 0, 3, 0, t.now(), t.now()));
        repos.reviews().upsert(new ReviewState(later.id(), ReviewPhase.REVIEW, 3, 5, t.now() + 100_000, t.now(), 1, 0, 3, 0, t.now(), t.now()));
        repos.flags().insert(edu.learnsphere.core.model.Flag.open(due.id(), edu.learnsphere.core.model.FlagReason.UNCLEAR, "",
                edu.learnsphere.core.model.FlagSource.SELF, t.now()));

        CourseRepository.CourseCounts counts = repos.courses().counts(course.id(), t.now());

        assertThat(counts.moduleCount()).isEqualTo(2);
        assertThat(counts.documentCount()).isEqualTo(2);
        assertThat(counts.documentsProcessing()).isEqualTo(1);
        assertThat(counts.approvedItems()).isEqualTo(3);
        assertThat(counts.draftItems()).isEqualTo(1);
        assertThat(counts.startedItems()).isEqualTo(2);
        assertThat(counts.newItems()).isEqualTo(1);
        assertThat(counts.dueItems()).isEqualTo(1);
        assertThat(counts.openFlags()).isEqualTo(1);
        assertThat(repos.courses().counts(t.now())).containsKey(course.id());
        assertThat(repos.courses().counts("missing", t.now())).isEqualTo(CourseRepository.CourseCounts.EMPTY);
    }
}
