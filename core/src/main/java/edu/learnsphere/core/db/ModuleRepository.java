package edu.learnsphere.core.db;

import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.CourseModule;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.PreparedBatch;

/** Modules of a course, kept in {@code position} order. */
public final class ModuleRepository {

    static final RowMapper<CourseModule> MODULE = (rs, ctx) -> new CourseModule(
            rs.getString("id"),
            rs.getString("course_id"),
            rs.getString("title"),
            rs.getString("summary"),
            rs.getInt("position"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    /**
     * A module with what the course page's module path shows.
     *
     * @param approvedItems approved items
     * @param draftItems    items waiting for review
     * @param documents     PDFs in the module
     * @param startedItems  approved items reviewed at least once
     * @param dueItems      approved, reviewed items due now in the study scope that was asked for
     */
    public record ModuleCounts(CourseModule module, int approvedItems, int draftItems, int documents, int startedItems,
                               int dueItems) {

        public ModuleCounts {
            Objects.requireNonNull(module, "module");
        }

        /** Share of approved items reviewed at least once, 0–1 (0 without approved items). */
        public double completion() {
            return approvedItems == 0 ? 0 : Math.min(1, (double) startedItems / approvedItems);
        }
    }

    private final Database db;
    private final Clock clock;

    public ModuleRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void insert(CourseModule module) {
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO modules (id, course_id, title, summary, position, created_at, updated_at)
                        VALUES (:id, :courseId, :title, :summary, :position, :createdAt, :updatedAt)
                        """)
                .bind("id", module.id()).bind("courseId", module.courseId()).bind("title", module.title())
                .bind("summary", module.summary()).bind("position", module.position())
                .bind("createdAt", module.createdAt()).bind("updatedAt", module.updatedAt())
                .execute());
    }

    /** Inserts a new module at the end of the course and returns it. */
    public CourseModule append(String courseId, String title) {
        return db.inTransaction(h -> {
            CourseModule module = CourseModule.create(courseId, title, nextPosition(courseId), clock.millis());
            insert(module);
            return module;
        });
    }

    /** Writes title, summary and position and stamps {@code updated_at}. */
    public boolean update(CourseModule module) {
        return db.withHandle(h -> h.createUpdate("""
                        UPDATE modules SET title = :title, summary = :summary, position = :position, updated_at = :now
                        WHERE id = :id
                        """)
                .bind("id", module.id()).bind("title", module.title()).bind("summary", module.summary())
                .bind("position", module.position()).bind("now", clock.millis())
                .execute()) > 0;
    }

    public Optional<CourseModule> find(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM modules WHERE id = :id").bind("id", id).map(MODULE).findOne());
    }

    /** @throws NotFoundException when there is no such module */
    public CourseModule get(String id) {
        return find(id).orElseThrow(() -> NotFoundException.of(UserMessage.MODULE_NOT_FOUND, "module", id));
    }

    /** Modules of a course in order. */
    public List<CourseModule> listByCourse(String courseId) {
        return db.withHandle(h -> h.createQuery(
                        "SELECT * FROM modules WHERE course_id = :c ORDER BY position, created_at")
                .bind("c", courseId).map(MODULE).list());
    }

    /**
     * Modules of a course in order, with their counts. Due items are approved, reviewed items of the module
     * due at or before {@code now} and inside {@code dueScope} (pass {@link StudyScope#enrolled()} so a course
     * the learner is not studying shows nothing due).
     */
    public List<ModuleCounts> listWithCounts(String courseId, StudyScope dueScope, long now) {
        String sql = """
                SELECT m.*,
                  (SELECT COUNT(*) FROM learning_items i WHERE i.module_id = m.id AND i.status = 'approved') AS approved_items,
                  (SELECT COUNT(*) FROM learning_items i WHERE i.module_id = m.id AND i.status = 'draft') AS draft_items,
                  (SELECT COUNT(*) FROM documents d WHERE d.module_id = m.id) AS document_count,
                  (SELECT COUNT(*) FROM learning_items i JOIN review_states r ON r.item_id = i.id
                     WHERE i.module_id = m.id AND i.status = 'approved' AND r.state <> 'new') AS started_items,
                  (SELECT COUNT(*) FROM learning_items i JOIN review_states r ON r.item_id = i.id"""
                + dueScope.joins()
                + " WHERE i.module_id = m.id AND i.status = 'approved' AND r.state <> 'new' AND r.due_at <= :now AND "
                + dueScope.condition() + ") AS due_items"
                + " FROM modules m WHERE m.course_id = :c ORDER BY m.position, m.created_at";
        return db.withHandle(h -> dueScope.bind(h.createQuery(sql)).bind("c", courseId).bind("now", now)
                .map((rs, ctx) -> new ModuleCounts(MODULE.map(rs, ctx), rs.getInt("approved_items"), rs.getInt("draft_items"),
                        rs.getInt("document_count"), rs.getInt("started_items"), rs.getInt("due_items")))
                .list());
    }

    public int countByCourse(String courseId) {
        return db.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM modules WHERE course_id = :c")
                .bind("c", courseId).mapTo(Integer.class).one());
    }

    /** The position after the last module (0 for an empty course). */
    public int nextPosition(String courseId) {
        return db.withHandle(h -> h.createQuery("SELECT COALESCE(MAX(position) + 1, 0) FROM modules WHERE course_id = :c")
                .bind("c", courseId).mapTo(Integer.class).one());
    }

    public boolean rename(String id, String title) {
        return db.withHandle(h -> h.createUpdate("UPDATE modules SET title = :t, updated_at = :now WHERE id = :id")
                .bind("id", id).bind("t", title).bind("now", clock.millis()).execute()) > 0;
    }

    public boolean updateSummary(String id, String summary) {
        return db.withHandle(h -> h.createUpdate("UPDATE modules SET summary = :s, updated_at = :now WHERE id = :id")
                .bind("id", id).bind("s", summary).bind("now", clock.millis()).execute()) > 0;
    }

    /**
     * Sets positions 0, 1, 2… in the order given, in one transaction. Ids of other courses are ignored;
     * modules of the course that are not listed keep their relative order after the listed ones.
     */
    public void reorder(String courseId, List<String> orderedIds) {
        db.useTransaction(h -> {
            List<String> existing = listByCourse(courseId).stream().map(CourseModule::id).toList();
            List<String> order = new ArrayList<>(orderedIds.stream().filter(existing::contains).distinct().toList());
            existing.stream().filter(id -> !order.contains(id)).forEach(order::add);
            long now = clock.millis();
            PreparedBatch batch = h.prepareBatch(
                    "UPDATE modules SET position = :p, updated_at = :now WHERE id = :id AND course_id = :c");
            for (int i = 0; i < order.size(); i++) {
                batch.bind("p", i).bind("now", now).bind("id", order.get(i)).bind("c", courseId).add();
            }
            batch.execute();
        });
    }

    /**
     * Deletes the module and closes the gap in positions. Documents, passages, concepts and items in it
     * stay in the course without a module.
     */
    public boolean delete(String id) {
        return db.inTransaction(h -> {
            Optional<CourseModule> module = find(id);
            if (module.isEmpty()) {
                return false;
            }
            h.createUpdate("DELETE FROM modules WHERE id = :id").bind("id", id).execute();
            h.createUpdate("UPDATE modules SET position = position - 1, updated_at = :now WHERE course_id = :c AND position > :p")
                    .bind("c", module.get().courseId()).bind("p", module.get().position()).bind("now", clock.millis())
                    .execute();
            return true;
        });
    }
}
