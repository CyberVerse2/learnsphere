package edu.learnsphere.core.db;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jdbi.v3.core.statement.SqlStatement;

/**
 * Which approved items a study query looks at. Every scheduling query in {@link ReviewStateRepository}
 * and {@link LearningItemRepository#listUnseen} takes one, so "across courses" always means the courses
 * the learner is studying and never every course in the profile.
 *
 * <ul>
 *   <li>{@link #enrolled()}: courses with an active enrolment that are not archived ("Study next" across
 *       courses, the home dashboard, the progress overview). Courses the learner left, archived courses and
 *       courses created here but not enrolled are excluded.</li>
 *   <li>{@link #course(String)}: one course, enrolled or not (a creator trying out their own course).</li>
 *   <li>{@link #module(String, String)}: one module of a course.</li>
 *   <li>{@link #courses(Collection)}: an explicit set of courses.</li>
 * </ul>
 *
 * The SQL fragments assume the learning item is aliased {@code i}.
 */
public sealed interface StudyScope {

    /** Every course with an active enrolment that is not archived. */
    static StudyScope enrolled() {
        return Enrolled.INSTANCE;
    }

    /** One course, whether or not the learner is enrolled. */
    static StudyScope course(String courseId) {
        return new Courses(Set.of(Objects.requireNonNull(courseId, "courseId")), null);
    }

    /** One module of a course. */
    static StudyScope module(String courseId, String moduleId) {
        return new Courses(Set.of(Objects.requireNonNull(courseId, "courseId")),
                Objects.requireNonNull(moduleId, "moduleId"));
    }

    /** These courses (an empty set matches nothing). */
    static StudyScope courses(Collection<String> courseIds) {
        return new Courses(Set.copyOf(courseIds), null);
    }

    /** Joins to add after {@code FROM learning_items i} (or after a join that defines {@code i}). */
    String joins();

    /** Conditions to append with {@code AND}; never empty. */
    String condition();

    /** Binds the parameters {@link #condition()} uses. */
    <S extends SqlStatement<S>> S bind(S statement);

    /** Courses with an active enrolment that are not archived. */
    enum Enrolled implements StudyScope {
        INSTANCE;

        @Override
        public String joins() {
            return " JOIN enrolments scope_e ON scope_e.course_id = i.course_id AND scope_e.status = 'active'"
                    + " JOIN courses scope_c ON scope_c.id = i.course_id AND scope_c.status <> 'archived'";
        }

        @Override
        public String condition() {
            return "1 = 1";
        }

        @Override
        public <S extends SqlStatement<S>> S bind(S statement) {
            return statement;
        }
    }

    /**
     * Explicit courses, optionally narrowed to one module.
     *
     * @param moduleId the module, or null for whole courses
     */
    record Courses(Set<String> courseIds, String moduleId) implements StudyScope {

        public Courses {
            courseIds = Set.copyOf(courseIds);
        }

        @Override
        public String joins() {
            return "";
        }

        @Override
        public String condition() {
            if (courseIds.isEmpty()) {
                return "0 = 1";
            }
            return "i.course_id IN (<scopeCourseIds>)" + (moduleId == null ? "" : " AND i.module_id = :scopeModuleId");
        }

        @Override
        public <S extends SqlStatement<S>> S bind(S statement) {
            if (!courseIds.isEmpty()) {
                statement.bindList("scopeCourseIds", List.copyOf(courseIds));
            }
            if (moduleId != null) {
                statement.bind("scopeModuleId", moduleId);
            }
            return statement;
        }
    }
}
