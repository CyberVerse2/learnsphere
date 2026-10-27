package edu.learnsphere.core.model;

import java.util.Objects;

/**
 * A row of {@code audit_log}: a record of a significant change, for troubleshooting and history.
 *
 * @param action     what happened, as a lowercase verb phrase such as {@code "publish"} or {@code "delete"}
 * @param entityType kind of record affected
 * @param entityId   id of the record affected, or null
 * @param detail     extra context, or null
 */
public record AuditLogEntry(String id, String action, EntityType entityType, String entityId, String detail, long createdAt) {

    public AuditLogEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(entityType, "entityType");
    }
}
