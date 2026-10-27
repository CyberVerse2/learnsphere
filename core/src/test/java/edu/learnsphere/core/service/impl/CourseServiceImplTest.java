package edu.learnsphere.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.events.ChangeType;
import edu.learnsphere.core.events.CourseChanged;
import edu.learnsphere.core.events.ProgressChanged;
import edu.learnsphere.core.model.AuditLogEntry;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.CourseDetail;
import edu.learnsphere.core.service.CourseDraft;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.CourseUpdate;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.ModuleSummary;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.util.Ids;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CourseServiceImplTest {

    @TempDir
    Path dir;

    private ServiceHarness h;
    private CourseServiceImpl courses;

    @BeforeEach
    void setUp() {
        h = new ServiceHarness(dir);
        courses = new CourseServiceImpl(h.t.repos, h.events, h.t.clock, h.jobs, h.index, h.pdfs);
    }

    @AfterEach
    void tearDown() {
        h.close();
    }

    @Test
    void createsAPrivateDraftInAColourAwayFromTheOtherCourses() {
        h.t.course("Biology"); // hue 210

        Course course = courses.create(new CourseDraft("  Chemistry  ", "Atoms and bonds", "", "Ada", null));

        assertThat(course.title()).isEqualTo("Chemistry");
        assertThat(course.origin()).isEqualTo(CourseOrigin.LOCAL);
        assertThat(course.status()).isEqualTo(CourseStatus.DRAFT);
        assertThat(course.visibility()).isEqualTo(Visibility.PRIVATE);
        assertThat(course.coverHue()).isEqualTo(28);
        assertThat(h.t.repos.courses().get(course.id())).isEqualTo(course);
        assertThat(courses.summary(course.id()).enrolled()).isFalse();
        assertThat(h.events(CourseChanged.class)).containsExactly(new CourseChanged(course.id(), ChangeType.CREATED));
        assertThat(courses.create(new CourseDraft("Physics", "", "", "", 95)).coverHue()).isEqualTo(95);
    }

    @Test
    void picksTheOfferedHueFarthestFromThoseInUse() {
        assertThat(CourseServiceImpl.pickHue(List.of())).isEqualTo(210);
        assertThat(CourseServiceImpl.pickHue(List.of(210))).isEqualTo(28);
        assertThat(CourseServiceImpl.pickHue(List.of(210, 28))).isEqualTo(120);
    }

    @Test
    void explainsWhatToFixInCourseDetails() {
        assertMessage(() -> courses.create(CourseDraft.titled("   ")), CourseServiceImpl.TITLE_REQUIRED);
        assertMessage(() -> courses.create(CourseDraft.titled("x".repeat(201))), CourseServiceImpl.TEXT_TOO_LONG);
        assertMessage(() -> courses.create(new CourseDraft("Biology", "x".repeat(301), "", "", null)),
                CourseServiceImpl.TEXT_TOO_LONG);
        assertMessage(() -> courses.create(new CourseDraft("Biology", "", "", "", 360)), CourseServiceImpl.HUE_INVALID);

        Course course = courses.create(CourseDraft.titled("Biology"));
        assertMessage(() -> courses.update(course.id(), new CourseUpdate("", "", "", "")), CourseServiceImpl.TITLE_REQUIRED);
        assertMessage(() -> courses.addModule(course.id(), " "), CourseServiceImpl.MODULE_TITLE_REQUIRED);
        assertMessage(() -> courses.setCoverHue(course.id(), -1), CourseServiceImpl.HUE_INVALID);
        assertThatThrownBy(() -> courses.get("missing")).isInstanceOf(NotFoundException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(UserMessage.COURSE_NOT_FOUND);
    }

    @Test
    void listsCoursesByScopeWithTheirCountsAndTodaysWork() {
        Course studying = h.t.enrolledCourse("Biology");
        CourseModule cells = h.t.module(studying, "Cells");
        LearningItem due = h.t.item(studying, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is a cell?");
        h.t.reviewed(due, h.t.now() - 1);
        h.t.item(studying, cells, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is a membrane?");
        h.t.item(studying, cells, ItemType.FLASHCARD, ItemStatus.DRAFT, "What is a nucleus?");
        h.t.document(studying, cells, "Cells");
        Course creating = h.t.course("Chemistry");
        LearningItem notStudied = h.t.item(creating, null, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is an atom?");
        h.t.reviewed(notStudied, h.t.now() - 1);
        Course shared = h.importedCourse("Geology");
        Course archived = h.t.enrolledCourse("History");
        h.t.archive(archived);

        assertThat(courses.list(CourseFilter.all())).extracting(CourseSummary::title)
                .containsExactlyInAnyOrder("Biology", "Chemistry", "Geology");
        assertThat(courses.list(CourseFilter.all().withArchived(true))).extracting(CourseSummary::title)
                .containsExactlyInAnyOrder("Biology", "Chemistry", "Geology", "History");
        assertThat(courses.list(CourseFilter.studying())).extracting(CourseSummary::title)
                .containsExactlyInAnyOrder("Biology", "Geology");
        assertThat(courses.list(CourseFilter.creating())).extracting(CourseSummary::title)
                .containsExactlyInAnyOrder("Biology", "Chemistry");
        assertThat(courses.list(CourseFilter.all().withSearch("bio"))).extracting(CourseSummary::title)
                .containsExactly("Biology");
        assertThat(courses.list(CourseFilter.all().withSearch("100%"))).isEmpty();

        CourseSummary biology = courses.list(CourseFilter.studying().withSearch("Biology")).getFirst();
        assertThat(biology.enrolled()).isTrue();
        assertThat(biology.createdHere()).isTrue();
        assertThat(biology.moduleCount()).isEqualTo(1);
        assertThat(biology.itemCount()).isEqualTo(2);
        assertThat(biology.draftItemCount()).isEqualTo(1);
        assertThat(biology.dueCount()).isEqualTo(1);
        assertThat(biology.newItemCount()).isEqualTo(1);
        assertThat(biology.documentCount()).isEqualTo(1);
        assertThat(biology.documentsProcessing()).isEqualTo(1);
        assertThat(biology.progress()).isEqualTo(0.5);
        assertThat(courses.summary(studying.id())).isEqualTo(biology);

        // Work is due only in courses being studied.
        assertThat(courses.summary(creating.id()).dueCount()).isZero();
        assertThat(courses.summary(shared.id()).createdHere()).isFalse();
    }

    @Test
    void listsRecentlyOpenedCoursesFirst() {
        Course first = h.t.course("Biology");
        Course second = h.t.course("Chemistry");
        h.t.clock.advance(Duration.ofDays(1));
        courses.markOpened(first.id());

        assertThat(courses.list(CourseFilter.all())).extracting(CourseSummary::id).containsExactly(first.id(), second.id());
        assertThat(courses.summary(first.id()).lastOpenedAt()).isEqualTo(h.t.now());
    }

    @Test
    void showsModulesInOrderWithTheirWorkAndConceptsWithMastery() {
        Course course = h.t.enrolledCourse("Biology");
        CourseModule cells = h.t.module(course, "Cells");
        CourseModule tissues = h.t.module(course, "Tissues");
        LearningItem item = h.t.item(course, tissues, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is a tissue?");
        h.t.reviewed(item, h.t.now() - 1);
        h.t.item(course, tissues, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is an organ?");
        Concept membrane = h.t.concept(course, cells, "Membrane");
        Concept epithelium = h.t.concept(course, tissues, "Epithelium");
        h.t.repos.items().setConcepts(item.id(), List.of(epithelium.id()));
        ConceptMastery known = new ConceptMastery(membrane.id(), "Membrane", cells.id(), 0.8, 0.9, 3, 3, 9);
        courses = new CourseServiceImpl(h.t.repos, h.events, h.t.clock, h.jobs, h.index, h.pdfs, id -> List.of(known));

        CourseDetail detail = courses.get(course.id());

        assertThat(detail.modules()).extracting(ModuleSummary::title).containsExactly("Cells", "Tissues");
        ModuleSummary second = detail.modules().get(1);
        assertThat(second.itemCount()).isEqualTo(2);
        assertThat(second.dueCount()).isEqualTo(1);
        assertThat(second.completion()).isEqualTo(0.5);
        assertThat(detail.latestVersion()).isNull();
        assertThat(detail.concepts()).extracting(ConceptMastery::name, ConceptMastery::mastery, ConceptMastery::itemCount,
                        ConceptMastery::reviewedItemCount)
                .containsExactly(tuple("Membrane", 0.8, 3, 3), tuple("Epithelium", null, 1, 1));

        courses = new CourseServiceImpl(h.t.repos, h.events, h.t.clock, h.jobs, h.index, h.pdfs, id -> {
            throw new IllegalStateException("broken");
        });
        assertThat(courses.get(course.id()).concepts()).extracting(ConceptMastery::name, ConceptMastery::mastery)
                .containsExactly(tuple("Membrane", null), tuple("Epithelium", null));
    }

    @Test
    void coursesSharedWithTheLearnerCanBeRecolouredAndStudiedButNotChanged() {
        Course shared = h.importedCourse("Geology");
        CourseModule rocks = h.t.module(shared, "Rocks");

        for (Runnable change : List.<Runnable>of(
                () -> courses.update(shared.id(), new CourseUpdate("Rocks", "", "", "")),
                () -> courses.setVisibility(shared.id(), Visibility.SHAREABLE),
                () -> courses.publish(shared.id(), ""),
                () -> courses.addModule(shared.id(), "Minerals"),
                () -> courses.renameModule(rocks.id(), "Stones"),
                () -> courses.setModuleSummary(rocks.id(), "All about rocks"),
                () -> courses.reorderModules(shared.id(), List.of(rocks.id())),
                () -> courses.deleteModule(rocks.id()))) {
            assertThatThrownBy(change::run).isInstanceOf(ConflictException.class)
                    .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(ServiceSupport.SHARED_COURSE);
        }

        courses.setCoverHue(shared.id(), 300);
        courses.leave(shared.id());
        assertThat(courses.summary(shared.id()).coverHue()).isEqualTo(300);
        assertThat(courses.summary(shared.id()).enrolled()).isFalse();
    }

    @Test
    void updatesDetailsAndVisibility() {
        Course course = courses.create(CourseDraft.titled("Biology"));
        h.clearEvents();

        Course updated = courses.update(course.id(), new CourseUpdate("Cell biology", " Cells ", "About cells", "Ada"));
        courses.setVisibility(course.id(), Visibility.SHAREABLE);
        courses.setVisibility(course.id(), Visibility.SHAREABLE);

        assertThat(updated.title()).isEqualTo("Cell biology");
        assertThat(updated.subtitle()).isEqualTo("Cells");
        assertThat(h.t.repos.courses().get(course.id()).visibility()).isEqualTo(Visibility.SHAREABLE);
        assertThat(h.events(CourseChanged.class)).hasSize(2);
    }

    @Test
    void publishesTheApprovedItemsAndRecordsIt() {
        Course course = h.t.course("Biology");
        h.t.item(course, null, ItemType.FLASHCARD, ItemStatus.DRAFT, "Draft");
        assertMessage(() -> courses.publish(course.id(), "First"), CourseServiceImpl.NOTHING_TO_PUBLISH);

        LearningItem a = h.t.item(course, null, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is a cell?");
        LearningItem b = h.t.item(course, null, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is a membrane?");
        CourseVersion first = courses.publish(course.id(), " First ");
        CourseVersion second = courses.publish(course.id(), null);

        assertThat(first.number()).isEqualTo(1);
        assertThat(first.notes()).isEqualTo("First");
        assertThat(second.number()).isEqualTo(2);
        assertThat(h.t.repos.courses().versionItemIds(first.id())).containsExactlyInAnyOrder(a.id(), b.id());
        assertThat(courses.versions(course.id())).extracting(CourseVersion::number).containsExactly(2, 1);
        CourseSummary summary = courses.summary(course.id());
        assertThat(summary.status()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(summary.versionNumber()).isEqualTo(2);
        assertThat(courses.get(course.id()).latestVersion()).isEqualTo(second);
        assertThat(h.t.repos.audit().listForEntity(EntityType.COURSE, course.id()))
                .extracting(AuditLogEntry::action, AuditLogEntry::detail)
                .containsExactlyInAnyOrder(tuple("publish", "version 1, 2 items"), tuple("publish", "version 2, 2 items"));
    }

    @Test
    void archivingHidesACourseFromStudyAndUnarchivingRestoresItsStatus() {
        Course draft = h.t.enrolledCourse("Biology");
        Course published = h.t.enrolledCourse("Chemistry");
        h.t.item(published, null, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is an atom?");
        courses.publish(published.id(), "");
        h.clearEvents();

        courses.archive(draft.id());
        courses.archive(draft.id());
        courses.archive(published.id());
        assertThat(courses.list(CourseFilter.studying())).isEmpty();
        // Publishing an archived course keeps it archived.
        courses.publish(published.id(), "");
        assertThat(courses.summary(published.id()).isArchived()).isTrue();

        courses.unarchive(draft.id());
        courses.unarchive(published.id());
        assertThat(courses.summary(draft.id()).status()).isEqualTo(CourseStatus.DRAFT);
        assertThat(courses.summary(published.id()).status()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(h.events(ProgressChanged.class)).extracting(ProgressChanged::courseId)
                .containsExactly(draft.id(), published.id(), draft.id(), published.id());
    }

    @Test
    void deletingACourseRemovesItsFilesPassagesAndWorkAndRecordsIt() {
        Course course = h.t.enrolledCourse("Biology");
        CourseModule cells = h.t.module(course, "Cells");
        Document document = h.t.document(course, cells, "Cells");
        Path file = h.storeFile(document);
        Course other = h.t.course("Chemistry");
        Document otherDocument = h.t.document(other, null, "Atoms");
        Path otherFile = h.storeFile(otherDocument);
        Chunk chunk = h.t.chunk(document, 0, "Cells are the unit of life.");
        Chunk otherChunk = h.t.chunk(otherDocument, 0, "Atoms are small.");
        h.index.upsert(List.of(indexed(chunk, course), indexed(otherChunk, other)));
        h.index.commit();
        h.jobs.enqueue(JobSpec.of(JobKind.INGEST_DOCUMENT, document.id(), null));
        h.jobs.enqueue(JobSpec.of(JobKind.GENERATE_ITEMS, cells.id(), null));
        h.jobs.enqueue(JobSpec.of(JobKind.INGEST_DOCUMENT, otherDocument.id(), null));
        h.clearEvents();

        courses.delete(course.id());

        assertThat(h.t.repos.courses().find(course.id())).isEmpty();
        assertThat(h.t.repos.documents().find(document.id())).isEmpty();
        assertThat(file).doesNotExist();
        assertThat(otherFile).exists();
        assertThat(h.index.chunks()).containsOnlyKeys(otherChunk.id());
        assertThat(h.jobs(JobKind.INGEST_DOCUMENT, JobStatus.QUEUED)).extracting(j -> j.subjectId())
                .containsExactly(otherDocument.id());
        assertThat(h.jobs(JobKind.GENERATE_ITEMS, JobStatus.CANCELLED)).hasSize(1);
        assertThat(h.t.repos.audit().listForEntity(EntityType.COURSE, course.id()))
                .extracting(AuditLogEntry::action, AuditLogEntry::detail)
                .containsExactly(tuple("delete", "Biology"));
        assertThat(h.events(CourseChanged.class)).containsExactly(new CourseChanged(course.id(), ChangeType.DELETED));
        assertThat(h.events(ProgressChanged.class)).containsExactly(new ProgressChanged(course.id()));
    }

    @Test
    void aFileSharedWithAnotherCourseStaysUntilNoDocumentUsesIt() {
        Course biology = h.t.course("Biology");
        Course chemistry = h.t.course("Chemistry");
        Document document = h.t.document(biology, null, "Notes");
        Document copy = new Document(Ids.newId(), chemistry.id(), null, "Notes", "Notes.pdf",
                document.fileSha256(), document.byteSize(), 0, DocumentStatus.QUEUED, 0, null, null, h.t.now(), h.t.now());
        h.t.repos.documents().insert(copy);
        Path file = h.storeFile(document);

        courses.delete(biology.id());
        assertThat(file).exists();
        courses.delete(chemistry.id());
        assertThat(file).doesNotExist();
    }

    @Test
    void deletingStillWorksWhenSearchCannotBeUpdated() {
        Course course = h.t.course("Biology");
        h.index.failNextWrites.set(1);

        courses.delete(course.id());

        assertThat(h.t.repos.courses().find(course.id())).isEmpty();
        assertThat(h.jobs(JobKind.REBUILD_INDEX, JobStatus.QUEUED)).hasSize(1);
    }

    @Test
    void addsRenamesReordersAndDeletesModules() {
        Course course = courses.create(CourseDraft.titled("Biology"));
        CourseModule a = courses.addModule(course.id(), " Cells ");
        CourseModule b = courses.addModule(course.id(), "Tissues");
        CourseModule c = courses.addModule(course.id(), "Organs");
        Document document = h.t.document(course, b, "Tissues");

        assertThat(a.title()).isEqualTo("Cells");
        assertThat(courses.renameModule(a.id(), "Cell biology").title()).isEqualTo("Cell biology");
        assertThat(courses.setModuleSummary(a.id(), " What cells are ").summary()).isEqualTo("What cells are");
        courses.reorderModules(course.id(), List.of(c.id(), a.id()));
        assertThat(courses.modules(course.id())).extracting(CourseModule::id).containsExactly(c.id(), a.id(), b.id());

        courses.deleteModule(b.id());
        assertThat(courses.modules(course.id())).extracting(CourseModule::id).containsExactly(c.id(), a.id());
        assertThat(h.t.repos.documents().get(document.id()).moduleId()).isNull();
        Course other = h.t.course("Chemistry");
        assertThatThrownBy(() -> courses.renameModule("missing", "x")).isInstanceOf(NotFoundException.class);
        assertThat(courses.modules(other.id())).isEmpty();
    }

    @Test
    void enrollingAndLeavingAnnounceOnlyRealChanges() {
        Course course = h.t.course("Biology");

        courses.enrol(course.id());
        courses.enrol(course.id());
        assertThat(courses.summary(course.id()).enrolled()).isTrue();
        courses.leave(course.id());
        courses.leave(course.id());
        assertThat(courses.summary(course.id()).enrolled()).isFalse();

        assertThat(h.events(ProgressChanged.class)).hasSize(2);
    }

    private static IndexedChunk indexed(Chunk chunk, Course course) {
        return new IndexedChunk(chunk.id(), chunk.documentId(), course.id(), chunk.moduleId(), "Title", chunk.pageStart(),
                chunk.pageEnd(), chunk.sectionTitle(), chunk.text(), new float[] {1f, 0f});
    }

    static void assertMessage(Runnable call, UserMessage message) {
        assertThatThrownBy(call::run).isInstanceOf(LearnSphereException.class)
                .extracting(e -> ((LearnSphereException) e).userMessage()).isEqualTo(message);
    }
}
