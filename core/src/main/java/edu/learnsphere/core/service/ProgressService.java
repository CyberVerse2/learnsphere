package edu.learnsphere.core.service;

import edu.learnsphere.core.error.NotFoundException;
import java.util.List;

/** Read-only progress views. Methods taking an id throw {@link NotFoundException} when it does not exist. */
public interface ProgressService {

    /** The home screen. */
    DashboardData dashboard();

    /**
     * The Progress screen across every course being studied (active enrolment, not archived): progress,
     * accuracy, reviews and study time summed over those courses, concepts of all of them, one combined
     * 14-day forecast and retention trend, the streak, and the courses themselves for the course picker.
     * {@link CourseProgress#isOverview()} is true.
     */
    CourseProgress overview();

    /** The Progress screen for one course. */
    CourseProgress course(String courseId);

    /**
     * Reviews done on each of the last {@code days} days in any course, oldest first and ending today: the
     * learner's study habit, for the streak calendar on the Progress screen.
     *
     * <p>The default reads the home screen's {@link DashboardData#week() week}, so it covers at most the last
     * 7 days; implementations that keep the full history return every day asked for.
     *
     * @param days how many days, at least 1
     */
    default List<DayActivity> activity(int days) {
        if (days < 1) {
            throw new IllegalArgumentException("days must be >= 1: " + days);
        }
        List<DayActivity> week = dashboard().week();
        return week.subList(Math.max(0, week.size() - days), week.size());
    }

    /** Mastery per concept of a course, weakest first. */
    List<ConceptMastery> conceptMastery(String courseId);

    /** Statistics per approved item of a course, hardest (lowest accuracy) first, for creators. */
    List<ItemStats> itemStats(String courseId);

    /** Statistics of one item. */
    ItemStats itemStatsFor(String itemId);
}
