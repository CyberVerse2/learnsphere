package edu.learnsphere.core.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Everything the home screen shows, in one call.
 *
 * @param profileName     the learner's display name, for the greeting
 * @param today           today's date in the learner's time zone
 * @param studyNext       the hero card across every enrolled course
 * @param continueCourses enrolled courses, most recently opened first (at most 6)
 * @param week            the last 7 days, oldest first, ending today
 * @param streakDays      consecutive days with a review, including today
 * @param reviewsToday    reviews done today
 * @param recentQuestions recently answered questions (at most 5)
 */
public record DashboardData(String profileName, LocalDate today, StudyNext studyNext, List<CourseSummary> continueCourses,
                            List<DayActivity> week, int streakDays, int reviewsToday, List<RecentQuestion> recentQuestions) {

    public DashboardData {
        Objects.requireNonNull(profileName, "profileName");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(studyNext, "studyNext");
        continueCourses = List.copyOf(continueCourses);
        week = List.copyOf(week);
        recentQuestions = List.copyOf(recentQuestions);
    }

    /** True when the profile has no courses yet (show the welcome empty state). */
    public boolean isEmpty() {
        return continueCourses.isEmpty() && recentQuestions.isEmpty() && studyNext.caughtUp();
    }
}
