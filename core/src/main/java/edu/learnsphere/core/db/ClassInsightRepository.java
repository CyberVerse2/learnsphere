package edu.learnsphere.core.db;

import edu.learnsphere.core.model.ClassInsight;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.mapper.RowMapper;

/** Learners' progress summaries imported by a creator. */
public final class ClassInsightRepository {

    static final RowMapper<ClassInsight> INSIGHT = (rs, ctx) -> new ClassInsight(
            rs.getString("id"),
            rs.getString("course_id"),
            rs.getString("learner_label"),
            rs.getString("payload_json"),
            rs.getLong("imported_at"),
            rs.getLong("created_at"));

    private final Database db;
    private final Clock clock;

    public ClassInsightRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void insert(ClassInsight i) {
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO class_insights (id, course_id, learner_label, payload_json, imported_at, created_at)
                        VALUES (:id, :courseId, :label, :payload, :importedAt, :createdAt)
                        """)
                .bind("id", i.id()).bind("courseId", i.courseId()).bind("label", i.learnerLabel())
                .bind("payload", i.payloadJson()).bind("importedAt", i.importedAt()).bind("createdAt", i.createdAt())
                .execute());
    }

    /** Replaces the summary with the same course and learner label, or inserts it. */
    public void replace(ClassInsight insight) {
        db.useTransaction(h -> {
            h.createUpdate("DELETE FROM class_insights WHERE course_id = :c AND learner_label = :l")
                    .bind("c", insight.courseId()).bind("l", insight.learnerLabel()).execute();
            insert(insight);
        });
    }

    public Optional<ClassInsight> find(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM class_insights WHERE id = :id").bind("id", id).map(INSIGHT).findOne());
    }

    public Optional<ClassInsight> findByLabel(String courseId, String learnerLabel) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM class_insights WHERE course_id = :c AND learner_label = :l")
                .bind("c", courseId).bind("l", learnerLabel).map(INSIGHT).findOne());
    }

    /** Summaries for a course by learner label. */
    public List<ClassInsight> listByCourse(String courseId) {
        return db.withHandle(h -> h.createQuery(
                        "SELECT * FROM class_insights WHERE course_id = :c ORDER BY learner_label COLLATE NOCASE")
                .bind("c", courseId).map(INSIGHT).list());
    }

    public boolean delete(String id) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM class_insights WHERE id = :id").bind("id", id).execute()) > 0;
    }

    public int deleteByCourse(String courseId) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM class_insights WHERE course_id = :c").bind("c", courseId).execute());
    }
}
