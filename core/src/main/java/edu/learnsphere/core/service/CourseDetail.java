package edu.learnsphere.core.service;

import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseVersion;
import java.util.List;
import java.util.Objects;

/**
 * Everything the course page needs in one call: the header, the module path and the concept chips.
 *
 * @param course        the stored course (for edit forms)
 * @param summary       counts and progress, as on its card
 * @param latestVersion latest published version, or null
 * @param modules       modules in order
 * @param concepts      concept mastery chips, in module order
 */
public record CourseDetail(Course course, CourseSummary summary, CourseVersion latestVersion, List<ModuleSummary> modules,
                           List<ConceptMastery> concepts) {

    public CourseDetail {
        Objects.requireNonNull(course, "course");
        Objects.requireNonNull(summary, "summary");
        modules = List.copyOf(modules);
        concepts = List.copyOf(concepts);
    }

    public String id() {
        return course.id();
    }
}
