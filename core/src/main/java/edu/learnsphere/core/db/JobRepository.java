package edu.learnsphere.core.db;

import edu.learnsphere.core.model.Job;
import edu.learnsphere.core.model.JobKind;
import edu.learnsphere.core.model.JobStatus;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jdbi.v3.core.mapper.RowMapper;

/** Persistent background jobs. */
public final class JobRepository {

    static final RowMapper<Job> JOB = (rs, ctx) -> new Job(
            rs.getString("id"),
            JobKind.fromCode(rs.getString("kind")),
            rs.getString("payload_json"),
            JobStatus.fromCode(rs.getString("status")),
            rs.getInt("priority"),
            rs.getInt("attempts"),
            rs.getDouble("progress"),
            rs.getString("message"),
            rs.getString("subject_id"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private final Database db;
    private final Clock clock;

    public JobRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void insert(Job j) {
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO jobs (id, kind, payload_json, status, priority, attempts, progress, message, subject_id,
                          created_at, updated_at)
                        VALUES (:id, :kind, :payload, :status, :priority, :attempts, :progress, :message, :subjectId,
                          :createdAt, :updatedAt)
                        """)
                .bind("id", j.id()).bind("kind", j.kind().code()).bind("payload", j.payloadJson())
                .bind("status", j.status().code()).bind("priority", j.priority()).bind("attempts", j.attempts())
                .bind("progress", j.progress()).bind("message", j.message()).bind("subjectId", j.subjectId())
                .bind("createdAt", j.createdAt()).bind("updatedAt", j.updatedAt())
                .execute());
    }

    public Optional<Job> find(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM jobs WHERE id = :id").bind("id", id).map(JOB).findOne());
    }

    /** Queued and running jobs, in the order they will run (priority, then age). */
    public List<Job> listActive() {
        return listByStatus(Set.of(JobStatus.QUEUED, JobStatus.RUNNING));
    }

    /** Jobs in any of {@code statuses}, highest priority first, then oldest first. */
    public List<Job> listByStatus(Set<JobStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        return db.withHandle(h -> h.createQuery(
                        "SELECT * FROM jobs WHERE status IN (<statuses>) ORDER BY priority DESC, created_at, id")
                .bindList("statuses", statuses.stream().map(JobStatus::code).toList()).map(JOB).list());
    }

    /** The queued or running job of {@code kind} for {@code subjectId}, if any (to avoid duplicates). */
    public Optional<Job> findActive(JobKind kind, String subjectId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT * FROM jobs WHERE kind = :k AND subject_id IS :s AND status IN ('queued', 'running')
                        ORDER BY created_at LIMIT 1
                        """)
                .bind("k", kind.code()).bind("s", subjectId).map(JOB).findOne());
    }

    /** Recent jobs for a subject, newest first. */
    public List<Job> listBySubject(String subjectId, int limit) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM jobs WHERE subject_id = :s ORDER BY created_at DESC LIMIT :n")
                .bind("s", subjectId).bind("n", limit).map(JOB).list());
    }

    /**
     * Atomically takes the next queued job (highest priority, then oldest), marks it running and counts
     * the attempt.
     */
    public Optional<Job> claimNext() {
        return db.inTransaction(h -> {
            Optional<Job> next = h.createQuery(
                            "SELECT * FROM jobs WHERE status = 'queued' ORDER BY priority DESC, created_at, id LIMIT 1")
                    .map(JOB).findOne();
            if (next.isEmpty()) {
                return Optional.<Job>empty();
            }
            long now = clock.millis();
            h.createUpdate("UPDATE jobs SET status = 'running', attempts = attempts + 1, updated_at = :now WHERE id = :id")
                    .bind("id", next.get().id()).bind("now", now).execute();
            return find(next.get().id());
        });
    }

    /** Updates progress (0–1) and the progress line. */
    public boolean updateProgress(String id, double progress, String message) {
        return db.withHandle(h -> h.createUpdate(
                        "UPDATE jobs SET progress = :p, message = :m, updated_at = :now WHERE id = :id")
                .bind("id", id).bind("p", Math.max(0, Math.min(1, progress))).bind("m", message)
                .bind("now", clock.millis()).execute()) > 0;
    }

    /** Sets the status and message (progress becomes 1 when done). */
    public boolean updateStatus(String id, JobStatus status, String message) {
        return db.withHandle(h -> h.createUpdate("""
                        UPDATE jobs SET status = :s, message = :m,
                          progress = CASE WHEN :s = 'done' THEN 1 ELSE progress END, updated_at = :now
                        WHERE id = :id
                        """)
                .bind("id", id).bind("s", status.code()).bind("m", message).bind("now", clock.millis())
                .execute()) > 0;
    }

    /** Puts jobs left running by an interrupted run back in the queue; returns how many. */
    public int requeueRunning() {
        return db.withHandle(h -> h.createUpdate("UPDATE jobs SET status = 'queued', updated_at = :now WHERE status = 'running'")
                .bind("now", clock.millis()).execute());
    }

    /** Deletes finished, failed and cancelled jobs last updated before {@code before}. */
    public int deleteFinishedBefore(long before) {
        return db.withHandle(h -> h.createUpdate(
                        "DELETE FROM jobs WHERE status IN ('done', 'failed', 'cancelled') AND updated_at < :t")
                .bind("t", before).execute());
    }
}
