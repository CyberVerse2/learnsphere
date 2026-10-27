package edu.learnsphere.core.db;

import edu.learnsphere.core.model.Flag;
import edu.learnsphere.core.model.FlagReason;
import edu.learnsphere.core.model.FlagSource;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.mapper.RowMapper;

/** Problems reported on items. */
public final class FlagRepository {

    static final RowMapper<Flag> FLAG = (rs, ctx) -> new Flag(
            rs.getString("id"),
            rs.getString("item_id"),
            FlagReason.fromCode(rs.getString("reason")),
            rs.getString("note"),
            FlagSource.fromCode(rs.getString("source")),
            Rows.nullableLong(rs, "resolved_at"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final Database db;
    private final Clock clock;

    public FlagRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void insert(Flag f) {
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO flags (id, item_id, reason, note, source, resolved_at, created_at, updated_at)
                        VALUES (:id, :itemId, :reason, :note, :source, :resolvedAt, :createdAt, :updatedAt)
                        """)
                .bind("id", f.id()).bind("itemId", f.itemId()).bind("reason", f.reason().code())
                .bind("note", f.note()).bind("source", f.source().code()).bind("resolvedAt", f.resolvedAt())
                .bind("createdAt", f.createdAt()).bind("updatedAt", f.updatedAt())
                .execute());
    }

    public Optional<Flag> find(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM flags WHERE id = :id").bind("id", id).map(FLAG).findOne());
    }

    /** Flags on an item, newest first. */
    public List<Flag> listByItem(String itemId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM flags WHERE item_id = :i ORDER BY created_at DESC")
                .bind("i", itemId).map(FLAG).list());
    }

    /** Flags on a course's items, newest first; resolved ones only when {@code includeResolved}. */
    public List<Flag> listByCourse(String courseId, boolean includeResolved) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT f.* FROM flags f JOIN learning_items i ON i.id = f.item_id
                        WHERE i.course_id = :c""" + (includeResolved ? "" : " AND f.resolved_at IS NULL")
                        + " ORDER BY f.created_at DESC")
                .bind("c", courseId).map(FLAG).list());
    }

    /** Open flags per item of a course, keyed by item id (items without open flags are absent). */
    public Map<String, Integer> countOpenByItem(String courseId) {
        return db.withHandle(h -> {
            Map<String, Integer> result = new HashMap<>();
            h.createQuery("""
                            SELECT f.item_id, COUNT(*) AS n FROM flags f JOIN learning_items i ON i.id = f.item_id
                            WHERE i.course_id = :c AND f.resolved_at IS NULL GROUP BY f.item_id
                            """)
                    .bind("c", courseId)
                    .map((rs, ctx) -> Map.entry(rs.getString("item_id"), rs.getInt("n")))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    /** Marks a flag resolved. */
    public boolean resolve(String id, long resolvedAt) {
        return db.withHandle(h -> h.createUpdate(
                        "UPDATE flags SET resolved_at = :t, updated_at = :now WHERE id = :id AND resolved_at IS NULL")
                .bind("id", id).bind("t", resolvedAt).bind("now", clock.millis()).execute()) > 0;
    }

    /** Opens a resolved flag again. */
    public boolean reopen(String id) {
        return db.withHandle(h -> h.createUpdate("UPDATE flags SET resolved_at = NULL, updated_at = :now WHERE id = :id")
                .bind("id", id).bind("now", clock.millis()).execute()) > 0;
    }

    public boolean delete(String id) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM flags WHERE id = :id").bind("id", id).execute()) > 0;
    }
}
