package edu.learnsphere.core.service;

/** Which courses a list shows. */
public enum CourseScope {
    /** Every course. */
    ALL,
    /** Courses the learner is enrolled in (imported courses, or their own courses they chose to study). */
    STUDYING,
    /** Courses created in this profile. */
    CREATING
}
