package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.db.ConceptRepository.ConceptCounts;
import edu.learnsphere.core.db.CourseRepository.CourseRow;
import edu.learnsphere.core.db.ModuleRepository.ModuleCounts;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SupportStatus;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The queries the course, document and item services and the job queue add to the repositories. */
class ServiceQueriesTest extends RepositoryTestBase {

    @Test
    void claimsTheNextJobSkippingThoseWaitingAndPutsAClaimBack() {
        Job first = queue(JobKind.GENERATE_ITEMS, "m1");
        Job second = queue(JobKind.GENERATE_ITEMS, "m2");

        Job claimed = repos.jobs().claimNext(JobKind.GENERATE_ITEMS, Set.of(first.id())).orElseThrow();
        assertThat(claimed.id()).isEqualTo(second.id());
        assertThat(repos.jobs().claimNext(JobKind.GENERATE_ITEMS, Set.of(first.id()))).isEmpty();

        assertThat(repos.jobs().requeue(second.id())).isTrue();
        assertThat(repos.jobs().requeue(second.id())).isFalse();
        Job requeued = repos.jobs().find(second.id()).orElseThrow();
        assertThat(requeued.status()).isEqualTo(JobStatus.QUEUED);
        assertThat(requeued.attempts()).isEqualTo(1);
        assertThat(repos.jobs().claimNext(JobKind.GENERATE_ITEMS, List.of()).orElseThrow().id()).isEqualTo(first.id());
    }

    @Test
    void listsCourseRowsWithEnrolmentAndCounts() {
        Course studying = t.enrolledCourse("100% Biology");
        CourseModule cells = t.module(studying, "Cells");
        LearningItem due = t.item(studying, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "Due");
        t.reviewed(due, t.now() - 1);
        t.item(studying, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "New");
        t.item(studying, cells, ItemType.FLASHCARD, ItemStatus.DRAFT, "Draft");
        Document failed = t.document(studying, cells, "Failed");
        repos.documents().markFailed(failed.id(), DocumentError.DAMAGED, null);
        t.document(studying, cells, "Queued");
        Course other = t.course("Biology basics");
        Course archived = t.enrolledCourse("History");
        t.archive(archived);

        assertThat(repos.courses().listRows(null, false, null, false, t.now())).extracting(r -> r.course().id())
                .containsExactlyInAnyOrder(studying.id(), other.id());
        assertThat(repos.courses().listRows(null, true, null, true, t.now())).extracting(r -> r.course().id())
                .containsExactlyInAnyOrder(studying.id(), archived.id());
        assertThat(repos.courses().listRows(CourseOrigin.IMPORTED, false, null, true, t.now())).isEmpty();
        assertThat(repos.courses().listRows(null, false, "100%", false, t.now())).extracting(r -> r.course().id())
                .containsExactly(studying.id());
        assertThat(repos.courses().listRows(null, false, "_", false, t.now())).isEmpty();

        CourseRow row = repos.courses().findRow(studying.id(), t.now()).orElseThrow();
        assertThat(row.enrolled()).isTrue();
        assertThat(row.counts()).isEqualTo(new CourseRepository.CourseCounts(1, 2, 1, 1, 2, 1, 1, 1, 0));
        assertThat(repos.courses().findRow(other.id(), t.now()).orElseThrow().enrolled()).isFalse();
        assertThat(repos.courses().findRow("missing", t.now())).isEmpty();
    }

    @Test
    void countsWorkPerModuleAndConcept() {
        Course course = t.enrolledCourse("Biology");
        CourseModule cells = t.module(course, "Cells");
        CourseModule empty = t.module(course, "Empty");
        LearningItem reviewed = t.item(course, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "Reviewed");
        t.reviewed(reviewed, t.now() - 1);
        LearningItem fresh = t.item(course, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "Fresh");
        t.item(course, cells, ItemType.FLASHCARD, ItemStatus.DRAFT, "Draft");
        t.document(course, cells, "Notes");
        Concept membrane = t.concept(course, cells, "Membrane");
        repos.items().setConcepts(reviewed.id(), List.of(membrane.id()));
        repos.items().setConcepts(fresh.id(), List.of(membrane.id()));

        List<ModuleCounts> modules = repos.modules().listWithCounts(course.id(), StudyScope.enrolled(), t.now());
        assertThat(modules).extracting(m -> m.module().id()).containsExactly(cells.id(), empty.id());
        assertThat(modules.getFirst()).isEqualTo(new ModuleCounts(cells, 2, 1, 1, 1, 1));
        assertThat(modules.getFirst().completion()).isEqualTo(0.5);
        assertThat(modules.get(1).completion()).isZero();

        t.archive(course);
        assertThat(repos.modules().listWithCounts(course.id(), StudyScope.enrolled(), t.now()).getFirst().dueItems())
                .isZero();

        Map<String, ConceptCounts> concepts = repos.concepts().studyCounts(course.id());
        assertThat(concepts).containsEntry(membrane.id(), new ConceptCounts(membrane.id(), 2, 1, 0));
    }

    @Test
    void countsItemsBySourceSupportAndFlags() {
        Course course = t.course("Biology");
        Document document = t.document(course, null, "Notes");
        Chunk chunk = t.chunk(document, 0, "Cells.");
        LearningItem sourced = LearningItem.draft(course.id(), null, ItemType.FLASHCARD, "What?", "Cells", "", List.of(),
                Difficulty.MEDIUM, chunk.id(), 1, ItemOrigin.AI, t.now());
        repos.items().insert(sourced);
        repos.items().setSupport(sourced.id(), SupportStatus.UNSUPPORTED, "Not on page 1");
        LearningItem approved = t.item(course, null, ItemType.FLASHCARD, ItemStatus.APPROVED, "Approved");
        repos.items().setSupport(approved.id(), SupportStatus.UNSUPPORTED, "Not on page 1");
        Flag open = Flag.open(approved.id(), FlagReason.TYPO, "", FlagSource.SELF, t.now());
        repos.flags().insert(open);
        repos.flags().insert(Flag.open(approved.id(), FlagReason.OTHER, "", FlagSource.SELF, t.now()));
        Flag resolved = Flag.open(sourced.id(), FlagReason.OTHER, "", FlagSource.SELF, t.now());
        repos.flags().insert(resolved);
        repos.flags().resolve(resolved.id(), t.now());

        assertThat(repos.items().countBySourceDocument(document.id())).isEqualTo(1);
        assertThat(repos.items().countUnsupportedDrafts(course.id())).isEqualTo(1);
        assertThat(repos.items().countFlagged(course.id())).isEqualTo(1);
    }

    private Job queue(JobKind kind, String subject) {
        Job job = Job.queued(kind, subject, null, 0, t.now());
        repos.jobs().insert(job);
        t.clock.advance(Duration.ofMillis(10));
        return job;
    }
}
