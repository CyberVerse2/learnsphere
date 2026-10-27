package edu.learnsphere.core.events;

import java.util.Objects;
import java.util.Set;

/**
 * Learning items of a course were created, edited, approved, rejected, retired or deleted.
 *
 * @param moduleId the module concerned, or null for several or none
 * @param itemIds  the items concerned; empty when many changed at once
 */
public record ItemsChanged(String courseId, String moduleId, Set<String> itemIds, ChangeType type) implements Event {

    public ItemsChanged {
        Objects.requireNonNull(courseId, "courseId");
        itemIds = itemIds == null ? Set.of() : Set.copyOf(itemIds);
        Objects.requireNonNull(type, "type");
    }
}
