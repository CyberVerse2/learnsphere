package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.Objects;

/**
 * A row of {@code documents}: an imported PDF and where it is in the import pipeline.
 *
 * @param moduleId     module it belongs to, or null for the whole course
 * @param title        display title (defaults to the file name without extension)
 * @param fileName     original file name
 * @param fileSha256   lowercase hex SHA-256 of the file; the copy lives at {@code files/<sha256>.pdf}
 * @param byteSize     file size in bytes
 * @param pageCount    pages, 0 until checked
 * @param status       pipeline stage
 * @param progress     progress of the current stage, 0–1
 * @param statusDetail short machine-made detail of the stage (for example {@code "14/120"}), or null
 * @param errorCode    why it failed, or null
 */
public record Document(
        String id,
        String courseId,
        String moduleId,
        String title,
        String fileName,
        String fileSha256,
        long byteSize,
        int pageCount,
        DocumentStatus status,
        double progress,
        String statusDetail,
        DocumentError errorCode,
        long createdAt,
        long updatedAt) {

    public Document {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(fileSha256, "fileSha256");
        Objects.requireNonNull(status, "status");
        if (progress < 0 || progress > 1) {
            throw new IllegalArgumentException("progress must be 0-1: " + progress);
        }
    }

    /** A newly imported document waiting in the queue. */
    public static Document queued(String courseId, String moduleId, String title, String fileName, String fileSha256,
                                  long byteSize, long now) {
        return new Document(Ids.newId(), courseId, moduleId, title, fileName, fileSha256, byteSize, 0,
                DocumentStatus.QUEUED, 0, null, null, now, now);
    }

    /** True while the pipeline is still working on it (not ready, failed or waiting for review). */
    public boolean isProcessing() {
        return status != DocumentStatus.READY && status != DocumentStatus.FAILED && status != DocumentStatus.NEEDS_REVIEW;
    }
}
