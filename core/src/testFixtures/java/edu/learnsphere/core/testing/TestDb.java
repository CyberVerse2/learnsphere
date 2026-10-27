package edu.learnsphere.core.testing;

import edu.learnsphere.core.db.Database;
import edu.learnsphere.core.db.Repositories;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.model.SessionRecord;
import edu.learnsphere.core.util.Ids;
import java.nio.file.Path;
import java.util.List;

/**
 * A migrated profile database in a temporary folder, with builders that insert test data through the
 * repositories. Every builder stamps rows with {@link #now()}; move {@link #clock} to spread them out.
 *
 * <pre>{@code
 * try (TestDb t = TestDb.open(tempDir)) {
 *     Course course = t.enrolledCourse("Biology");
 *     LearningItem item = t.item(course, null, ItemType.MCQ, ItemStatus.APPROVED, "What is a cell?");
 * }
 * }</pre>
 */
public final class TestDb implements AutoCloseable {

    /** The clock every repository uses; starts at {@link MutableClock#START}. */
    public final MutableClock clock = MutableClock.fixed();
    public final Database db;
    public final Repositories repos;

    private TestDb(Path dir) {
        db = Database.open(dir.resolve(Database.FILE_NAME), clock);
        repos = new Repositories(db, clock);
    }

    /** Opens (creating and migrating) a database in {@code dir}. */
    public static TestDb open(Path dir) {
        return new TestDb(dir);
    }

    public long now() {
        return clock.millis();
    }

    /** A private draft course created in this profile, not enrolled. */
    public Course course(String title) {
        Course course = Course.newDraft(title, "Ada", 210, now());
        repos.courses().insert(course);
        return course;
    }

    /** A course created here that the learner is also studying. */
    public Course enrolledCourse(String title) {
        Course course = course(title);
        repos.courses().enrol(course.id(), now());
        return course;
    }

    /** Archives a course (hides it from study). */
    public void archive(Course course) {
        repos.courses().setStatus(course.id(), CourseStatus.ARCHIVED);
    }

    public CourseModule module(Course course, String title) {
        return repos.modules().append(course.id(), title);
    }

    /** A queued document with a random file hash. */
    public Document document(Course course, CourseModule module, String title) {
        String sha = (Ids.newId().replace("-", "") + Ids.newId().replace("-", "")).substring(0, 64);
        Document document = Document.queued(course.id(), module == null ? null : module.id(), title, title + ".pdf", sha,
                1234, now());
        repos.documents().insert(document);
        return document;
    }

    /** A passage on page {@code ordinal + 1}, without an embedding. */
    public Chunk chunk(Document document, int ordinal, String text) {
        Chunk chunk = Chunk.create(document.id(), document.courseId(), document.moduleId(), ordinal, ordinal + 1,
                ordinal + 1, "Section " + ordinal, text, text.length() / 4, now());
        repos.chunks().insert(chunk);
        return chunk;
    }

    /** An AI item with answer "answer" (and two wrong options for multiple choice) in {@code status}. */
    public LearningItem item(Course course, CourseModule module, ItemType type, ItemStatus status, String prompt) {
        LearningItem item = LearningItem.draft(course.id(), module == null ? null : module.id(), type, prompt, "answer",
                        "because", type == ItemType.MCQ ? List.of("wrong 1", "wrong 2") : List.of(), Difficulty.MEDIUM,
                        null, null, ItemOrigin.AI, now())
                .withStatus(status, now());
        repos.items().insert(item);
        return item;
    }

    /** Stores a reviewed state (phase review) for {@code item}, due at {@code dueAt}. */
    public ReviewState reviewed(LearningItem item, long dueAt) {
        ReviewState state = new ReviewState(item.id(), ReviewPhase.REVIEW, 4.5, 5.2, dueAt, dueAt - 86_400_000L, 3, 1, 4, 0,
                now(), now());
        repos.reviews().upsert(state);
        return state;
    }

    public Concept concept(Course course, CourseModule module, String name) {
        Concept concept = Concept.create(course.id(), module == null ? null : module.id(), name, "About " + name, now());
        repos.concepts().insert(concept);
        return concept;
    }

    /** A started study-next session in {@code course} (or across courses when null). */
    public SessionRecord session(Course course) {
        SessionRecord session = new SessionRecord(Ids.newId(), course == null ? null : course.id(), null,
                SessionKind.STUDY_NEXT, now(), null, now(), now());
        repos.sessions().insertSession(session);
        return session;
    }

    @Override
    public void close() {
        db.close();
    }
}
