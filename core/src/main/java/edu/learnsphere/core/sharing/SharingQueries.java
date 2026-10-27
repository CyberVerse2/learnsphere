package edu.learnsphere.core.sharing;

import edu.learnsphere.core.db.Database;
import edu.learnsphere.core.error.ConflictException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The few reads sharing needs that no repository offers: id ownership and passage fingerprints. */
final class SharingQueries {

    /** Tables whose rows a package carries with the creator's ids; every one has a {@code course_id}. */
    enum Table {
        MODULES("modules"), CONCEPTS("concepts"), ITEMS("learning_items"), DOCUMENTS("documents"), CHUNKS("chunks");

        private final String sql;

        Table(String sql) {
            this.sql = sql;
        }
    }

    /** The fields of a stored passage that a document fingerprint covers. */
    record ChunkKey(String id, int ordinal, int pageStart, int pageEnd, String sectionTitle, String text, int tokenEstimate,
                    boolean searchable) {
    }

    private static final int BATCH = 500;

    private final Database db;

    SharingQueries(Database db) {
        this.db = Objects.requireNonNull(db, "db");
    }

    /** The course of each id that exists in {@code table}, keyed by id. */
    Map<String, String> owners(Table table, Collection<String> ids) {
        Map<String, String> owners = new HashMap<>();
        List<String> all = new ArrayList<>(ids);
        for (int from = 0; from < all.size(); from += BATCH) {
            List<String> batch = all.subList(from, Math.min(all.size(), from + BATCH));
            db.withHandle(h -> h.createQuery("SELECT id, course_id FROM " + table.sql + " WHERE id IN (<ids>)")
                            .bindList("ids", batch)
                            .map((rs, ctx) -> Map.entry(rs.getString("id"), rs.getString("course_id")))
                            .list())
                    .forEach(e -> owners.put(e.getKey(), e.getValue()));
        }
        return owners;
    }

    /**
     * Refuses ids that belong to a course other than {@code courseId} (any course when it is null).
     *
     * @throws ConflictException {@link SharingMessages#COURSE_CLASHES}
     */
    void requireNoClash(String courseId, Map<Table, ? extends Collection<String>> idsByTable) {
        idsByTable.forEach((table, ids) -> owners(table, ids).forEach((id, owner) -> {
            if (!owner.equals(courseId)) {
                throw new ConflictException(SharingMessages.COURSE_CLASHES,
                        table.sql + " row " + id + " belongs to course " + owner);
            }
        }));
    }

    /**
     * The passages of a document without their vectors, in order; {@code searchable} is true when a vector of
     * {@code model} with {@code dimensions} values is stored.
     */
    List<ChunkKey> chunkKeys(String documentId, String model, int dimensions) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT id, ordinal, page_start, page_end, section_title, text, token_estimate,
                          (embedding IS NOT NULL AND embedding_model IS :model AND length(embedding) = :bytes) AS searchable
                        FROM chunks WHERE document_id = :d ORDER BY ordinal
                        """)
                .bind("d", documentId).bind("model", model).bind("bytes", dimensions * Float.BYTES)
                .map((rs, ctx) -> new ChunkKey(rs.getString("id"), rs.getInt("ordinal"), rs.getInt("page_start"),
                        rs.getInt("page_end"), rs.getString("section_title"), rs.getString("text"),
                        rs.getInt("token_estimate"), rs.getInt("searchable") == 1))
                .list());
    }
}
