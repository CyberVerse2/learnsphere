package edu.learnsphere.core.service;

import java.util.Objects;

/**
 * Which courses {@link CourseService#list} returns. Results are ordered most recently opened first.
 *
 * @param scope           all, studying or creating
 * @param search          case-insensitive text in title, subtitle or author; null or blank for none
 * @param includeArchived whether archived courses are included
 */
public record CourseFilter(CourseScope scope, String search, boolean includeArchived) {

    public CourseFilter {
        Objects.requireNonNull(scope, "scope");
    }

    /** Every course that is not archived. */
    public static CourseFilter all() {
        return new CourseFilter(CourseScope.ALL, null, false);
    }

    /** Courses being studied, not archived. */
    public static CourseFilter studying() {
        return new CourseFilter(CourseScope.STUDYING, null, false);
    }

    /** Courses created here, not archived. */
    public static CourseFilter creating() {
        return new CourseFilter(CourseScope.CREATING, null, false);
    }

    public CourseFilter withSearch(String text) {
        return new CourseFilter(scope, text, includeArchived);
    }

    public CourseFilter withArchived(boolean include) {
        return new CourseFilter(scope, search, include);
    }
}
