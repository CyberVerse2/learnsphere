package edu.learnsphere.core.db;

import edu.learnsphere.core.model.Concept;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.PreparedBatch;

/** Key concepts of courses and modules. Item tags live in {@link LearningItemRepository}. */
public final class ConceptRepository {

    static final RowMapper<Concept> CONCEPT = (rs, ctx) -> new Concept(
            rs.getString("id"),
            rs.getString("course_id"),
            rs.getString("module_id"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final String INSERT = """
            INSERT INTO concepts (id, course_id, module_id, name, description, created_at, updated_at)
            VALUES (:id, :courseId, :moduleId, :name, :description, :createdAt, :updatedAt)
            """;

    /**
     * How much study a concept has had.
     *
     * @param approvedItems approved items tagged with it
     * @param reviewedItems of those, items reviewed at least once
     * @param attempts      attempts on items tagged with it
     */
    public record ConceptCounts(String conceptId, int approvedItems, int reviewedItems, int attempts) {
    }

    private final Database db;
    private final Clock clock;

    public ConceptRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void insert(Concept concept) {
        insertAll(List.of(concept));
    }

    public void insertAll(Collection<Concept> concepts) {
        if (concepts.isEmpty()) {
            return;
        }
        db.useTransaction(h -> {
            PreparedBatch batch = h.prepareBatch(INSERT);
            for (Concept c : concepts) {
                batch.bind("id", c.id()).bind("courseId", c.courseId()).bind("moduleId", c.moduleId())
                        .bind("name", c.name()).bind("description", c.description())
                        .bind("createdAt", c.createdAt()).bind("updatedAt", c.updatedAt()).add();
            }
            batch.execute();
        });
    }

    public Optional<Concept> find(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM concepts WHERE id = :id").bind("id", id).map(CONCEPT).findOne());
    }

    public List<Concept> listByIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return db.withHandle(h -> h.createQuery("SELECT * FROM concepts WHERE id IN (<ids>) ORDER BY name")
                .bindList("ids", List.copyOf(ids)).map(CONCEPT).list());
    }

    /** Concepts of a course, grouped by module order, then by name. */
    public List<Concept> listByCourse(String courseId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT c.* FROM concepts c LEFT JOIN modules m ON m.id = c.module_id
                        WHERE c.course_id = :c ORDER BY COALESCE(m.position, 1000000), c.name COLLATE NOCASE
                        """)
                .bind("c", courseId).map(CONCEPT).list());
    }

    /** Concepts of a module by name. */
    public List<Concept> listByModule(String moduleId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM concepts WHERE module_id = :m ORDER BY name COLLATE NOCASE")
                .bind("m", moduleId).map(CONCEPT).list());
    }

    /** The concept with this name in the module (case-insensitive), to avoid duplicates. */
    public Optional<Concept> findByName(String courseId, String moduleId, String name) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT * FROM concepts WHERE course_id = :c AND module_id IS :m AND name = :n COLLATE NOCASE
                        LIMIT 1
                        """)
                .bind("c", courseId).bind("m", moduleId).bind("n", name.strip()).map(CONCEPT).findOne());
    }

    public boolean update(String id, String name, String description) {
        return db.withHandle(h -> h.createUpdate(
                        "UPDATE concepts SET name = :n, description = :d, updated_at = :now WHERE id = :id")
                .bind("id", id).bind("n", name).bind("d", description).bind("now", clock.millis())
                .execute()) > 0;
    }

    public boolean delete(String id) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM concepts WHERE id = :id").bind("id", id).execute()) > 0;
    }

    /** Deletes a module's concepts (before extracting them again). Item tags go with them. */
    public int deleteByModule(String moduleId) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM concepts WHERE module_id = :m").bind("m", moduleId).execute());
    }

    /** {@link ConceptCounts} for every concept of a course, keyed by concept id. */
    public Map<String, ConceptCounts> studyCounts(String courseId) {
        return db.withHandle(h -> {
            Map<String, ConceptCounts> result = new HashMap<>();
            h.createQuery("""
                            SELECT c.id,
                              (SELECT COUNT(*) FROM item_concepts ic JOIN learning_items i ON i.id = ic.item_id
                                 WHERE ic.concept_id = c.id AND i.status = 'approved') AS approved_items,
                              (SELECT COUNT(*) FROM item_concepts ic JOIN learning_items i ON i.id = ic.item_id
                                 JOIN review_states r ON r.item_id = i.id
                                 WHERE ic.concept_id = c.id AND i.status = 'approved' AND r.state <> 'new') AS reviewed_items,
                              (SELECT COUNT(*) FROM item_concepts ic JOIN attempts a ON a.item_id = ic.item_id
                                 WHERE ic.concept_id = c.id) AS attempts
                            FROM concepts c WHERE c.course_id = :c
                            """)
                    .bind("c", courseId)
                    .map((rs, ctx) -> new ConceptCounts(rs.getString("id"), rs.getInt("approved_items"),
                            rs.getInt("reviewed_items"), rs.getInt("attempts")))
                    .forEach(counts -> result.put(counts.conceptId(), counts));
            return result;
        });
    }

    /** Approved items tagged with each concept of a course, keyed by concept id. */
    public Map<String, Integer> approvedItemCounts(String courseId) {
        return db.withHandle(h -> {
            Map<String, Integer> result = new HashMap<>();
            h.createQuery("""
                            SELECT ic.concept_id, COUNT(*) AS n FROM item_concepts ic
                            JOIN learning_items i ON i.id = ic.item_id
                            WHERE i.course_id = :c AND i.status = 'approved'
                            GROUP BY ic.concept_id
                            """)
                    .bind("c", courseId)
                    .map((rs, ctx) -> Map.entry(rs.getString("concept_id"), rs.getInt("n")))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }
}
