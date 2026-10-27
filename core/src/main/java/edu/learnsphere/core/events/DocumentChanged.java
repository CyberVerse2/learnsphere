package edu.learnsphere.core.events;

import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.DocumentStatus;
import java.util.Objects;

/**
 * A document was added, removed or moved through the import pipeline. Carries everything a status pill
 * shows ("Reading your PDF… page 14 of 120", or the failure sentence), so it can update without asking
 * {@code DocumentService} on every progress event.
 *
 * @param status    current status (the last one for a deleted document)
 * @param progress  progress of the current stage, 0–1
 * @param stepDone  pages (or passages) finished in the current stage, 0 when not counted
 * @param stepTotal pages (or passages) in the current stage, 0 when not counted
 * @param error     what went wrong when {@code status} is failed, otherwise null
 */
public record DocumentChanged(String documentId, String courseId, DocumentStatus status, double progress, int stepDone,
                              int stepTotal, UserMessage error, ChangeType type) implements Event {

    public DocumentChanged {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(type, "type");
        if (stepDone < 0 || stepTotal < 0) {
            throw new IllegalArgumentException("Negative step counts: " + stepDone + "/" + stepTotal);
        }
    }

    /** A change with no step counter and no error. */
    public static DocumentChanged of(String documentId, String courseId, DocumentStatus status, double progress,
                                     ChangeType type) {
        return new DocumentChanged(documentId, courseId, status, progress, 0, 0, null, type);
    }

    /** True when the current stage reports a step counter. */
    public boolean hasSteps() {
        return stepTotal > 0;
    }
}
