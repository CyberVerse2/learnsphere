package edu.learnsphere.core.sharing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import edu.learnsphere.core.error.ConflictException;
import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.AuditLogEntry;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.ExportOptions;
import edu.learnsphere.core.service.ImportOutcome;
import edu.learnsphere.core.service.ImportResult;
import edu.learnsphere.core.service.JobSpec;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.PackageInfo;
import edu.learnsphere.core.sharing.SharingProfile.Made;
import edu.learnsphere.core.spi.IndexedChunk;
import edu.learnsphere.core.util.Json;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CourseSharingTest {

    @TempDir
    Path dir;

    private SharingProfile creator;
    private SharingProfile learner;

    @BeforeEach
    void setUp() {
        creator = SharingProfile.open(dir.resolve("creator"));
        learner = SharingProfile.open(dir.resolve("learner"));
    }

    @AfterEach
    void tearDown() {
        learner.close();
        creator.close();
    }

    // ---- round trip ---------------------------------------------------------------------------------

    @Test
    void exportedCourseImportsWithItsContentPdfsAndSearchablePassages() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("out/cell-biology.learnsphere"));

        PackageInfo info = learner.sharing.inspect(file);
        assertThat(info.title()).isEqualTo("Cell Biology");
        assertThat(info.subtitle()).isEqualTo("From membranes to ATP");
        assertThat(info.authorName()).isEqualTo("Ada");
        assertThat(info.versionNumber()).isEqualTo(1);
        assertThat(info.itemCount()).isEqualTo(3);
        assertThat(info.moduleCount()).isEqualTo(2);
        assertThat(info.documentCount()).isEqualTo(2);
        assertThat(info.protectedByCode()).isFalse();
        assertThat(info.existingCourseId()).isNull();
        assertThat(info.byteSize()).isEqualTo(Files.size(file));

        ImportResult result = learner.importCourse(file, null);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.CREATED);
        assertThat(result.added()).isEqualTo(3);
        assertThat(result.courseTitle()).isEqualTo("Cell Biology");
        Course course = learner.t.repos.courses().get(result.courseId());
        assertThat(course.id()).isNotEqualTo(made.course().id());
        assertThat(course.origin()).isEqualTo(CourseOrigin.IMPORTED);
        assertThat(course.visibility()).isEqualTo(Visibility.SHAREABLE);
        assertThat(course.status()).isEqualTo(CourseStatus.PUBLISHED);
        assertThat(course.versionNumber()).isEqualTo(1);
        assertThat(course.packageId()).isEqualTo(made.course().packageId());
        assertThat(course.description()).isEqualTo("An introduction.");
        assertThat(learner.t.repos.courses().isEnrolled(course.id())).isTrue();
        assertThat(learner.t.repos.courses().listVersions(course.id())).singleElement()
                .satisfies(v -> assertThat(v.notes()).isEqualTo("First release"));

        assertThat(learner.t.repos.modules().listByCourse(course.id())).extracting(CourseModule::id, CourseModule::title)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(made.cells().id(), "Cells"),
                        org.assertj.core.groups.Tuple.tuple(made.energy().id(), "Energy"));

        List<LearningItem> items = learner.t.repos.items().listByCourse(course.id());
        assertThat(items).extracting(LearningItem::id)
                .containsExactlyInAnyOrder(made.mcq().id(), made.shortAnswer().id(), made.flashcard().id());
        LearningItem mcq = learner.t.repos.items().get(made.mcq().id());
        assertThat(mcq.status()).isEqualTo(ItemStatus.APPROVED);
        assertThat(mcq.prompt()).isEqualTo(made.mcq().prompt());
        assertThat(mcq.distractors()).containsExactly("wrong 1", "wrong 2");
        assertThat(mcq.sourceChunkId()).isEqualTo(made.membrane().id());
        assertThat(mcq.sourcePage()).isEqualTo(1);
        assertThat(mcq.courseId()).isEqualTo(course.id());
        assertThat(learner.t.repos.items().conceptLinks(course.id())).isEqualTo(Map.of(
                made.mcq().id(), List.of(made.membraneConcept().id()),
                made.shortAnswer().id(), List.of(made.membraneConcept().id()),
                made.flashcard().id(), List.of(made.atpConcept().id())));

        List<Document> documents = learner.t.repos.documents().listByCourse(course.id());
        assertThat(documents).extracting(Document::id)
                .containsExactlyInAnyOrder(made.structure().id(), made.energyDoc().id());
        assertThat(documents).allSatisfy(d -> {
            assertThat(d.status()).isEqualTo(DocumentStatus.READY);
            assertThat(learner.paths.pdfFile(d.fileSha256())).hasSameBinaryContentAs(creator.paths.pdfFile(d.fileSha256()));
        });
        assertThat(learner.t.repos.documents().listPages(made.structure().id()))
                .extracting(DocumentPage::pageNumber).containsExactly(1, 2);
        assertThat(learner.t.repos.documents().listPages(made.structure().id()))
                .allSatisfy(p -> assertThat(p.isUsableAsEvidence()).isTrue());

        List<Chunk> chunks = learner.t.repos.chunks().listByCourse(course.id());
        assertThat(chunks).hasSize(3).allSatisfy(c -> {
            assertThat(c.embeddingModel()).isEqualTo(SharingProfile.MODEL);
            assertThat(c.embedding()).isEqualTo(CountingEmbedder.vector(c.text(), SharingProfile.DIMENSIONS));
        });
        assertThat(learner.embedder.embedded).hasValue(0);
        assertThat(learner.index.chunkIdsOfCourse(course.id()))
                .containsExactlyInAnyOrder(made.membrane().id(), made.organelles().id(), made.atp().id());
        IndexedChunk indexed = learner.index.get(made.atp().id());
        assertThat(indexed.documentTitle()).isEqualTo("Energy");
        assertThat(indexed.moduleId()).isEqualTo(made.energy().id());

        assertThat(creator.t.repos.audit().listForEntity(edu.learnsphere.core.model.EntityType.COURSE, made.course().id()))
                .extracting(AuditLogEntry::action).contains("export_package");
        assertThat(learner.t.repos.audit().listForEntity(edu.learnsphere.core.model.EntityType.COURSE, course.id()))
                .extracting(AuditLogEntry::action).contains("import_package");
    }

    @Test
    void passagesFromAnotherEmbedderAreEmbeddedAgainOnImport() {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));
        try (SharingProfile other = SharingProfile.open(dir.resolve("other"), "other-embed")) {
            ImportResult result = other.importCourse(file, null);

            assertThat(other.embedder.embedded).hasValue(3);
            assertThat(other.t.repos.chunks().listByCourse(result.courseId()))
                    .allSatisfy(c -> assertThat(c.embeddingModel()).isEqualTo("other-embed"));
            assertThat(other.index.chunkIdsOfCourse(result.courseId())).hasSize(3);
        }
    }

    @Test
    void packageWithoutPdfsImportsPagesAndPassages() {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = dir.resolve("no-pdfs.learnsphere");
        creator.sharing.exportCourse(made.course().id(), file, new ExportOptions(null, false));

        ImportResult result = learner.importCourse(file, null);

        assertThat(learner.storedPdfs()).isEmpty();
        assertThat(learner.t.repos.documents().listByCourse(result.courseId())).hasSize(2);
        assertThat(learner.index.chunkIdsOfCourse(result.courseId())).hasSize(3);
    }

    // ---- updates ------------------------------------------------------------------------------------

    @Test
    void updateKeepsProgressAddsUpdatesAndRetiresItems() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        String courseId = learner.importCourse(creator.export(made.course(), dir.resolve("v1.learnsphere")), null).courseId();
        learner.answer(courseId, made.mcq().id(), true, false);
        learner.answer(courseId, made.flashcard().id(), true);
        learner.t.reviewed(learner.t.repos.items().get(made.mcq().id()), learner.t.now() + 86_400_000L);
        Path energyPdf = learner.paths.pdfFile(made.energyDoc().fileSha256());
        assertThat(energyPdf).exists();

        // Version 2: the short answer is reworded, the flashcard and the energy PDF are gone, an item is added.
        LearningItem reworded = new LearningItem(made.shortAnswer().id(), made.course().id(), made.cells().id(),
                ItemType.SHORT_ANSWER, "What do organelles do in a cell?", made.shortAnswer().answer(), "because", List.of(),
                made.shortAnswer().difficulty(), made.organelles().id(), 2, made.shortAnswer().origin(), ItemStatus.APPROVED,
                made.shortAnswer().support(), null, made.shortAnswer().createdAt(), creator.t.now());
        creator.t.repos.items().update(reworded);
        creator.t.repos.items().setStatus(made.flashcard().id(), ItemStatus.RETIRED);
        creator.t.repos.documents().delete(made.energyDoc().id());
        LearningItem added = creator.item(made.course(), made.cells(), ItemType.TRUE_FALSE, "Cells have membranes.",
                made.membrane().id(), 1, ItemStatus.APPROVED);
        creator.publish(made.course(), "Second release", made.mcq(), reworded, added);
        Path v2 = creator.export(made.course(), dir.resolve("v2.learnsphere"));

        PackageInfo info = learner.sharing.inspect(v2);
        assertThat(info.isUpdate()).isTrue();
        assertThat(info.existingCourseId()).isEqualTo(courseId);
        assertThat(info.installedVersion()).isEqualTo(1);
        assertThat(info.isNewer()).isTrue();
        long structureChunkCreated = learner.t.repos.chunks().find(made.membrane().id()).orElseThrow().createdAt();

        ImportResult result = learner.importCourse(v2, null);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.UPDATED);
        assertThat(result.courseId()).isEqualTo(courseId);
        assertThat(result.versionNumber()).isEqualTo(2);
        assertThat(result.added()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.retired()).isEqualTo(1);

        assertThat(learner.t.repos.items().get(made.shortAnswer().id()).prompt()).isEqualTo("What do organelles do in a cell?");
        assertThat(learner.t.repos.items().get(made.flashcard().id()).status()).isEqualTo(ItemStatus.RETIRED);
        assertThat(learner.t.repos.items().get(added.id()).status()).isEqualTo(ItemStatus.APPROVED);
        assertThat(learner.t.repos.reviews().find(made.mcq().id())).isPresent();
        assertThat(learner.t.repos.sessions().listAttemptsForItem(made.mcq().id(), 10)).hasSize(2);
        assertThat(learner.t.repos.sessions().listAttemptsForItem(made.flashcard().id(), 10)).hasSize(1);
        assertThat(learner.t.repos.courses().get(courseId).versionNumber()).isEqualTo(2);
        assertThat(learner.t.repos.courses().listVersions(courseId)).hasSize(2);

        assertThat(learner.t.repos.documents().find(made.energyDoc().id())).isEmpty();
        assertThat(energyPdf).doesNotExist();
        assertThat(learner.index.chunkIdsOfCourse(courseId))
                .containsExactlyInAnyOrder(made.membrane().id(), made.organelles().id());
        assertThat(learner.t.repos.chunks().find(made.membrane().id()).orElseThrow().createdAt())
                .as("unchanged documents are kept as they are").isEqualTo(structureChunkCreated);
        assertThat(learner.embedder.embedded).hasValue(0);

        ImportResult again = learner.importCourse(v2, null);
        assertThat(again.outcome()).isEqualTo(ImportOutcome.ALREADY_CURRENT);
        assertThat(again.added() + again.updated() + again.retired()).isZero();
    }

    @Test
    void fixedPageTextReplacesTheDocumentWithoutCountingItsItemsAsUpdated() {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        String courseId = learner.importCourse(creator.export(made.course(), dir.resolve("v1.learnsphere")), null).courseId();
        learner.answer(courseId, made.mcq().id(), true);

        creator.t.repos.documents().updatePageText(made.structure().id(), 1, "The membrane surrounds every cell.", false);
        creator.publish(made.course(), "Typo fixed", made.mcq(), made.shortAnswer(), made.flashcard());
        ImportResult result = learner.importCourse(creator.export(made.course(), dir.resolve("v2.learnsphere")), null);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.UPDATED);
        assertThat(result.added()).isZero();
        assertThat(result.updated()).as("no question changed").isZero();
        assertThat(result.retired()).isZero();
        assertThat(learner.t.repos.documents().listPages(made.structure().id()).getFirst().text())
                .isEqualTo("The membrane surrounds every cell.");
        assertThat(learner.t.repos.items().get(made.mcq().id()).sourceChunkId()).isEqualTo(made.membrane().id());
        assertThat(learner.t.repos.items().get(made.shortAnswer().id()).sourceChunkId()).isEqualTo(made.organelles().id());
        assertThat(learner.t.repos.items().conceptLinks(courseId)).containsEntry(made.mcq().id(),
                List.of(made.membraneConcept().id()));
        assertThat(learner.t.repos.sessions().listAttemptsForItem(made.mcq().id(), 10)).hasSize(1);
        assertThat(learner.index.chunkIdsOfCourse(courseId)).hasSize(3);
    }

    @Test
    void removedModuleMovesItsContentAndKeepsProgress() {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        String courseId = learner.importCourse(creator.export(made.course(), dir.resolve("v1.learnsphere")), null).courseId();
        learner.answer(courseId, made.flashcard().id(), true);
        learner.t.reviewed(learner.t.repos.items().get(made.flashcard().id()), learner.t.now() + 86_400_000L);

        // Version 2: "Energy" is gone; its PDF and the flashcard move to a new "Review" module, the ATP concept
        // becomes course-wide.
        CourseModule review = creator.t.module(made.course(), "Review");
        creator.t.repos.modules().delete(made.energy().id());
        creator.t.repos.documents().setModule(made.energyDoc().id(), review.id());
        LearningItem flashcard = creator.t.repos.items().get(made.flashcard().id());
        creator.t.repos.items().update(new LearningItem(flashcard.id(), flashcard.courseId(), review.id(), flashcard.type(),
                flashcard.prompt(), flashcard.answer(), flashcard.explanation(), flashcard.distractors(),
                flashcard.difficulty(), flashcard.sourceChunkId(), flashcard.sourcePage(), flashcard.origin(),
                flashcard.status(), flashcard.support(), flashcard.supportNote(), flashcard.createdAt(), creator.t.now()));
        creator.publish(made.course(), "New modules", made.mcq(), made.shortAnswer(), made.flashcard());
        ImportResult result = learner.importCourse(creator.export(made.course(), dir.resolve("v2.learnsphere")), null);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.UPDATED);
        assertThat(result.added()).isZero();
        assertThat(result.updated()).as("only the flashcard moved").isEqualTo(1);
        assertThat(result.retired()).isZero();
        assertThat(learner.t.repos.modules().listByCourse(courseId)).extracting(CourseModule::id)
                .containsExactly(made.cells().id(), review.id());
        LearningItem moved = learner.t.repos.items().get(made.flashcard().id());
        assertThat(moved.moduleId()).isEqualTo(review.id());
        assertThat(moved.sourceChunkId()).isEqualTo(made.atp().id());
        assertThat(learner.t.repos.documents().get(made.energyDoc().id()).moduleId()).isEqualTo(review.id());
        assertThat(learner.t.repos.chunks().find(made.atp().id()).orElseThrow().moduleId()).isEqualTo(review.id());
        assertThat(learner.t.repos.concepts().find(made.atpConcept().id()).orElseThrow().moduleId()).isNull();
        assertThat(learner.t.repos.items().conceptLinks(courseId)).containsEntry(made.flashcard().id(),
                List.of(made.atpConcept().id()));
        assertThat(learner.index.get(made.atp().id()).moduleId()).isEqualTo(review.id());
        assertThat(learner.t.repos.sessions().listAttemptsForItem(made.flashcard().id(), 10)).hasSize(1);
        assertThat(learner.t.repos.reviews().find(made.flashcard().id())).isPresent();
    }

    @Test
    void conceptMovedToAnotherModuleKeepsItsItems() {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        String courseId = learner.importCourse(creator.export(made.course(), dir.resolve("v1.learnsphere")), null).courseId();
        Map<String, List<String>> linksBefore = learner.t.repos.items().conceptLinks(courseId);

        Concept membrane = made.membraneConcept();
        creator.t.repos.concepts().delete(membrane.id());
        creator.t.repos.concepts().insert(new Concept(membrane.id(), membrane.courseId(), made.energy().id(),
                membrane.name(), membrane.description(), membrane.createdAt(), creator.t.now()));
        creator.t.repos.items().setConcepts(made.mcq().id(), List.of(membrane.id()));
        creator.t.repos.items().setConcepts(made.shortAnswer().id(), List.of(membrane.id()));
        creator.publish(made.course(), "Concept moved", made.mcq(), made.shortAnswer(), made.flashcard());
        ImportResult result = learner.importCourse(creator.export(made.course(), dir.resolve("v2.learnsphere")), null);

        assertThat(result.outcome()).isEqualTo(ImportOutcome.UPDATED);
        assertThat(result.added() + result.updated() + result.retired()).isZero();
        assertThat(learner.t.repos.concepts().find(membrane.id()).orElseThrow().moduleId()).isEqualTo(made.energy().id());
        assertThat(learner.t.repos.items().conceptLinks(courseId)).isEqualTo(linksBefore);
    }

    @Test
    void importingAgainRepairsSearchAfterAFailedIndexWrite() {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));
        learner.index.failing.set(true);

        JobUpdate failed = learner.importAndWait(file, null, false);

        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);
        assertThat(learner.t.repos.courses().list()).hasSize(1);
        assertThat(learner.index.size()).isZero();

        learner.index.failing.set(false);
        ImportResult again = learner.importCourse(file, null);

        assertThat(again.outcome()).isEqualTo(ImportOutcome.ALREADY_CURRENT);
        assertThat(learner.index.chunkIdsOfCourse(again.courseId()))
                .containsExactlyInAnyOrder(made.membrane().id(), made.organelles().id(), made.atp().id());
    }

    @Test
    void olderVersionIsRefusedUnlessAllowed() {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path v1 = creator.export(made.course(), dir.resolve("v1.learnsphere"));
        creator.publish(made.course(), "Second release", made.mcq(), made.shortAnswer());
        Path v2 = creator.export(made.course(), dir.resolve("v2.learnsphere"));
        String courseId = learner.importCourse(v2, null).courseId();

        PackageInfo info = learner.sharing.inspect(v1);
        assertThat(info.isOlderThanInstalled()).isTrue();
        assertThat(info.canImport()).isFalse();
        assertThat(learner.sharing.inspect(v2).canImport()).isTrue();
        assertThatThrownBy(() -> learner.sharing.importCourse(v1, null))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.NEWER_VERSION_INSTALLED));

        ImportResult result = learner.importCourse(v1, null, true);
        assertThat(result.outcome()).isEqualTo(ImportOutcome.UPDATED);
        assertThat(result.added()).as("version 1 still had the flashcard").isEqualTo(1);
        assertThat(result.updated()).isZero();
        assertThat(result.retired()).isZero();
        assertThat(learner.t.repos.courses().get(courseId).versionNumber()).isEqualTo(1);
    }

    // ---- protected courses --------------------------------------------------------------------------

    @Test
    void protectedCourseNeedsTheRightCode() throws IOException {
        Made made = creator.createCourse(Visibility.PROTECTED);
        Path file = dir.resolve("protected.learnsphere");
        assertThatThrownBy(() -> creator.sharing.exportCourse(made.course().id(), file, ExportOptions.standard()))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.PROTECTION_CODE_NEEDED));
        char[] exportCode = "orchid".toCharArray();
        creator.sharing.exportCourse(made.course().id(), file, ExportOptions.protectedWith(exportCode));
        assertThat(exportCode).containsOnly('\0');

        String raw = rawEntries(file);
        assertThat(raw).doesNotContain(made.mcq().prompt()).doesNotContain("Organelles do the work");

        PackageInfo info = learner.sharing.inspect(file);
        assertThat(info.protectedByCode()).isTrue();
        assertThat(info.title()).isEqualTo("Cell Biology");

        assertThatThrownBy(() -> learner.sharing.importCourse(file, null))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.CODE_REQUIRED));
        char[] wrong = "lily".toCharArray();
        assertThatThrownBy(() -> learner.sharing.importCourse(file, wrong))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(UserMessage.WRONG_COURSE_CODE));
        assertThat(wrong).containsOnly('\0');
        assertThat(learner.t.repos.courses().list()).isEmpty();

        char[] right = " orchid ".toCharArray();
        ImportResult result = learner.importCourse(file, right);
        assertThat(right).containsOnly('\0');
        assertThat(result.outcome()).isEqualTo(ImportOutcome.CREATED);
        assertThat(learner.t.repos.courses().get(result.courseId()).visibility()).isEqualTo(Visibility.PROTECTED);
        assertThat(learner.t.repos.items().listByCourse(result.courseId())).hasSize(3);
        assertThat(learner.paths.pdfFile(made.structure().fileSha256()))
                .hasSameBinaryContentAs(creator.paths.pdfFile(made.structure().fileSha256()));
        assertThat(learner.index.chunkIdsOfCourse(result.courseId())).hasSize(3);
    }

    @Test
    void protectedImportInterruptedByARestartEndsFailed() {
        Made made = creator.createCourse(Visibility.PROTECTED);
        Path file = dir.resolve("protected.learnsphere");
        creator.sharing.exportCourse(made.course().id(), file, ExportOptions.protectedWith("orchid".toCharArray()));

        // A job left from an earlier run: its key was only ever in memory.
        JobUpdate queued = learner.jobs.enqueue(new JobSpec(JobKind.IMPORT_PACKAGE, null,
                Json.write(new ImportJob(file.toAbsolutePath().toString(), "gone-after-restart", false)),
                JobSpec.USER_PRIORITY, false));
        JobUpdate done = learner.await(queued.jobId());

        assertThat(done.status()).isEqualTo(JobStatus.FAILED);
        assertThat(done.message()).isEqualTo(SharingMessages.IMPORT_INTERRUPTED.text());
        assertThat(learner.t.repos.courses().list()).isEmpty();
    }

    @Test
    void tamperedCiphertextIsDetectedEvenWithAMatchingHash() throws IOException {
        Made made = creator.createCourse(Visibility.PROTECTED);
        Path file = dir.resolve("protected.learnsphere");
        creator.sharing.exportCourse(made.course().id(), file, ExportOptions.protectedWith("orchid".toCharArray()));
        Path tampered = rewrite(file, dir.resolve("tampered.learnsphere"), entries -> {
            byte[] course = entries.get(PackageFormat.COURSE);
            course[course.length / 2] ^= 0x01;
            patchManifest(entries, PackageFormat.COURSE, course);
        });

        assertThatThrownBy(() -> learner.sharing.importCourse(tampered, "orchid".toCharArray()))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.COURSE_FILE_DAMAGED));
    }

    // ---- damaged files ------------------------------------------------------------------------------

    @Test
    void changedEntryIsRejected() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));
        Path tampered = rewrite(file, dir.resolve("tampered.learnsphere"), entries -> {
            String json = new String(entries.get(PackageFormat.COURSE), StandardCharsets.UTF_8);
            String changed = json.replace("What surrounds the cell?", "What surrounds the CELL?");
            assertThat(changed).isNotEqualTo(json);
            entries.put(PackageFormat.COURSE, changed.getBytes(StandardCharsets.UTF_8));
        });

        assertDamaged(tampered);
    }

    @Test
    void truncatedFileIsRejected() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));
        byte[] bytes = Files.readAllBytes(file);
        Path truncated = Files.write(dir.resolve("truncated.learnsphere"), Arrays.copyOf(bytes, bytes.length / 2));

        assertThatThrownBy(() -> learner.sharing.inspect(truncated)).isInstanceOf(InvalidInputException.class);
        assertThatThrownBy(() -> learner.sharing.importCourse(truncated, null)).isInstanceOf(InvalidInputException.class);
        assertThat(learner.t.repos.courses().list()).isEmpty();
    }

    @Test
    void tamperedPdfFailsTheJobAndLeavesNothingBehind() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));
        String pdfEntry = PackageFormat.documentEntry(made.structure().fileSha256());
        Path tampered = rewrite(file, dir.resolve("tampered.learnsphere"), entries -> {
            byte[] pdf = entries.get(pdfEntry);
            pdf[pdf.length - 10] ^= 0x01;
            patchManifest(entries, pdfEntry, pdf);
        });

        JobUpdate done = learner.importAndWait(tampered, null, false);

        assertThat(done.status()).isEqualTo(JobStatus.FAILED);
        assertThat(done.message()).isEqualTo(SharingMessages.COURSE_FILE_DAMAGED.text());
        assertThat(learner.t.repos.courses().list()).isEmpty();
        assertThat(learner.storedPdfs()).as("no stray copies").isEmpty();
        assertThat(learner.index.size()).isZero();
    }

    @Test
    void changedPassagesFailTheJobAndLeaveNothingBehind() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));
        Path tampered = rewrite(file, dir.resolve("tampered.learnsphere"), entries -> {
            String json = new String(entries.get(PackageFormat.CHUNKS), StandardCharsets.UTF_8);
            String changed = json.replace("ATP stores energy.", "ATP stores energy!");
            assertThat(changed).isNotEqualTo(json);
            entries.put(PackageFormat.CHUNKS, changed.getBytes(StandardCharsets.UTF_8));
        });

        assertJobDamaged(tampered);
    }

    @Test
    void invalidPassageFailsTheJobEvenWithAMatchingHash() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));
        Path tampered = rewrite(file, dir.resolve("tampered.learnsphere"), entries -> {
            String json = new String(entries.get(PackageFormat.CHUNKS), StandardCharsets.UTF_8);
            String changed = json.replaceFirst("\"pageStart\":1", "\"pageStart\":0");
            assertThat(changed).isNotEqualTo(json);
            byte[] bytes = changed.getBytes(StandardCharsets.UTF_8);
            entries.put(PackageFormat.CHUNKS, bytes);
            patchManifest(entries, PackageFormat.CHUNKS, bytes);
        });

        assertJobDamaged(tampered);
    }

    @Test
    void truncatedPagesFailTheJob() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));
        Path tampered = rewrite(file, dir.resolve("tampered.learnsphere"), entries -> {
            byte[] pages = entries.get(PackageFormat.PAGES);
            byte[] cut = Arrays.copyOf(pages, pages.length - 5);
            entries.put(PackageFormat.PAGES, cut);
            patchManifest(entries, PackageFormat.PAGES, cut);
        });

        assertJobDamaged(tampered);
    }

    @Test
    void pdfThatCannotBeStoredFailsWithoutBlamingTheCourseFile() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));
        File files = learner.paths.filesDir().toFile();
        // Windows can't make a folder read-only this way.
        Assumptions.assumeTrue(files.setWritable(false), "the folder can't be made read-only");
        JobUpdate done;
        try {
            Assumptions.assumeFalse(Files.isWritable(files.toPath()), "the folder stays writable for this user");
            done = learner.importAndWait(file, null, false);
        } finally {
            assertThat(files.setWritable(true)).isTrue();
        }

        assertThat(done.status()).isEqualTo(JobStatus.FAILED);
        assertThat(done.message()).isEqualTo(UserMessage.SOMETHING_WENT_WRONG.text());
        assertThat(learner.t.repos.courses().list()).isEmpty();
    }

    @Test
    void fullDiskIsReportedAsSuch() {
        Path target = learner.paths.pdfFile("0".repeat(64));

        assertThat(PackageReader.storageFailure(target, new IOException("No space left on device")).userMessage())
                .isEqualTo(UserMessage.DISK_FULL);
        assertThat(PackageReader.storageFailure(target, new java.nio.file.AccessDeniedException(target.toString()))
                .userMessage()).isEqualTo(UserMessage.SOMETHING_WENT_WRONG);
    }

    @Test
    void unexpectedEntriesAreRejectedAndNeverWritten() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("out/course.learnsphere"));
        Path tampered = rewrite(file, dir.resolve("out/evil.learnsphere"),
                entries -> entries.put("../evil.txt", "boo".getBytes(StandardCharsets.UTF_8)));

        assertDamaged(tampered);
        assertThat(dir.resolve("evil.txt")).doesNotExist();
        assertThat(learner.paths.root().resolve("evil.txt")).doesNotExist();
    }

    @Test
    void otherFilesAreNotCourses() throws IOException {
        Path text = Files.writeString(dir.resolve("notes.learnsphere"), "just some notes");
        assertThatThrownBy(() -> learner.sharing.inspect(text))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isIn(SharingMessages.NOT_A_COURSE_FILE,
                        SharingMessages.COURSE_FILE_DAMAGED));
        assertThatThrownBy(() -> learner.sharing.inspect(dir.resolve("missing.learnsphere")))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.FILE_NOT_OPENED));
    }

    @Test
    void laterFormatVersionAsksForAnUpdate() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));
        Path future = rewrite(file, dir.resolve("future.learnsphere"), entries -> {
            ObjectNode manifest = (ObjectNode) Json.tree(new String(entries.get(PackageFormat.MANIFEST),
                    StandardCharsets.UTF_8));
            manifest.put("formatVersion", 2);
            entries.put(PackageFormat.MANIFEST, Json.write(manifest).getBytes(StandardCharsets.UTF_8));
        });

        assertThatThrownBy(() -> learner.sharing.inspect(future))
                .isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.COURSE_FILE_TOO_NEW));
    }

    // ---- refusals -----------------------------------------------------------------------------------

    @Test
    void privateUnpublishedAndImportedCoursesCannotBeExported() {
        Made made = creator.createCourse(Visibility.PRIVATE);
        Path target = dir.resolve("course.learnsphere");
        assertThatThrownBy(() -> creator.export(made.course(), target))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.PRIVATE_COURSE));

        Course draft = creator.t.course("Draft");
        creator.t.repos.courses().setVisibility(draft.id(), Visibility.SHAREABLE);
        assertThatThrownBy(() -> creator.export(draft, target))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.NOT_PUBLISHED));
        assertThat(target).doesNotExist();

        creator.t.repos.courses().setVisibility(made.course().id(), Visibility.SHAREABLE);
        String imported = learner.importCourse(creator.export(made.course(), target), null).courseId();
        assertThatThrownBy(() -> learner.export(learner.t.repos.courses().get(imported), dir.resolve("again.learnsphere")))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.NOT_MADE_HERE));
    }

    @Test
    void courseLargerThanAPackageMayHoldIsNotExported() throws IOException {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        CourseExporter small = new CourseExporter(creator.t.repos, creator.paths, creator.t.clock,
                name -> name.equals(PackageFormat.CHUNKS) ? 100 : PackageFormat.plaintextLimit(name));
        Path target = dir.resolve("out/course.learnsphere");

        assertThatThrownBy(() -> small.export(made.course().id(), target, ExportOptions.standard()))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.COURSE_TOO_LARGE));
        assertThat(target).doesNotExist();
        try (var left = Files.list(target.getParent())) {
            assertThat(left).as("no partial file").isEmpty();
        }
    }

    @Test
    void exportReadsPagesAndPassagesWithoutBlockingOtherWritesAndNoticesChangedDocuments() {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        List<Boolean> writesWhileExporting = new java.util.ArrayList<>();
        CourseExporter exporter = new CourseExporter(creator.t.repos, creator.paths, creator.t.clock, name -> {
            if (name.equals(PackageFormat.CHUNKS)) {
                // Another thread writes while the export runs; then the PDF is processed again.
                writesWhileExporting.add(CompletableFuture.supplyAsync(() -> creator.t.repos.audit()
                        .append("probe", EntityType.COURSE, made.course().id(), null) != null).orTimeout(3, TimeUnit.SECONDS)
                        .join());
                creator.t.clock.advance(java.time.Duration.ofSeconds(1));
                creator.t.repos.documents().updateStatus(made.structure().id(), DocumentStatus.READY, 1, null);
            }
            return PackageFormat.plaintextLimit(name);
        });
        Path target = dir.resolve("course.learnsphere");

        assertThatThrownBy(() -> exporter.export(made.course().id(), target, ExportOptions.standard()))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.COURSE_CHANGED_WHILE_SAVING));
        assertThat(writesWhileExporting).containsExactly(true);
        assertThat(target).doesNotExist();
    }

    @Test
    void ownPackageCannotBeImportedIntoTheProfileThatMadeIt() {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));

        PackageInfo info = creator.sharing.inspect(file);
        assertThat(info.createdHere()).isTrue();
        assertThat(info.canImport()).isFalse();
        assertThatThrownBy(() -> creator.sharing.importCourse(file, null))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.COURSE_MADE_HERE));
    }

    @Test
    void packageWhoseIdsBelongToAnotherCourseIsRefused() {
        Made made = creator.createCourse(Visibility.SHAREABLE);
        Path file = creator.export(made.course(), dir.resolve("course.learnsphere"));
        Course own = learner.t.course("My notes");
        learner.t.repos.modules().insert(new CourseModule(made.cells().id(), own.id(), "Mine", "", 0, learner.t.now(),
                learner.t.now()));

        assertThatThrownBy(() -> learner.sharing.importCourse(file, null))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.COURSE_CLASHES));
    }

    // ---- helpers ------------------------------------------------------------------------------------

    /** The package passes the quick checks, then the import job fails as damaged and stores nothing. */
    private void assertJobDamaged(Path file) {
        JobUpdate done = learner.importAndWait(file, null, false);

        assertThat(done.status()).isEqualTo(JobStatus.FAILED);
        assertThat(done.message()).isEqualTo(SharingMessages.COURSE_FILE_DAMAGED.text());
        assertThat(learner.t.repos.courses().list()).isEmpty();
        assertThat(learner.storedPdfs()).as("no stray copies").isEmpty();
        assertThat(learner.index.size()).isZero();
    }

    private void assertDamaged(Path file) {
        assertThatThrownBy(() -> {
            JobUpdate done = learner.importAndWait(file, null, false);
            throw new AssertionError("Import was accepted: " + done);
        }).isInstanceOf(InvalidInputException.class)
                .satisfies(e -> assertThat(message(e)).isEqualTo(SharingMessages.COURSE_FILE_DAMAGED));
        assertThat(learner.t.repos.courses().list()).isEmpty();
    }

    private static UserMessage message(Throwable e) {
        return ((LearnSphereException) e).userMessage();
    }

    /** Copies a package, letting {@code change} edit its entries (by name, as stored). */
    static Path rewrite(Path source, Path target, Consumer<Map<String, byte[]>> change) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(source.toFile())) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement();
                try (InputStream in = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), in.readAllBytes());
                }
            }
        }
        change.accept(entries);
        Files.createDirectories(target.getParent());
        try (OutputStream out = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(out)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(e.getKey()));
                zip.write(e.getValue());
                zip.closeEntry();
            }
        }
        return target;
    }

    /** Points the manifest's record of {@code path} at {@code bytes}, as someone forging a package would. */
    static void patchManifest(Map<String, byte[]> entries, String path, byte[] bytes) {
        JsonNode manifest = Json.tree(new String(entries.get(PackageFormat.MANIFEST), StandardCharsets.UTF_8));
        for (JsonNode entry : manifest.get("entries")) {
            if (entry.get("path").asText().equals(path)) {
                ((ObjectNode) entry).put("sha256", SharingProfile.sha256(bytes));
                ((ObjectNode) entry).put("size", bytes.length);
            }
        }
        entries.put(PackageFormat.MANIFEST, Json.write(manifest).getBytes(StandardCharsets.UTF_8));
    }

    /** All entry bytes of a package except the manifest, as text. */
    private static String rawEntries(Path file) throws IOException {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!entry.getName().equals(PackageFormat.MANIFEST)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        in.transferTo(all);
                    }
                }
            }
        }
        return all.toString(StandardCharsets.ISO_8859_1);
    }
}
