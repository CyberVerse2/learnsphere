package edu.learnsphere.core.spi;

import edu.learnsphere.core.model.SupportStatus;
import java.util.Objects;

/**
 * Whether a passage supports an item's answer.
 *
 * @param note one short sentence for the creator explaining the verdict
 */
public record SupportVerdict(boolean supported, String note) {

    public SupportVerdict {
        note = Objects.requireNonNullElse(note, "");
    }

    /** The stored verdict. */
    public SupportStatus status() {
        return supported ? SupportStatus.SUPPORTED : SupportStatus.UNSUPPORTED;
    }
}
