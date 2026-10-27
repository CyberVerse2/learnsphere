package edu.learnsphere.core.service;

import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.DocumentStatus;
import java.util.Objects;

/**
 * A PDF row in the Studio with its live import status.
 *
 * @param moduleTitle        title of its module, or null when it belongs to the whole course
 * @param progress           progress of the current stage, 0–1
 * @param stepDone           pages (or passages) finished in the current stage, 0 when not counted
 * @param stepTotal          pages (or passages) in the current stage, 0 when not counted
 * @param error              what went wrong, or null
 * @param pagesNeedingReview scanned pages waiting for review
 */
public record DocumentSummary(
        String id,
        String courseId,
        String moduleId,
        String moduleTitle,
        String title,
        String fileName,
        long byteSize,
        int pageCount,
        DocumentStatus status,
        double progress,
        int stepDone,
        int stepTotal,
        UserMessage error,
        int pagesNeedingReview,
        long createdAt,
        long updatedAt) {

    public DocumentSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(courseId, "courseId");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(status, "status");
    }

    /** True while the import is still working on it. */
    public boolean isProcessing() {
        return status != DocumentStatus.READY && status != DocumentStatus.FAILED && status != DocumentStatus.NEEDS_REVIEW;
    }

    /** True when it can be used to answer questions and generate items. */
    public boolean isReady() {
        return status == DocumentStatus.READY;
    }

    /**
     * Parses a {@code documents.status_detail} counter written as {@code "done/total"}; returns
     * {@code {0, 0}} for anything else.
     */
    public static int[] parseStep(String statusDetail) {
        if (statusDetail != null) {
            int slash = statusDetail.indexOf('/');
            if (slash > 0) {
                try {
                    return new int[] {Integer.parseInt(statusDetail.substring(0, slash).strip()),
                            Integer.parseInt(statusDetail.substring(slash + 1).strip())};
                } catch (NumberFormatException ignored) {
                    // not a counter
                }
            }
        }
        return new int[] {0, 0};
    }

    /** The {@code status_detail} counter for {@code done} of {@code total}. */
    public static String formatStep(int done, int total) {
        return done + "/" + total;
    }
}
