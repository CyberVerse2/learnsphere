package edu.learnsphere.core.sharing;

import edu.learnsphere.core.util.Ids;
import edu.learnsphere.core.util.Json;
import java.util.List;
import java.util.Objects;

/**
 * The content of a {@code .lsprogress} file: one learner's per-item attempt summaries for a course, under a
 * label the learner chooses. It carries no answers, chat or other personal data. The same JSON (limited to
 * the course's items) is stored in {@code class_insights.payload_json}; read it back with {@link #fromPayload}.
 *
 * @param format        always {@link #FORMAT}
 * @param formatVersion {@link #VERSION} when written by this version of LearnSphere
 * @param packageId     the course's package id, matched against the creator's course
 * @param courseTitle   the course title when exported, for display only
 * @param versionNumber the course version the learner had
 * @param learnerLabel  the name the learner chose, at most {@link #MAX_LABEL_LENGTH} characters
 * @param exportedAt    when the file was written (epoch millis)
 * @param items         items the learner attempted
 */
public record ProgressFile(String format, int formatVersion, String packageId, String courseTitle, int versionNumber,
                           String learnerLabel, long exportedAt, List<ItemProgress> items) {

    /** The {@code format} of every progress file. */
    public static final String FORMAT = "learnsphere-progress";
    /** The format version this code writes and reads. */
    public static final int VERSION = 1;
    /** Longest learner label. */
    public static final int MAX_LABEL_LENGTH = 60;

    public ProgressFile {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * A learner's attempts on one item.
     *
     * @param averageScore      mean score 0–1
     * @param averageDurationMs mean answering time
     * @param lapses            times the item was forgotten after being learned
     */
    public record ItemProgress(String itemId, int attempts, int correct, double averageScore, long averageDurationMs,
                               int lapses) {

        /** Share of correct attempts, 0–1. */
        public double accuracy() {
            return attempts == 0 ? 0 : (double) correct / attempts;
        }

        boolean isValid() {
            return Ids.isValid(itemId) && attempts > 0 && correct >= 0 && correct <= attempts
                    && averageScore >= 0 && averageScore <= 1 && averageDurationMs >= 0 && lapses >= 0;
        }
    }

    /** Reads a stored {@code class_insights.payload_json}. */
    public static ProgressFile fromPayload(String payloadJson) {
        return Json.read(Objects.requireNonNull(payloadJson, "payloadJson"), ProgressFile.class);
    }

    /** The JSON stored in {@code class_insights.payload_json}. */
    public String toPayload() {
        return Json.write(this);
    }

    /** A label as stored: trimmed, inner whitespace collapsed. */
    static String normalizeLabel(String label) {
        return label == null ? "" : label.strip().replaceAll("\\s+", " ");
    }

    /** True when the fields a reader relies on are present and in range. */
    boolean isValid() {
        String label = normalizeLabel(learnerLabel);
        return FORMAT.equals(format) && formatVersion >= 1 && packageId != null && !packageId.isBlank()
                && versionNumber >= 0 && !label.isEmpty() && label.length() <= MAX_LABEL_LENGTH && exportedAt >= 0
                && items.stream().allMatch(i -> i != null && i.isValid())
                && items.stream().map(ItemProgress::itemId).distinct().count() == items.size();
    }
}
