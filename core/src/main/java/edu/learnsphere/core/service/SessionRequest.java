package edu.learnsphere.core.service;

import edu.learnsphere.core.model.SessionKind;
import java.util.Objects;

/**
 * What kind of study session to start.
 *
 * @param courseId the course, or null to study across every enrolled course ({@link SessionKind#STUDY_NEXT} only)
 * @param moduleId the module (required for {@link SessionKind#MODULE_QUIZ}), or null
 * @param size     how many items at most
 */
public record SessionRequest(SessionKind kind, String courseId, String moduleId, int size) {

    /** Items in a session unless the learner changes it. */
    public static final int DEFAULT_SIZE = 20;

    public SessionRequest {
        Objects.requireNonNull(kind, "kind");
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1: " + size);
        }
        if (kind == SessionKind.MODULE_QUIZ && moduleId == null) {
            throw new IllegalArgumentException("A module quiz needs a module");
        }
    }

    /** Due reviews, weak concepts, then new items, across every enrolled course. */
    public static SessionRequest studyNext() {
        return new SessionRequest(SessionKind.STUDY_NEXT, null, null, DEFAULT_SIZE);
    }

    /** Due reviews, weak concepts, then new items in one course. */
    public static SessionRequest studyNext(String courseId) {
        return new SessionRequest(SessionKind.STUDY_NEXT, courseId, null, DEFAULT_SIZE);
    }

    public static SessionRequest moduleQuiz(String courseId, String moduleId) {
        return new SessionRequest(SessionKind.MODULE_QUIZ, courseId, moduleId, DEFAULT_SIZE);
    }

    /** Flashcards of a course, or one module when {@code moduleId} is not null. */
    public static SessionRequest flashcards(String courseId, String moduleId) {
        return new SessionRequest(SessionKind.FLASHCARDS, courseId, moduleId, DEFAULT_SIZE);
    }

    public static SessionRequest weakConcepts(String courseId) {
        return new SessionRequest(SessionKind.WEAK_CONCEPTS, courseId, null, DEFAULT_SIZE);
    }

    public SessionRequest withSize(int newSize) {
        return new SessionRequest(kind, courseId, moduleId, newSize);
    }
}
