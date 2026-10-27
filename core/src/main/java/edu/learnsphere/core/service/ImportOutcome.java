package edu.learnsphere.core.service;

/** What an import did. */
public enum ImportOutcome {
    /** A new course was added to the library. */
    CREATED,
    /** An existing course was updated in place; progress was kept. */
    UPDATED,
    /** The library already had this version; nothing changed. */
    ALREADY_CURRENT,
    /** Learner feedback was added to a course's flags. */
    FEEDBACK_ADDED
}
