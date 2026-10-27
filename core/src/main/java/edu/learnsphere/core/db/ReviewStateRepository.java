package edu.learnsphere.core.db;

import edu.learnsphere.core.model.ReviewPhase;
import edu.learnsphere.core.model.ReviewState;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.jdbi.v3.core.statement.Query;

/**
 * FSRS memory state per item. Queries that feed study sessions and forecasts only consider approved
 * items, because learners only ever study approved items.
 */
public final class ReviewStateRepository {

    static final RowMapper<ReviewState> STATE = (rs, ctx) -> new ReviewState(
            rs.getString("item_id"),
            ReviewPhase.fromCode(rs.getString("state")),
            rs.getDouble("stability"),
            rs.getDouble("difficulty"),
            rs.getLong("due_at"),
            Rows.nullableLong(rs, "last_review_at"),
            rs.getInt("reps"),
            rs.getInt("lapses"),
            rs.getInt("scheduled_days"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final String UPSERT = """
            INSERT INTO review_states (item_id, state, stability, difficulty, due_at, last_review_at, reps, lapses,
              scheduled_days, created_at, updated_at)
            VALUES (:itemId, :state, :stability, :difficulty, :dueAt, :lastReviewAt, :reps, :lapses, :scheduledDays,
              :createdAt, :updatedAt)
            ON CONFLICT (item_id) DO UPDATE SET state = excluded.state, stability = excluded.stability,
              difficulty = excluded.difficulty, due_at = excluded.due_at, last_review_at = excluded.last_review_at,
              reps = excluded.reps, lapses = excluded.lapses, scheduled_days = excluded.scheduled_days,
              updated_at = excluded.updated_at
            """;

    private final Database db;
    private final Clock clock;

    public ReviewStateRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Inserts or replaces the state of {@code state.itemId()}. */
    public void upsert(ReviewState state) {
        upsertAll(List.of(state));
    }

    public void upsertAll(Collection<ReviewState> states) {
        if (states.isEmpty()) {
            return;
        }
        db.useTransaction(h -> {
            PreparedBatch batch = h.prepareBatch(UPSERT);
            for (ReviewState s : states) {
                batch.bind("itemId", s.itemId()).bind("state", s.phase().code()).bind("stability", s.stability())
                        .bind("difficulty", s.difficulty()).bind("dueAt", s.dueAt()).bind("lastReviewAt", s.lastReviewAt())
                        .bind("reps", s.reps()).bind("lapses", s.lapses()).bind("scheduledDays", s.scheduledDays())
                        .bind("createdAt", s.createdAt()).bind("updatedAt", s.updatedAt())
                        .add();
            }
            batch.execute();
        });
    }

    public Optional<ReviewState> find(String itemId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM review_states WHERE item_id = :i")
                .bind("i", itemId).map(STATE).findOne());
    }

    /** States for these items keyed by item id (items never seen are absent). */
    public Map<String, ReviewState> findAll(Collection<String> itemIds) {
        Map<String, ReviewState> result = new HashMap<>();
        if (itemIds.isEmpty()) {
            return result;
        }
        db.withHandle(h -> h.createQuery("SELECT * FROM review_states WHERE item_id IN (<ids>)")
                .bindList("ids", List.copyOf(itemIds)).map(STATE).list()).forEach(s -> result.put(s.itemId(), s));
        return result;
    }

    /**
     * Approved, previously reviewed items due at or before {@code before}, earliest first.
     *
     * @param courseId only this course, or null for all courses
     * @param moduleId only this module, or null
     */
    public List<ReviewState> listDue(String courseId, String moduleId, long before, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.* FROM review_states r JOIN learning_items i ON i.id = r.item_id
                WHERE i.status = 'approved' AND r.state <> 'new' AND r.due_at <= :before
                """);
        appendScope(sql, courseId, moduleId);
        sql.append(" ORDER BY r.due_at LIMIT :limit");
        return db.withHandle(h -> bindScope(h.createQuery(sql.toString()), courseId, moduleId)
                .bind("before", before).bind("limit", limit).map(STATE).list());
    }

    /** How many approved items are due at or before {@code before} (course and module may be null). */
    public int countDue(String courseId, String moduleId, long before) {
        StringBuilder sql = new StringBuilder("""
                SELECT COUNT(*) FROM review_states r JOIN learning_items i ON i.id = r.item_id
                WHERE i.status = 'approved' AND r.state <> 'new' AND r.due_at <= :before
                """);
        appendScope(sql, courseId, moduleId);
        return db.withHandle(h -> bindScope(h.createQuery(sql.toString()), courseId, moduleId)
                .bind("before", before).mapTo(Integer.class).one());
    }

    /** Due counts per course at or before {@code before}, keyed by course id (courses with none are absent). */
    public Map<String, Integer> countDueByCourse(long before) {
        return db.withHandle(h -> {
            Map<String, Integer> result = new HashMap<>();
            h.createQuery("""
                            SELECT i.course_id, COUNT(*) AS n FROM review_states r JOIN learning_items i ON i.id = r.item_id
                            WHERE i.status = 'approved' AND r.state <> 'new' AND r.due_at <= :before
                            GROUP BY i.course_id
                            """)
                    .bind("before", before)
                    .map((rs, ctx) -> Map.entry(rs.getString("course_id"), rs.getInt("n")))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    /**
     * Due times of approved, reviewed items in {@code [from, to)}, for the review forecast. The caller
     * buckets them into days in the learner's time zone.
     */
    public List<Long> dueTimesBetween(String courseId, long from, long to) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.due_at FROM review_states r JOIN learning_items i ON i.id = r.item_id
                WHERE i.status = 'approved' AND r.state <> 'new' AND r.due_at >= :from AND r.due_at < :to
                """);
        appendScope(sql, courseId, null);
        sql.append(" ORDER BY r.due_at");
        return db.withHandle(h -> bindScope(h.createQuery(sql.toString()), courseId, null)
                .bind("from", from).bind("to", to).mapTo(Long.class).list());
    }

    /** The earliest due time after {@code after} among approved reviewed items, if any ("next review"). */
    public Optional<Long> nextDueAfter(String courseId, long after) {
        StringBuilder sql = new StringBuilder("""
                SELECT MIN(r.due_at) FROM review_states r JOIN learning_items i ON i.id = r.item_id
                WHERE i.status = 'approved' AND r.state <> 'new' AND r.due_at > :after
                """);
        appendScope(sql, courseId, null);
        return db.withHandle(h -> bindScope(h.createQuery(sql.toString()), courseId, null)
                .bind("after", after).mapTo(Long.class).findOne());
    }

    /** Every state of a course's approved items (for mastery and retention calculations). */
    public List<ReviewState> listByCourse(String courseId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT r.* FROM review_states r JOIN learning_items i ON i.id = r.item_id
                        WHERE i.course_id = :c AND i.status = 'approved'
                        """)
                .bind("c", courseId).map(STATE).list());
    }

    public boolean delete(String itemId) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM review_states WHERE item_id = :i").bind("i", itemId).execute()) > 0;
    }

    private static void appendScope(StringBuilder sql, String courseId, String moduleId) {
        if (courseId != null) {
            sql.append(" AND i.course_id = :courseId");
        }
        if (moduleId != null) {
            sql.append(" AND i.module_id = :moduleId");
        }
    }

    private static Query bindScope(Query query, String courseId, String moduleId) {
        if (courseId != null) {
            query.bind("courseId", courseId);
        }
        if (moduleId != null) {
            query.bind("moduleId", moduleId);
        }
        return query;
    }
}
