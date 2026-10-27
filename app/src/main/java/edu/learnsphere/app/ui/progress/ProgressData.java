package edu.learnsphere.app.ui.progress;

import edu.learnsphere.core.service.ClassInsightSummary;
import edu.learnsphere.core.service.ConceptMastery;
import edu.learnsphere.core.service.CourseFilter;
import edu.learnsphere.core.service.CourseProgress;
import edu.learnsphere.core.service.CourseSummary;
import edu.learnsphere.core.service.DayActivity;
import edu.learnsphere.core.service.ItemStats;
import edu.learnsphere.core.service.SettingKeys;
import edu.learnsphere.core.service.Workspace;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the Progress screen shows, read in one background call ({@link #load}).
 *
 * @param progress       the overview or one course
 * @param retentionGoal  the learner's desired retention (the goal line of the retention trend)
 * @param days           study days for the streak strip, oldest first and ending today (overview only)
 * @param studying       courses being studied, most recently opened first, for the course picker
 * @param course         the course's card in the one-course view, or null for the overview
 * @param conceptCourses in an overview of several courses, the course title of each concept by concept id
 * @param creator        question statistics when the course was made in this profile, or null
 */
record ProgressData(CourseProgress progress, double retentionGoal, List<DayActivity> days,
                    List<CourseSummary> studying, CourseSummary course, Map<String, String> conceptCourses,
                    CreatorStats creator) {

    private static final Logger LOG = LoggerFactory.getLogger(ProgressData.class);

    /** Days in the streak strip: five weeks. */
    static final int STRIP_DAYS = 35;

    ProgressData {
        Objects.requireNonNull(progress, "progress");
        days = List.copyOf(days);
        studying = List.copyOf(studying);
        conceptCourses = Map.copyOf(conceptCourses);
    }

    /**
     * How a creator's questions are answered.
     *
     * @param items         approved questions, hardest first
     * @param classInsights the class progress imported for the course, or null when none was imported
     */
    record CreatorStats(List<ItemStats> items, ClassInsightSummary classInsights) {
        CreatorStats {
            items = List.copyOf(items);
        }

        /** True when someone has answered at least one question (the creator or the class). */
        boolean hasAnswers() {
            return items.stream().anyMatch(i -> i.attempts() > 0 || i.classLearners() > 0);
        }

        boolean hasClass() {
            return classInsights != null && !classInsights.isEmpty();
        }
    }

    /** Reads the overview ({@code courseId} null) or one course. Blocking: call it off the FX thread. */
    static ProgressData load(Workspace ws, String courseId) {
        double goal = ws.settings().get(SettingKeys.DESIRED_RETENTION);
        if (courseId == null) {
            CourseProgress overview = ws.progress().overview();
            Map<String, String> conceptCourses = new HashMap<>();
            if (overview.courses().size() > 1) {
                for (CourseSummary course : overview.courses()) {
                    for (ConceptMastery concept : ws.progress().conceptMastery(course.id())) {
                        conceptCourses.put(concept.conceptId(), course.title());
                    }
                }
            }
            return new ProgressData(overview, goal, ws.progress().activity(STRIP_DAYS), overview.courses(), null,
                    conceptCourses, null);
        }
        CourseProgress progress = ws.progress().course(courseId);
        CourseSummary course = ws.courses().summary(courseId);
        CreatorStats creator = null;
        if (course.createdHere()) {
            creator = new CreatorStats(ws.progress().itemStats(courseId), classInsights(ws, courseId));
        }
        return new ProgressData(progress, goal, List.of(), ws.courses().list(CourseFilter.studying()), course, Map.of(),
                creator);
    }

    private static ClassInsightSummary classInsights(Workspace ws, String courseId) {
        try {
            ClassInsightSummary summary = ws.sharing().classInsights(courseId);
            return summary == null || summary.isEmpty() ? null : summary;
        } catch (RuntimeException e) {
            // Class progress is extra: the creator's own statistics still show without it.
            LOG.warn("Class progress of course {} could not be read", courseId, e);
            return null;
        }
    }

    boolean isOverview() {
        return progress.isOverview();
    }

    /** True once the learner has answered something in scope. */
    boolean hasStudied() {
        return progress.totalReviews() > 0 || progress.concepts().stream().anyMatch(ConceptMastery::hasMastery);
    }

    /** Questions started, and questions to study, in the one-course view ({0, 0} for the overview). */
    int[] startedOfTotal() {
        if (course == null) {
            return new int[] {0, 0};
        }
        return new int[] {Math.max(0, course.itemCount() - course.newItemCount()), course.itemCount()};
    }

    /** Reviews done today, from the streak strip's days (0 in the one-course view). */
    int reviewsToday() {
        return days.isEmpty() ? 0 : days.getLast().reviews();
    }
}
