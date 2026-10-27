package edu.learnsphere.core.service;

import edu.learnsphere.core.error.NotFoundException;
import java.util.List;

/** Read-only progress views. Methods taking an id throw {@link NotFoundException} when it does not exist. */
public interface ProgressService {

    /** The home screen. */
    DashboardData dashboard();

    /** The Progress screen for one course. */
    CourseProgress course(String courseId);

    /** Mastery per concept of a course, weakest first. */
    List<ConceptMastery> conceptMastery(String courseId);

    /** Statistics per approved item of a course, hardest (lowest accuracy) first, for creators. */
    List<ItemStats> itemStats(String courseId);

    /** Statistics of one item. */
    ItemStats itemStatsFor(String itemId);
}
