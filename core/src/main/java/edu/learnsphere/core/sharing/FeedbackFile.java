package edu.learnsphere.core.sharing;

import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.util.Ids;
import java.util.List;

/**
 * The content of a {@code .lsfeedback} file: a learner's open flags on a course's items, for its creator.
 *
 * @param format        always {@link #FORMAT}
 * @param formatVersion {@link #VERSION} when written by this version of LearnSphere
 * @param packageId     the course's package id, matched against the creator's course
 * @param courseTitle   the course title when exported, for display only
 * @param versionNumber the course version the learner had
 * @param exportedAt    when the file was written (epoch millis)
 * @param flags         the learner's flags
 */
public record FeedbackFile(String format, int formatVersion, String packageId, String courseTitle, int versionNumber,
                           long exportedAt, List<FlagEntry> flags) {

    /** The {@code format} of every feedback file. */
    public static final String FORMAT = "learnsphere-feedback";
    /** The format version this code writes and reads. */
    public static final int VERSION = 1;
    /** Longest note kept from a flag. */
    static final int MAX_NOTE_LENGTH = 2000;

    public FeedbackFile {
        flags = flags == null ? List.of() : List.copyOf(flags);
    }

    /**
     * One flag.
     *
     * @param flagId the learner's flag id, so importing the same file twice adds nothing
     * @param reason a {@link FlagReason} code
     */
    public record FlagEntry(String flagId, String itemId, String reason, String note, long createdAt) {

        boolean isValid() {
            return Ids.isValid(flagId) && Ids.isValid(itemId) && isReason(reason) && createdAt >= 0
                    && (note == null || note.length() <= MAX_NOTE_LENGTH);
        }

        private static boolean isReason(String code) {
            try {
                FlagReason.fromCode(code);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }
    }

    /** True when the fields a reader relies on are present and in range. */
    boolean isValid() {
        return FORMAT.equals(format) && formatVersion >= 1 && packageId != null && !packageId.isBlank()
                && versionNumber >= 0 && exportedAt >= 0 && flags.stream().allMatch(f -> f != null && f.isValid());
    }
}
