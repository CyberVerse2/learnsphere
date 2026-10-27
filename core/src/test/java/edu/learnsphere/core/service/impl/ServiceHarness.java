package edu.learnsphere.core.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.AppPaths;
import edu.learnsphere.core.events.Event;
import edu.learnsphere.core.events.EventBus;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.testing.MemorySearchIndex;
import edu.learnsphere.core.testing.TestDb;
import edu.learnsphere.core.util.Ids;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What the course, document and item service tests share: a real database in a temporary folder, an event
 * bus that records every event, a job queue without retries (and without handlers unless a test registers
 * one, so queued work stays queued), an in-memory search index and the profile's PDF store.
 */
final class ServiceHarness implements AutoCloseable {

    final TestDb t;
    final EventBus events = new EventBus();
    final JobQueueImpl jobs;
    final MemorySearchIndex index = new MemorySearchIndex(2, "embed-v1");
    final AppPaths.ProfilePaths paths;
    final PdfStore pdfs;
    private final List<Event> seen = new CopyOnWriteArrayList<>();

    ServiceHarness(Path dir) {
        t = TestDb.open(dir.resolve("db"));
        paths = AppPaths.at(dir.resolve("home")).profile(Ids.newId());
        jobs = new JobQueueImpl(t.repos.jobs(), events, t.clock, JobQueueImpl.RetryPolicy.NONE);
        pdfs = new PdfStore(paths, t.repos.documents());
        events.subscribeAll(seen::add);
    }

    /** The events of {@code type} published so far, oldest first. */
    <E extends Event> List<E> events(Class<E> type) {
        assertThat(events.awaitIdle(Duration.ofSeconds(5))).isTrue();
        return seen.stream().filter(type::isInstance).map(type::cast).toList();
    }

    /** Forgets the events published so far. */
    void clearEvents() {
        events.awaitIdle(Duration.ofSeconds(5));
        seen.clear();
    }

    /** A course imported from someone else's package, which the learner is studying. */
    Course importedCourse(String title) {
        long now = t.now();
        Course course = new Course(Ids.newId(), title, "", "", "Grace", CourseOrigin.IMPORTED, Visibility.PRIVATE,
                CourseStatus.PUBLISHED, 150, 1, now, Ids.newId(), null, now, now);
        t.repos.courses().insert(course);
        t.repos.courses().enrol(course.id(), now);
        return course;
    }

    /** Stores a small PDF file for a document, as an import would have. */
    Path storeFile(Document document) {
        Path file = pdfs.file(document.fileSha256());
        try {
            Files.writeString(file, "%PDF-1.7 " + document.id(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }

    /** Jobs of {@code kind} in {@code statuses}. */
    List<Job> jobs(JobKind kind, JobStatus... statuses) {
        return t.repos.jobs().listByStatus(Set.of(statuses)).stream().filter(j -> j.kind() == kind).toList();
    }

    @Override
    public void close() {
        jobs.close();
        events.close();
        t.close();
    }
}
