package edu.learnsphere.core.db;

import edu.learnsphere.core.model.Attempt;
import edu.learnsphere.core.model.Confidence;
import edu.learnsphere.core.model.GradedBy;
import edu.learnsphere.core.model.Rating;
import edu.learnsphere.core.model.SessionKind;
import edu.learnsphere.core.model.SessionRecord;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.Query;

/** Study sessions and the attempts made in them. */
public final class StudySessionRepository {

    static final RowMapper<SessionRecord> SESSION = (rs, ctx) -> new SessionRecord(
            rs.getString("id"),
            rs.getString("course_id"),
            rs.getString("module_id"),
            SessionKind.fromCode(rs.getString("kind")),
            rs.getLong("started_at"),
            Rows.nullableLong(rs, "ended_at"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    static final RowMapper<Attempt> ATTEMPT = (rs, ctx) -> {
        Integer confidence = Rows.nullableInt(rs, "confidence");
        return new Attempt(
                rs.getString("id"),
                rs.getString("item_id"),
                rs.getString("session_id"),
                rs.getString("response"),
                Rows.bool(rs, "correct"),
                rs.getDouble("score"),
                Rating.fromValue(rs.getInt("rating")),
                confidence == null ? null : Confidence.fromValue(confidence),
                Rows.bool(rs, "hint_used"),
                rs.getLong("duration_ms"),
                GradedBy.fromCode(rs.getString("graded_by")),
                rs.getString("feedback"),
                Rows.bool(rs, "disputed"),
                rs.getLong("created_at"),
                rs.getLong("updated_at"));
    };

    /**
     * An attempt on an item tagged with a concept, for per-concept accuracy.
     *
     * @param rank 1 for the concept's most recent attempt, 2 for the one before, and so on
     */
    public record ConceptAttempt(String conceptId, String itemId, boolean correct, double score, long createdAt, int rank) {
    }

    /** Aggregated attempts on one item. */
    public record ItemAttemptStats(String itemId, int attempts, int correct, double averageScore, long averageDurationMs,
                                   long lastAttemptAt) {

        /** Share of correct attempts, 0–1. */
        public double accuracy() {
            return attempts == 0 ? 0 : (double) correct / attempts;
        }
    }

    private final Database db;
    private final Clock clock;

    public StudySessionRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- sessions -----------------------------------------------------------------------------

    public void insertSession(SessionRecord s) {
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO study_sessions (id, course_id, module_id, kind, started_at, ended_at, created_at, updated_at)
                        VALUES (:id, :courseId, :moduleId, :kind, :startedAt, :endedAt, :createdAt, :updatedAt)
                        """)
                .bind("id", s.id()).bind("courseId", s.courseId()).bind("moduleId", s.moduleId())
                .bind("kind", s.kind().code()).bind("startedAt", s.startedAt()).bind("endedAt", s.endedAt())
                .bind("createdAt", s.createdAt()).bind("updatedAt", s.updatedAt())
                .execute());
    }

    public Optional<SessionRecord> findSession(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM study_sessions WHERE id = :id").bind("id", id).map(SESSION).findOne());
    }

    /** Marks a session finished at {@code endedAt}. */
    public boolean endSession(String id, long endedAt) {
        return db.withHandle(h -> h.createUpdate(
                        "UPDATE study_sessions SET ended_at = :t, updated_at = :now WHERE id = :id AND ended_at IS NULL")
                .bind("id", id).bind("t", endedAt).bind("now", clock.millis()).execute()) > 0;
    }

    /** Recent sessions, newest first (course may be null for all courses). */
    public List<SessionRecord> listSessions(String courseId, int limit) {
        String sql = "SELECT * FROM study_sessions" + (courseId == null ? "" : " WHERE course_id = :c")
                + " ORDER BY started_at DESC LIMIT :limit";
        return db.withHandle(h -> {
            Query q = h.createQuery(sql).bind("limit", limit);
            if (courseId != null) {
                q.bind("c", courseId);
            }
            return q.map(SESSION).list();
        });
    }

    // ---- attempts -----------------------------------------------------------------------------

    public void insertAttempt(Attempt a) {
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO attempts (id, item_id, session_id, response, correct, score, rating, confidence, hint_used,
                          duration_ms, graded_by, feedback, disputed, created_at, updated_at)
                        VALUES (:id, :itemId, :sessionId, :response, :correct, :score, :rating, :confidence, :hintUsed,
                          :durationMs, :gradedBy, :feedback, :disputed, :createdAt, :updatedAt)
                        """)
                .bind("id", a.id()).bind("itemId", a.itemId()).bind("sessionId", a.sessionId())
                .bind("response", a.response()).bind("correct", Rows.flag(a.correct())).bind("score", a.score())
                .bind("rating", a.rating().value())
                .bind("confidence", a.confidence() == null ? null : a.confidence().value())
                .bind("hintUsed", Rows.flag(a.hintUsed())).bind("durationMs", a.durationMs())
                .bind("gradedBy", a.gradedBy().code()).bind("feedback", a.feedback())
                .bind("disputed", Rows.flag(a.disputed())).bind("createdAt", a.createdAt())
                .bind("updatedAt", a.updatedAt())
                .execute());
    }

    public Optional<Attempt> findAttempt(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM attempts WHERE id = :id").bind("id", id).map(ATTEMPT).findOne());
    }

    /** Attempts on an item, newest first. */
    public List<Attempt> listAttemptsForItem(String itemId, int limit) {
        return db.withHandle(h -> h.createQuery(
                        "SELECT * FROM attempts WHERE item_id = :i ORDER BY created_at DESC, id LIMIT :limit")
                .bind("i", itemId).bind("limit", limit).map(ATTEMPT).list());
    }

    /** Attempts in a session, in the order they were made. */
    public List<Attempt> listAttemptsForSession(String sessionId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM attempts WHERE session_id = :s ORDER BY created_at, id")
                .bind("s", sessionId).map(ATTEMPT).list());
    }

    /** Attempts on a course's items since {@code since}, oldest first. */
    public List<Attempt> listAttemptsForCourse(String courseId, long since) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT a.* FROM attempts a JOIN learning_items i ON i.id = a.item_id
                        WHERE i.course_id = :c AND a.created_at >= :since ORDER BY a.created_at, a.id
                        """)
                .bind("c", courseId).bind("since", since).map(ATTEMPT).list());
    }

    /** Times of every attempt since {@code since} across courses, oldest first (for streaks and the week strip). */
    public List<Long> attemptTimesSince(long since) {
        return db.withHandle(h -> h.createQuery("SELECT created_at FROM attempts WHERE created_at >= :since ORDER BY created_at")
                .bind("since", since).mapTo(Long.class).list());
    }

    /** Attempts made since {@code since} (course may be null for all courses). */
    public int countAttemptsSince(String courseId, long since) {
        String sql = courseId == null
                ? "SELECT COUNT(*) FROM attempts WHERE created_at >= :since"
                : "SELECT COUNT(*) FROM attempts a JOIN learning_items i ON i.id = a.item_id"
                        + " WHERE i.course_id = :c AND a.created_at >= :since";
        return db.withHandle(h -> {
            Query q = h.createQuery(sql).bind("since", since);
            if (courseId != null) {
                q.bind("c", courseId);
            }
            return q.mapTo(Integer.class).one();
        });
    }

    /**
     * How many distinct items had their first attempt ever at or after {@code since}, across every course:
     * the new items introduced since then, which the daily new-item limit counts.
     */
    public int countItemsFirstAttemptedSince(long since) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT COUNT(DISTINCT a.item_id) FROM attempts a
                        WHERE a.created_at >= :since
                          AND NOT EXISTS (SELECT 1 FROM attempts b WHERE b.item_id = a.item_id AND b.created_at < :since)
                        """)
                .bind("since", since).mapTo(Integer.class).one());
    }

    /**
     * The most recent {@code perConcept} attempts for every concept of a course (newest first within
     * each concept), in one query.
     */
    public List<ConceptAttempt> recentAttemptsByConcept(String courseId, int perConcept) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT concept_id, item_id, correct, score, created_at, rnk FROM (
                          SELECT ic.concept_id, a.item_id, a.correct, a.score, a.created_at,
                            ROW_NUMBER() OVER (PARTITION BY ic.concept_id ORDER BY a.created_at DESC, a.id) AS rnk
                          FROM attempts a
                          JOIN item_concepts ic ON ic.item_id = a.item_id
                          JOIN learning_items i ON i.id = a.item_id
                          WHERE i.course_id = :c
                        ) WHERE rnk <= :n ORDER BY concept_id, rnk
                        """)
                .bind("c", courseId).bind("n", perConcept)
                .map((rs, ctx) -> new ConceptAttempt(rs.getString("concept_id"), rs.getString("item_id"),
                        Rows.bool(rs, "correct"), rs.getDouble("score"), rs.getLong("created_at"), rs.getInt("rnk")))
                .list());
    }

    /** Attempt statistics per item of a course, keyed by item id (items without attempts are absent). */
    public Map<String, ItemAttemptStats> itemStats(String courseId) {
        return db.withHandle(h -> {
            Map<String, ItemAttemptStats> result = new HashMap<>();
            h.createQuery("""
                            SELECT a.item_id, COUNT(*) AS attempts, SUM(a.correct) AS correct, AVG(a.score) AS avg_score,
                              CAST(AVG(a.duration_ms) AS INTEGER) AS avg_duration, MAX(a.created_at) AS last_at
                            FROM attempts a JOIN learning_items i ON i.id = a.item_id
                            WHERE i.course_id = :c GROUP BY a.item_id
                            """)
                    .bind("c", courseId)
                    .map((rs, ctx) -> new ItemAttemptStats(rs.getString("item_id"), rs.getInt("attempts"),
                            rs.getInt("correct"), rs.getDouble("avg_score"), rs.getLong("avg_duration"), rs.getLong("last_at")))
                    .forEach(s -> result.put(s.itemId(), s));
            return result;
        });
    }

    /** Marks an attempt as disputed and records the rating it was changed to. */
    public boolean markDisputed(String attemptId, Rating newRating) {
        return db.withHandle(h -> h.createUpdate(
                        "UPDATE attempts SET disputed = 1, rating = :r, updated_at = :now WHERE id = :id")
                .bind("id", attemptId).bind("r", newRating.value()).bind("now", clock.millis()).execute()) > 0;
    }
}
