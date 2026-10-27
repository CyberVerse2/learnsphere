package edu.learnsphere.core.sharing;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.model.Attempt;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.PageSource;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.SessionRecord;
import edu.learnsphere.core.model.SupportStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.service.ExportOptions;
import edu.learnsphere.core.service.ImportResult;
import edu.learnsphere.core.service.JobUpdate;
import edu.learnsphere.core.service.impl.JobQueueImpl;
import edu.learnsphere.core.service.impl.SharingServiceImpl;
import edu.learnsphere.core.testing.TestDb;
import edu.learnsphere.core.util.Ids;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * One profile for sharing tests: a real database and job queue in a temporary folder, an in-memory search
 * index and a counting embedder, and the sharing service with its import handler registered.
 */
final class SharingProfile implements AutoCloseable {

    static final int DIMENSIONS = 8;
    static final String MODEL = "test-embed";
    private static final Set<JobStatus> TERMINAL = Set.of(JobStatus.DONE, JobStatus.FAILED, JobStatus.CANCELLED);

    final AppPaths.ProfilePaths paths;
    final TestDb t;
    final EventBus events = new EventBus();
    final JobQueueImpl jobs;
    final InMemorySearchIndex index;
    final CountingEmbedder embedder;
    final SharingServiceImpl sharing;

    private SharingProfile(Path home, String model) {
        paths = AppPaths.at(home).profile(Ids.newId());
        t = TestDb.open(paths.root());
        jobs = new JobQueueImpl(t.repos.jobs(), events, t.clock);
        index = new InMemorySearchIndex(DIMENSIONS, model);
        embedder = new CountingEmbedder(DIMENSIONS, model);
        sharing = new SharingServiceImpl(t.repos, paths, events, t.clock, jobs, index, embedder);
        jobs.register(JobKind.IMPORT_PACKAGE, sharing.importHandler());
    }

    static SharingProfile open(Path home) {
        return new SharingProfile(home, MODEL);
    }

    static SharingProfile open(Path home, String model) {
        return new SharingProfile(home, model);
    }

    /** Imports a package, waits for the job and returns it in its final state. */
    JobUpdate importAndWait(Path file, char[] code, boolean allowOlder) {
        JobUpdate queued = sharing.importCourse(file, code, allowOlder);
        return await(queued.jobId());
    }

    /** Imports a package and returns what the finished job did. */
    ImportResult importCourse(Path file, char[] code) {
        return importCourse(file, code, false);
    }

    /** Imports a package, optionally going back to an older version, and returns what the finished job did. */
    ImportResult importCourse(Path file, char[] code, boolean allowOlder) {
        JobUpdate done = importAndWait(file, code, allowOlder);
        assertThat(done.status()).as("import job: %s", done.message()).isEqualTo(JobStatus.DONE);
        return sharing.importResult(done.jobId()).orElseThrow();
    }

    JobUpdate await(String jobId) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            JobUpdate job = jobs.find(jobId).orElseThrow();
            if (TERMINAL.contains(job.status())) {
                return job;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Job " + jobId + " did not finish: " + job);
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    // ---- creator data -------------------------------------------------------------------------------

    /** What {@link #createCourse} made. */
    record Made(Course course, CourseModule cells, CourseModule energy, Document structure, Document energyDoc,
                Chunk membrane, Chunk organelles, Chunk atp, Concept membraneConcept, Concept atpConcept,
                LearningItem mcq, LearningItem shortAnswer, LearningItem flashcard, LearningItem draft) {
    }

    /**
     * A course made here with two modules, two ready documents (with PDFs, usable pages, one page that is not
     * usable, and embedded passages), two concepts, three approved items and a draft, published as version 1.
     */
    Made createCourse(Visibility visibility) {
        long now = t.now();
        Course course = t.course("Cell Biology");
        t.repos.courses().updateDetails(course.id(), "Cell Biology", "From membranes to ATP", "An introduction.", "Ada");
        t.repos.courses().setVisibility(course.id(), visibility);
        CourseModule cells = t.module(course, "Cells");
        CourseModule energy = t.module(course, "Energy");

        Document structure = readyDocument(course, cells, "Cell structure", pdf("structure", 150_000), 3);
        t.repos.documents().upsertPages(List.of(
                new DocumentPage(structure.id(), 1, "The membrane surrounds the cell.", PageSource.TEXT_LAYER, false, null,
                        false, now, now),
                new DocumentPage(structure.id(), 2, "Organelles do the work.", PageSource.AI_READ, true, "A diagram",
                        true, now, now),
                new DocumentPage(structure.id(), 3, "Unchecked reading.", PageSource.AI_READ, false, null, true, now,
                        now)));
        Chunk membrane = chunk(structure, 0, 1, "The membrane surrounds the cell.");
        Chunk organelles = chunk(structure, 1, 2, "Organelles do the work.");

        Document energyDoc = readyDocument(course, energy, "Energy", pdf("energy", 4_000), 1);
        t.repos.documents().upsertPages(List.of(new DocumentPage(energyDoc.id(), 1, "ATP stores energy.",
                PageSource.TEXT_LAYER, false, null, false, now, now)));
        Chunk atp = chunk(energyDoc, 0, 1, "ATP stores energy.");

        Concept membraneConcept = t.concept(course, cells, "Membrane");
        Concept atpConcept = t.concept(course, energy, "ATP");

        LearningItem mcq = item(course, cells, ItemType.MCQ, "What surrounds the cell?", membrane.id(), 1,
                ItemStatus.APPROVED);
        LearningItem shortAnswer = item(course, cells, ItemType.SHORT_ANSWER, "What do organelles do?", organelles.id(),
                2, ItemStatus.APPROVED);
        LearningItem flashcard = item(course, energy, ItemType.FLASHCARD, "ATP", atp.id(), 1, ItemStatus.APPROVED);
        LearningItem draft = item(course, energy, ItemType.TRUE_FALSE, "ATP is a sugar.", null, null, ItemStatus.DRAFT);
        t.repos.items().setConcepts(mcq.id(), List.of(membraneConcept.id()));
        t.repos.items().setConcepts(shortAnswer.id(), List.of(membraneConcept.id()));
        t.repos.items().setConcepts(flashcard.id(), List.of(atpConcept.id()));
        t.repos.items().setConcepts(draft.id(), List.of(atpConcept.id()));

        publish(course, "First release", mcq, shortAnswer, flashcard);
        return new Made(t.repos.courses().get(course.id()), cells, energy, structure, energyDoc, membrane, organelles, atp,
                membraneConcept, atpConcept, mcq, shortAnswer, flashcard, draft);
    }

    CourseVersion publish(Course course, String notes, LearningItem... items) {
        t.clock.advance(java.time.Duration.ofMinutes(1));
        return t.repos.courses().publishVersion(course.id(), notes, List.of(items).stream().map(LearningItem::id).toList(),
                t.now());
    }

    Path export(Course course, Path target) {
        sharing.exportCourse(course.id(), target, ExportOptions.standard());
        return target;
    }

    Document readyDocument(Course course, CourseModule module, String title, byte[] pdf, int pages) {
        String sha = sha256(pdf);
        try {
            Files.write(paths.pdfFile(sha), pdf);
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        Document d = new Document(Ids.newId(), course.id(), module.id(), title, title + ".pdf", sha, pdf.length, pages,
                DocumentStatus.READY, 1, null, null, t.now(), t.now());
        t.repos.documents().insert(d);
        return d;
    }

    Chunk chunk(Document d, int ordinal, int page, String text) {
        Chunk chunk = Chunk.create(d.id(), d.courseId(), d.moduleId(), ordinal, page, page, "Section " + ordinal, text,
                        text.length() / 4, t.now())
                .withEmbedding(CountingEmbedder.vector(text, DIMENSIONS), MODEL, t.now());
        t.repos.chunks().insert(chunk);
        return chunk;
    }

    LearningItem item(Course course, CourseModule module, ItemType type, String prompt, String sourceChunkId,
                      Integer sourcePage, ItemStatus status) {
        LearningItem item = new LearningItem(Ids.newId(), course.id(), module.id(), type, prompt, "answer to " + prompt,
                "because", type == ItemType.MCQ ? List.of("wrong 1", "wrong 2") : List.of(), Difficulty.MEDIUM,
                sourceChunkId, sourcePage, ItemOrigin.AI, status, SupportStatus.SUPPORTED, null, t.now(), t.now());
        t.repos.items().insert(item);
        return item;
    }

    // ---- learner data -------------------------------------------------------------------------------

    /** Records answers to {@code itemId} in a new session. */
    void answer(String courseId, String itemId, boolean... correct) {
        Course course = t.repos.courses().get(courseId);
        SessionRecord session = t.session(course);
        for (boolean c : correct) {
            t.repos.sessions().insertAttempt(new Attempt(Ids.newId(), itemId, session.id(), "my answer", c, c ? 1 : 0,
                    c ? Rating.GOOD : Rating.AGAIN, null, false, 4_000, GradedBy.RULE, null, false, t.now(), t.now()));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------------

    static byte[] pdf(String name, int size) {
        byte[] head = ("%PDF-1.7\n% " + name + "\n").getBytes(StandardCharsets.US_ASCII);
        byte[] bytes = new byte[Math.max(size, head.length)];
        System.arraycopy(head, 0, bytes, 0, head.length);
        for (int i = head.length; i < bytes.length; i++) {
            bytes[i] = (byte) ('a' + (i * 31 + name.length()) % 26);
        }
        return bytes;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    List<Path> storedPdfs() {
        try (var files = Files.list(paths.filesDir())) {
            return new ArrayList<>(files.toList());
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public void close() {
        sharing.close();
        jobs.close();
        events.close();
        t.close();
    }
}
