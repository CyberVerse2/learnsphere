package edu.learnsphere.core.db;

import edu.learnsphere.core.model.AuditLogEntry;
import edu.learnsphere.core.model.EntityType;
import edu.learnsphere.core.util.Ids;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import org.jdbi.v3.core.mapper.RowMapper;

/** Append-only history of significant changes. */
public final class AuditLogRepository {

    static final RowMapper<AuditLogEntry> ENTRY = (rs, ctx) -> new AuditLogEntry(
            rs.getString("id"),
            rs.getString("action"),
            EntityType.fromCode(rs.getString("entity_type")),
            rs.getString("entity_id"),
            rs.getString("detail"),
            rs.getLong("created_at"));

    private final Database db;
    private final Clock clock;

    public AuditLogRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Appends an entry stamped with the current time and returns it. */
    public AuditLogEntry append(String action, EntityType entityType, String entityId, String detail) {
        AuditLogEntry entry = new AuditLogEntry(Ids.newId(), action, entityType, entityId, detail, clock.millis());
        insert(entry);
        return entry;
    }

    public void insert(AuditLogEntry e) {
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO audit_log (id, action, entity_type, entity_id, detail, created_at)
                        VALUES (:id, :action, :type, :entityId, :detail, :createdAt)
                        """)
                .bind("id", e.id()).bind("action", e.action()).bind("type", e.entityType().code())
                .bind("entityId", e.entityId()).bind("detail", e.detail()).bind("createdAt", e.createdAt())
                .execute());
    }

    /** The newest entries first. */
    public List<AuditLogEntry> listRecent(int limit) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM audit_log ORDER BY created_at DESC, rowid DESC LIMIT :n")
                .bind("n", limit).map(ENTRY).list());
    }

    /** Entries about one record, newest first. */
    public List<AuditLogEntry> listForEntity(EntityType type, String entityId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT * FROM audit_log WHERE entity_type = :t AND entity_id = :id
                        ORDER BY created_at DESC, rowid DESC
                        """)
                .bind("t", type.code()).bind("id", entityId).map(ENTRY).list());
    }
}
