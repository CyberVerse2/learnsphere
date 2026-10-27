package edu.learnsphere.core.service;

import edu.learnsphere.core.error.InvalidInputException;
import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UnavailableException;

/**
 * Study sessions, grading and spaced-review scheduling (FSRS-6). Recording answers publishes
 * {@link edu.learnsphere.core.events.ProgressChanged}. Methods taking an id throw
 * {@link NotFoundException} when it does not exist.
 */
public interface PracticeService {

    /** What a "Study next" session would contain now (course may be null for every enrolled course). */
    StudyNext studyNext(String courseId);

    /** Starts a session. The session may be empty when there is nothing to study. */
    StudySession start(SessionRequest request);

    /**
     * Grades an answer, records the attempt and reschedules the item. Short answers are graded by the
     * study assistant.
     *
     * @throws InvalidInputException when the item is not part of the session or was already answered
     * @throws UnavailableException  when a short answer cannot be graded right now
     */
    Feedback submit(String sessionId, Response response);

    /** A hint for an item in the session; marks the item's answer as hinted. */
    Hint hint(String sessionId, String itemId);

    /** Finishes the session and summarizes it. Unanswered items stay as they were. */
    SessionSummary finish(String sessionId);

    /**
     * Disputes an AI grade: the attempt is re-rated Good and marked disputed, and the item rescheduled.
     *
     * @throws InvalidInputException when the attempt was not graded by the study assistant
     */
    Feedback dispute(String attemptId);
}
