package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;

import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseModule;
import edu.learnsphere.core.model.Document;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleRepositoryTest extends RepositoryTestBase {

    @Test
    void appendsModulesInOrder() {
        Course course = t.course("C");
        CourseModule a = t.module(course, "A");
        CourseModule b = t.module(course, "B");

        assertThat(a.position()).isZero();
        assertThat(b.position()).isEqualTo(1);
        assertThat(repos.modules().listByCourse(course.id())).containsExactly(a, b);
        assertThat(repos.modules().find(a.id())).contains(a);
        assertThat(repos.modules().countByCourse(course.id())).isEqualTo(2);
        assertThat(repos.modules().nextPosition(course.id())).isEqualTo(2);
    }

    @Test
    void renamesAndSummarizes() {
        CourseModule module = t.module(t.course("C"), "Old");
        repos.modules().rename(module.id(), "New");
        repos.modules().updateSummary(module.id(), "What it covers");
        CourseModule stored = repos.modules().get(module.id());
        assertThat(stored.title()).isEqualTo("New");
        assertThat(stored.summary()).isEqualTo("What it covers");
    }

    @Test
    void reordersAndKeepsUnlistedModulesAtTheEnd() {
        Course course = t.course("C");
        CourseModule a = t.module(course, "A");
        CourseModule b = t.module(course, "B");
        CourseModule c = t.module(course, "C");

        repos.modules().reorder(course.id(), List.of(c.id(), a.id(), "not-a-module"));

        assertThat(repos.modules().listByCourse(course.id())).extracting(CourseModule::title).containsExactly("C", "A", "B");
        assertThat(repos.modules().listByCourse(course.id())).extracting(CourseModule::position).containsExactly(0, 1, 2);
    }

    @Test
    void deletingAModuleClosesTheGapAndKeepsDocuments() {
        Course course = t.course("C");
        CourseModule a = t.module(course, "A");
        CourseModule b = t.module(course, "B");
        CourseModule c = t.module(course, "C");
        Document document = t.document(course, b, "Doc");

        assertThat(repos.modules().delete(b.id())).isTrue();
        assertThat(repos.modules().delete(b.id())).isFalse();

        assertThat(repos.modules().listByCourse(course.id())).extracting(CourseModule::id).containsExactly(a.id(), c.id());
        assertThat(repos.modules().get(c.id()).position()).isEqualTo(1);
        assertThat(repos.documents().get(document.id()).moduleId()).isNull();
    }
}
