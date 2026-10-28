package edu.learnsphere.core.service.impl.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import edu.learnsphere.core.model.Chunk;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.testing.TestDb;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CourseOutlineTest {

    @TempDir
    Path dir;

    private TestDb t;

    @BeforeEach
    void setUp() {
        t = TestDb.open(dir);
    }

    @AfterEach
    void tearDown() {
        t.close();
    }

    @Test
    void groupsPassagesUnderTheirHeadingInReadingOrder() {
        Course course = t.course("Logic");
        Document notes = ready(course, "Logic notes", null, "1 Introduction", "1 Introduction", "Example 1.1", "",
                "2 Propositions", "Figure 3", "2.1 Syntax", "x".repeat(100));
        Document slides = ready(course, "Slides", "Truth tables");
        t.document(course, null, "Still importing");

        var sections = CourseOutline.of(t.repos, course.id());

        assertThat(sections).extracting(CourseOutline.Section::documentId, CourseOutline.Section::heading)
                .containsExactly(
                        tuple(notes.id(), "Logic notes"),
                        tuple(notes.id(), "1 Introduction"),
                        tuple(notes.id(), "2 Propositions"),
                        tuple(notes.id(), "2.1 Syntax"),
                        tuple(notes.id(), "x".repeat(CourseOutline.MAX_HEADING - 1) + "…"),
                        tuple(slides.id(), "Truth tables"));
        assertThat(sections.get(1).chunkIds()).hasSize(4);
        assertThat(sections.get(2).chunkIds()).hasSize(2);
    }

    /** A ready document with one passage under each heading (null for none), in order. */
    private Document ready(Course course, String title, String... headings) {
        Document document = t.document(course, null, title);
        for (int i = 0; i < headings.length; i++) {
            t.repos.chunks().insert(Chunk.create(document.id(), course.id(), null, i, i + 1, i + 1, headings[i],
                    "Passage " + i + ".", 10, t.now()));
        }
        t.repos.documents().markReady(document.id());
        t.clock.advance(Duration.ofSeconds(1));
        return document;
    }
}
