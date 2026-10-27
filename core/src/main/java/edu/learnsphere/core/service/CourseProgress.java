package edu.learnsphere.core.service;

import java.util.List;
import java.util.Objects;

/**
 * The Progress screen for one course.
 *
 * @param progress       share of approved items studied at least once, 0–1
 * @param averageMastery mean mastery over concepts that have one, or null
 * @param accuracy       share of correct attempts, or null without attempts
 * @param totalReviews   attempts in this course
 * @param studyTimeMs    time spent answering in this course
 * @param streakDays     consecutive days with a review in any course
 * @param modules        modules in order
 * @param concepts       mastery by concept, weakest first (concepts without mastery last)
 * @param forecast       reviews due on each of the next 14 days, starting today
 * @param retention      weekly retention for the last 12 weeks, oldest first (weeks without reviews are absent)
 */
public record CourseProgress(String courseId, String title, int coverHue, double progress, Double averageMastery,
                             Double accuracy, int totalReviews, long studyTimeMs, int streakDays,
                             List<ModuleProgress> modules, List<ConceptMastery> concepts, List<ForecastDay> forecast,
                             List<RetentionPoint> retention) {

    /** Days in the review forecast. */
    public static final int FORECAST_DAYS = 14;

    public CourseProgress {
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(title, "title");
        modules = List.copyOf(modules);
        concepts = List.copyOf(concepts);
        forecast = List.copyOf(forecast);
        retention = List.copyOf(retention);
    }
}
