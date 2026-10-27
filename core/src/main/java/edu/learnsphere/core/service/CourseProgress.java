package edu.learnsphere.core.service;

import java.util.List;

/**
 * The Progress screen, for one course or as the overview of every course being studied.
 *
 * @param courseId       the course, or null for the overview
 * @param title          the course title, or null for the overview
 * @param coverHue       the course's cover hue, or null for the overview
 * @param progress       share of approved items studied at least once, 0–1
 * @param averageMastery mean mastery over concepts that have one, or null
 * @param accuracy       share of correct attempts, or null without attempts
 * @param totalReviews   attempts in scope
 * @param studyTimeMs    time spent answering in scope
 * @param streakDays     consecutive days with a review in any course
 * @param modules        modules in order (empty for the overview)
 * @param concepts       mastery by concept, weakest first (concepts without mastery last); in the overview,
 *                       concepts of every course being studied
 * @param forecast       reviews due on each of the next 14 days, starting today
 * @param retention      weekly retention for the last 12 weeks, oldest first (weeks without reviews are absent)
 * @param courses        in the overview, the courses being studied, most recently opened first, for the course
 *                       picker; empty for one course
 */
public record CourseProgress(String courseId, String title, Integer coverHue, double progress, Double averageMastery,
                             Double accuracy, int totalReviews, long studyTimeMs, int streakDays,
                             List<ModuleProgress> modules, List<ConceptMastery> concepts, List<ForecastDay> forecast,
                             List<RetentionPoint> retention, List<CourseSummary> courses) {

    /** Days in the review forecast. */
    public static final int FORECAST_DAYS = 14;

    public CourseProgress {
        if ((courseId == null) != (title == null)) {
            throw new IllegalArgumentException("courseId and title are both set (one course) or both null (overview)");
        }
        modules = List.copyOf(modules);
        concepts = List.copyOf(concepts);
        forecast = List.copyOf(forecast);
        retention = List.copyOf(retention);
        courses = courses == null ? List.of() : List.copyOf(courses);
    }

    /** True for the overview across courses. */
    public boolean isOverview() {
        return courseId == null;
    }
}
