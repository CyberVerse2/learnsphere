package edu.learnsphere.core.model;

import edu.learnsphere.core.util.Ids;
import java.util.Objects;

/**
 * A row of {@code flags}: a problem reported on an item.
 *
 * @param note       free text, may be empty
 * @param resolvedAt when the creator resolved it, or null while open
 */
public record Flag(String id, String itemId, FlagReason reason, String note, FlagSource source, Long resolvedAt,
                   long createdAt, long updatedAt) {

    public Flag {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(itemId, "itemId");
        Objects.requireNonNull(reason, "reason");
        note = Objects.requireNonNullElse(note, "");
        Objects.requireNonNull(source, "source");
    }

    /** A new open flag. */
    public static Flag open(String itemId, FlagReason reason, String note, FlagSource source, long now) {
        return new Flag(Ids.newId(), itemId, reason, note, source, null, now, now);
    }

    /** True until resolved. */
    public boolean isOpen() {
        return resolvedAt == null;
    }
}
