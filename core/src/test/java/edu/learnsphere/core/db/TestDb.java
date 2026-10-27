package edu.learnsphere.core.db;

import edu.learnsphere.core.MutableClock;
import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Concept;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Difficulty;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.ItemOrigin;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.model.SessionRecord;
import edu.learnsphere.core.util.Ids;
import java.nio.file.Path;
import java.util.List;

/** A migrated database in a temp folder with fixtures for building test data. */
final class TestDb implements AutoCloseable {

    final MutableClock clock = MutableClock.fixed();
    final Database db;
    final Repositories repos;

    private TestDb(Path dir) {
        db = Database.open(dir.resolve("learnsphere.db"), clock);
        repos = new Repositories(db, clock);
    }

    static TestDb open(Path dir) {
        return new TestDb(dir);
    }

    long now() {
        return clock.millis();
    }

    Course course(String title) {
        Course course = Course.newDraft(title, "Ada", 210, now());
        repos.courses().insert(course);
        return course;
    }

    CourseModule module(Course course, String title) {
        return repos.modules().append(course.id(), title);
    }

    Document document(Course course, CourseModule module, String title) {
        String sha = (Ids.newId().replace("-", "") + Ids.newId().replace("-", "")).substring(0, 64);
        Document document = Document.queued(course.id(), module == null ? null : module.id(), title, title + ".pdf", sha,
                1234, now());
        repos.documents().insert(document);
        return document;
    }

    Chunk chunk(Document document, int ordinal, String text) {
        Chunk chunk = Chunk.create(document.id(), document.courseId(), document.moduleId(), ordinal, ordinal + 1,
                ordinal + 1, "Section " + ordinal, text, text.length() / 4, now());
        repos.chunks().insert(chunk);
        return chunk;
    }

    LearningItem item(Course course, CourseModule module, ItemType type, ItemStatus status, String prompt) {
        LearningItem item = LearningItem.draft(course.id(), module == null ? null : module.id(), type, prompt, "answer",
                        "because", type == ItemType.MCQ ? List.of("wrong 1", "wrong 2") : List.of(), Difficulty.MEDIUM,
                        null, null, ItemOrigin.AI, now())
                .withStatus(status, now());
        repos.items().insert(item);
        return item;
    }

    Concept concept(Course course, CourseModule module, String name) {
        Concept concept = Concept.create(course.id(), module == null ? null : module.id(), name, "About " + name, now());
        repos.concepts().insert(concept);
        return concept;
    }

    SessionRecord session(Course course) {
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
