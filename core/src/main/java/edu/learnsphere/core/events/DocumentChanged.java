package edu.learnsphere.core.events;

import edu.learnsphere.core.model.DocumentStatus;
import java.util.Objects;

/**
 * A document was added, removed or moved through the import pipeline. Carries the status and progress
 * so status pills can update without reloading.
 *
 * @param status   current status (the last one for a deleted document)
 * @param progress progress of the current stage, 0–1
 */
public record DocumentChanged(String documentId, String courseId, DocumentStatus status, double progress, ChangeType type)
        implements Event {

    public DocumentChanged {
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(type, "type");
    }
}
