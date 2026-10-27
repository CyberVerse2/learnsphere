package edu.learnsphere.core.service.impl;

import edu.learnsphere.core.db.AuditLogRepository;
import edu.learnsphere.core.model.AuditLogEntry;
import edu.learnsphere.core.model.EntityType;

/**
 * Sensitive operations recorded in a profile's {@code audit_log}, with the action text stored for each.
 * Record them inside the transaction that makes the change, so an entry exists exactly when the change does:
 *
 * <pre>{@code
 * repos.database().useTransaction(h -> {
 *     repos.courses().delete(courseId);
 *     AuditAction.DELETE.record(repos.audit(), EntityType.COURSE, courseId, course.title());
 * });
 * }</pre>
 */
public enum AuditAction {

    /** A PDF was imported into a course (entity: document; detail: file name). */
    IMPORT("import"),
    /** A PDF was removed from a course (entity: document; detail: title). */
    REMOVE("remove"),
    /** A course was deleted with everything in it (entity: course; detail: title). */
    DELETE("delete"),
    /** A course version was published (entity: course; detail: version number and item count). */
    PUBLISH("publish"),
    /** A course, progress or feedback file was exported (entity: package or course). */
    EXPORT("export"),
    /** A shared course package was imported (entity: course). */
    IMPORT_PACKAGE("import_package"),
    /** The profile was backed up to a zip (entity: profile). */
    BACKUP("backup"),
    /** The profile was restored from a backup (entity: profile; detail: the id it had in the backup). */
    RESTORE("restore");

    private final String code;

    AuditAction(String code) {
        this.code = code;
    }

    /** The text stored in {@code audit_log.action}. */
    public String code() {
        return code;
    }

    /** Appends an entry for this action, stamped with the repository's clock. */
    public AuditLogEntry record(AuditLogRepository audit, EntityType entityType, String entityId, String detail) {
        return audit.append(code, entityType, entityId, detail);
    }
}
