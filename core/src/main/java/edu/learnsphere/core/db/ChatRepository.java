package edu.learnsphere.core.db;

import com.fasterxml.jackson.core.type.TypeReference;
import edu.learnsphere.core.model.ChatMessage;
import edu.learnsphere.core.model.ChatRole;
import edu.learnsphere.core.model.ChatThread;
import edu.learnsphere.core.model.Citation;
import edu.learnsphere.core.model.MessageStatus;
import edu.learnsphere.core.util.Json;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.Query;

/** Chat threads per course and their messages. */
public final class ChatRepository {

    private static final TypeReference<List<Citation>> CITATIONS = new TypeReference<>() { };

    static final RowMapper<ChatThread> THREAD = (rs, ctx) -> new ChatThread(
            rs.getString("id"),
            rs.getString("course_id"),
            rs.getString("title"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    static final RowMapper<ChatMessage> MESSAGE = (rs, ctx) -> new ChatMessage(
            rs.getString("id"),
            rs.getString("thread_id"),
            ChatRole.fromCode(rs.getString("role")),
            rs.getString("content"),
            MessageStatus.fromCode(rs.getString("status")),
            Json.read(rs.getString("citations_json"), CITATIONS),
            rs.getLong("created_at"));

    /** A thread with what a thread list needs: message count and the latest message. */
    public record ThreadRow(ChatThread thread, int messageCount, String lastMessage, Long lastMessageAt) {
    }

    /** A question with the answer that followed it, for "recent questions". */
    public record Exchange(ChatThread thread, ChatMessage question, ChatMessage answer) {
    }

    private final Database db;
    private final Clock clock;

    public ChatRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- threads ------------------------------------------------------------------------------

    public void insertThread(ChatThread t) {
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO chat_threads (id, course_id, title, created_at, updated_at)
                        VALUES (:id, :courseId, :title, :createdAt, :updatedAt)
                        """)
                .bind("id", t.id()).bind("courseId", t.courseId()).bind("title", t.title())
                .bind("createdAt", t.createdAt()).bind("updatedAt", t.updatedAt())
                .execute());
    }

    public Optional<ChatThread> findThread(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM chat_threads WHERE id = :id").bind("id", id).map(THREAD).findOne());
    }

    /** Threads of a course, most recently active first. */
    public List<ChatThread> listThreads(String courseId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM chat_threads WHERE course_id = :c ORDER BY updated_at DESC")
                .bind("c", courseId).map(THREAD).list());
    }

    /** Threads of a course with message counts and latest messages, most recently active first. */
    public List<ThreadRow> listThreadRows(String courseId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT t.*,
                          (SELECT COUNT(*) FROM chat_messages m WHERE m.thread_id = t.id) AS message_count,
                          (SELECT m.content FROM chat_messages m WHERE m.thread_id = t.id
                             ORDER BY m.created_at DESC, m.rowid DESC LIMIT 1) AS last_message,
                          (SELECT MAX(m.created_at) FROM chat_messages m WHERE m.thread_id = t.id) AS last_message_at
                        FROM chat_threads t WHERE t.course_id = :c ORDER BY t.updated_at DESC
                        """)
                .bind("c", courseId)
                .map((rs, ctx) -> new ThreadRow(THREAD.map(rs, ctx), rs.getInt("message_count"),
                        rs.getString("last_message"), Rows.nullableLong(rs, "last_message_at")))
                .list());
    }

    public boolean renameThread(String id, String title) {
        return db.withHandle(h -> h.createUpdate("UPDATE chat_threads SET title = :t, updated_at = :now WHERE id = :id")
                .bind("id", id).bind("t", title).bind("now", clock.millis()).execute()) > 0;
    }

    /** Moves a thread to the top of its course's list. */
    public boolean touchThread(String id, long at) {
        return db.withHandle(h -> h.createUpdate("UPDATE chat_threads SET updated_at = :t WHERE id = :id")
                .bind("id", id).bind("t", at).execute()) > 0;
    }

    /** Deletes a thread with its messages. */
    public boolean deleteThread(String id) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM chat_threads WHERE id = :id").bind("id", id).execute()) > 0;
    }

    // ---- messages -----------------------------------------------------------------------------

    /** Inserts a message and moves its thread to the top. */
    public void insertMessage(ChatMessage m) {
        db.useTransaction(h -> {
            h.createUpdate("""
                            INSERT INTO chat_messages (id, thread_id, role, content, status, citations_json, created_at)
                            VALUES (:id, :threadId, :role, :content, :status, :citations, :createdAt)
                            """)
                    .bind("id", m.id()).bind("threadId", m.threadId()).bind("role", m.role().code())
                    .bind("content", m.content()).bind("status", m.status().code())
                    .bind("citations", Json.write(m.citations())).bind("createdAt", m.createdAt())
                    .execute();
            touchThread(m.threadId(), m.createdAt());
        });
    }

    public Optional<ChatMessage> findMessage(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM chat_messages WHERE id = :id").bind("id", id).map(MESSAGE).findOne());
    }

    /** Messages of a thread in conversation order. */
    public List<ChatMessage> listMessages(String threadId) {
        return db.withHandle(h -> h.createQuery(
                        "SELECT * FROM chat_messages WHERE thread_id = :t ORDER BY created_at, rowid")
                .bind("t", threadId).map(MESSAGE).list());
    }

    /**
     * The most recent answered questions across courses (or in one course), newest first: each answer
     * with status complete, paired with the question just before it in the same thread.
     */
    public List<Exchange> recentExchanges(String courseId, int limit) {
        String sql = """
                SELECT a.id AS a_id, a.thread_id AS a_thread_id, a.role AS a_role, a.content AS a_content,
                  a.status AS a_status, a.citations_json AS a_citations_json, a.created_at AS a_created_at,
                  q.id AS q_id, q.thread_id AS q_thread_id, q.role AS q_role, q.content AS q_content,
                  q.status AS q_status, q.citations_json AS q_citations_json, q.created_at AS q_created_at,
                  t.id, t.course_id, t.title, t.created_at, t.updated_at
                FROM chat_messages a
                JOIN chat_threads t ON t.id = a.thread_id
                JOIN chat_messages q ON q.id = (
                  SELECT q2.id FROM chat_messages q2
                  WHERE q2.thread_id = a.thread_id AND q2.role = 'user'
                    AND (q2.created_at < a.created_at OR (q2.created_at = a.created_at AND q2.rowid < a.rowid))
                  ORDER BY q2.created_at DESC, q2.rowid DESC LIMIT 1)
                WHERE a.role = 'assistant' AND a.status = 'complete'
                """ + (courseId == null ? "" : " AND t.course_id = :c") + " ORDER BY a.created_at DESC LIMIT :limit";
        return db.withHandle(h -> {
            Query q = h.createQuery(sql).bind("limit", limit);
            if (courseId != null) {
                q.bind("c", courseId);
            }
            return q.map((rs, ctx) -> new Exchange(
                    THREAD.map(rs, ctx),
                    new ChatMessage(rs.getString("q_id"), rs.getString("q_thread_id"), ChatRole.fromCode(rs.getString("q_role")),
                            rs.getString("q_content"), MessageStatus.fromCode(rs.getString("q_status")),
                            Json.read(rs.getString("q_citations_json"), CITATIONS), rs.getLong("q_created_at")),
                    new ChatMessage(rs.getString("a_id"), rs.getString("a_thread_id"), ChatRole.fromCode(rs.getString("a_role")),
                            rs.getString("a_content"), MessageStatus.fromCode(rs.getString("a_status")),
                            Json.read(rs.getString("a_citations_json"), CITATIONS), rs.getLong("a_created_at"))))
                    .list();
        });
    }
}
