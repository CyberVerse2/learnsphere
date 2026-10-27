package edu.learnsphere.core.db;

import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentError;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.DocumentStatus;
import edu.learnsphere.core.model.PageSource;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.PreparedBatch;

/** Imported PDFs, their pipeline status and the text of their pages. */
public final class DocumentRepository {

    static final RowMapper<Document> DOCUMENT = (rs, ctx) -> new Document(
            rs.getString("id"),
            rs.getString("course_id"),
            rs.getString("module_id"),
            rs.getString("title"),
            rs.getString("file_name"),
            rs.getString("file_sha256"),
            rs.getLong("byte_size"),
            rs.getInt("page_count"),
            DocumentStatus.fromCode(rs.getString("status")),
            rs.getDouble("progress"),
            rs.getString("status_detail"),
            rs.getString("error_code") == null ? null : DocumentError.fromCode(rs.getString("error_code")),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    static final RowMapper<DocumentPage> PAGE = (rs, ctx) -> new DocumentPage(
            rs.getString("document_id"),
            rs.getInt("page_number"),
            rs.getString("text"),
            PageSource.fromCode(rs.getString("source")),
            Rows.bool(rs, "reviewed"),
            rs.getString("figure_notes"),
            Rows.bool(rs, "has_images"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    private static final String UPSERT_PAGE = """
            INSERT INTO document_pages (document_id, page_number, text, source, reviewed, figure_notes, has_images,
              created_at, updated_at)
            VALUES (:documentId, :pageNumber, :text, :source, :reviewed, :figureNotes, :hasImages, :createdAt, :updatedAt)
            ON CONFLICT (document_id, page_number) DO UPDATE SET text = excluded.text, source = excluded.source,
              reviewed = excluded.reviewed, figure_notes = excluded.figure_notes, has_images = excluded.has_images,
              updated_at = excluded.updated_at
            """;

    private final Database db;
    private final Clock clock;

    public DocumentRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- documents ----------------------------------------------------------------------------

    public void insert(Document d) {
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO documents (id, course_id, module_id, title, file_name, file_sha256, byte_size,
                          page_count, status, progress, status_detail, error_code, created_at, updated_at)
                        VALUES (:id, :courseId, :moduleId, :title, :fileName, :sha, :byteSize, :pageCount, :status,
                          :progress, :detail, :error, :createdAt, :updatedAt)
                        """)
                .bind("id", d.id()).bind("courseId", d.courseId()).bind("moduleId", d.moduleId())
                .bind("title", d.title()).bind("fileName", d.fileName()).bind("sha", d.fileSha256())
                .bind("byteSize", d.byteSize()).bind("pageCount", d.pageCount()).bind("status", d.status().code())
                .bind("progress", d.progress()).bind("detail", d.statusDetail())
                .bind("error", d.errorCode() == null ? null : d.errorCode().code())
                .bind("createdAt", d.createdAt()).bind("updatedAt", d.updatedAt())
                .execute());
    }

    public Optional<Document> find(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM documents WHERE id = :id").bind("id", id).map(DOCUMENT).findOne());
    }

    /** @throws NotFoundException when there is no such document */
    public Document get(String id) {
        return find(id).orElseThrow(() -> NotFoundException.of(UserMessage.DOCUMENT_NOT_FOUND, "document", id));
    }

    /** Documents of a course, oldest first. */
    public List<Document> listByCourse(String courseId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM documents WHERE course_id = :c ORDER BY created_at, title")
                .bind("c", courseId).map(DOCUMENT).list());
    }

    /** Documents of a module, oldest first. */
    public List<Document> listByModule(String moduleId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM documents WHERE module_id = :m ORDER BY created_at, title")
                .bind("m", moduleId).map(DOCUMENT).list());
    }

    /** Documents in any of {@code statuses}, oldest first (for resuming the pipeline). */
    public List<Document> listByStatus(Set<DocumentStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        return db.withHandle(h -> h.createQuery("SELECT * FROM documents WHERE status IN (<statuses>) ORDER BY created_at")
                .bindList("statuses", statuses.stream().map(DocumentStatus::code).toList())
                .map(DOCUMENT).list());
    }

    /** The document in this course with this file hash, if the same PDF was imported before. */
    public Optional<Document> findByCourseAndSha(String courseId, String sha256) {
        return db.withHandle(h -> h.createQuery(
                        "SELECT * FROM documents WHERE course_id = :c AND file_sha256 = :s ORDER BY created_at LIMIT 1")
                .bind("c", courseId).bind("s", sha256).map(DOCUMENT).findOne());
    }

    /** How many documents (in any course) use the file with this hash; 0 means the file can be deleted. */
    public int countBySha(String sha256) {
        return db.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM documents WHERE file_sha256 = :s")
                .bind("s", sha256).mapTo(Integer.class).one());
    }

    /** Moves the pipeline to {@code status} with stage progress and detail; clears any error. */
    public boolean updateStatus(String id, DocumentStatus status, double progress, String detail) {
        return db.withHandle(h -> h.createUpdate("""
                        UPDATE documents SET status = :s, progress = :p, status_detail = :d, error_code = NULL,
                          updated_at = :now WHERE id = :id
                        """)
                .bind("id", id).bind("s", status.code()).bind("p", clamp(progress)).bind("d", detail)
                .bind("now", clock.millis()).execute()) > 0;
    }

    /** Updates only the progress of the current stage. */
    public boolean updateProgress(String id, double progress, String detail) {
        return db.withHandle(h -> h.createUpdate(
                        "UPDATE documents SET progress = :p, status_detail = :d, updated_at = :now WHERE id = :id")
                .bind("id", id).bind("p", clamp(progress)).bind("d", detail).bind("now", clock.millis())
                .execute()) > 0;
    }

    /** Marks the document failed with {@code error}. */
    public boolean markFailed(String id, DocumentError error, String detail) {
        return db.withHandle(h -> h.createUpdate("""
                        UPDATE documents SET status = 'failed', error_code = :e, status_detail = :d, updated_at = :now
                        WHERE id = :id
                        """)
                .bind("id", id).bind("e", error.code()).bind("d", detail).bind("now", clock.millis())
                .execute()) > 0;
    }

    /** Marks the document ready: progress 1, no detail, no error. */
    public boolean markReady(String id) {
        return updateStatus(id, DocumentStatus.READY, 1, null);
    }

    public boolean setPageCount(String id, int pageCount) {
        return db.withHandle(h -> h.createUpdate("UPDATE documents SET page_count = :n, updated_at = :now WHERE id = :id")
                .bind("id", id).bind("n", pageCount).bind("now", clock.millis()).execute()) > 0;
    }

    public boolean rename(String id, String title) {
        return db.withHandle(h -> h.createUpdate("UPDATE documents SET title = :t, updated_at = :now WHERE id = :id")
                .bind("id", id).bind("t", title).bind("now", clock.millis()).execute()) > 0;
    }

    /** Moves the document (and its passages) to another module, or to none with {@code null}. */
    public boolean setModule(String id, String moduleId) {
        return db.inTransaction(h -> {
            long now = clock.millis();
            int changed = h.createUpdate("UPDATE documents SET module_id = :m, updated_at = :now WHERE id = :id")
                    .bind("id", id).bind("m", moduleId).bind("now", now).execute();
            h.createUpdate("UPDATE chunks SET module_id = :m, updated_at = :now WHERE document_id = :id")
                    .bind("id", id).bind("m", moduleId).bind("now", now).execute();
            return changed > 0;
        });
    }

    /** Deletes the document with its pages and passages. */
    public boolean delete(String id) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM documents WHERE id = :id").bind("id", id).execute()) > 0;
    }

    /** Pages needing review per document of a course, keyed by document id (documents without any are absent). */
    public Map<String, Integer> pagesNeedingReviewByDocument(String courseId) {
        return db.withHandle(h -> {
            Map<String, Integer> result = new HashMap<>();
            h.createQuery("""
                            SELECT p.document_id, COUNT(*) AS n FROM document_pages p
                            JOIN documents d ON d.id = p.document_id
                            WHERE d.course_id = :c AND p.source = 'ai_read' AND p.reviewed = 0
                            GROUP BY p.document_id
                            """)
                    .bind("c", courseId)
                    .map((rs, ctx) -> Map.entry(rs.getString("document_id"), rs.getInt("n")))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    // ---- pages --------------------------------------------------------------------------------

    /** Inserts or replaces a page. */
    public void upsertPage(DocumentPage page) {
        db.useHandle(h -> bindPage(h.createUpdate(UPSERT_PAGE), page).execute());
    }

    /** Inserts or replaces many pages in one batch. */
    public void upsertPages(Collection<DocumentPage> pages) {
        if (pages.isEmpty()) {
            return;
        }
        db.useTransaction(h -> {
            PreparedBatch batch = h.prepareBatch(UPSERT_PAGE);
            for (DocumentPage page : pages) {
                bindPage(batch, page).add();
            }
            batch.execute();
        });
    }

    private static <T extends org.jdbi.v3.core.statement.SqlStatement<T>> T bindPage(T statement, DocumentPage p) {
        return statement.bind("documentId", p.documentId()).bind("pageNumber", p.pageNumber()).bind("text", p.text())
                .bind("source", p.source().code()).bind("reviewed", Rows.flag(p.reviewed()))
                .bind("figureNotes", p.figureNotes()).bind("hasImages", Rows.flag(p.hasImages()))
                .bind("createdAt", p.createdAt()).bind("updatedAt", p.updatedAt());
    }

    public Optional<DocumentPage> findPage(String documentId, int pageNumber) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM document_pages WHERE document_id = :d AND page_number = :n")
                .bind("d", documentId).bind("n", pageNumber).map(PAGE).findOne());
    }

    /** Every page of a document in page order. */
    public List<DocumentPage> listPages(String documentId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM document_pages WHERE document_id = :d ORDER BY page_number")
                .bind("d", documentId).map(PAGE).list());
    }

    /** Pages that may be used as evidence (text layer, or reviewed), in page order. */
    public List<DocumentPage> listUsablePages(String documentId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT * FROM document_pages WHERE document_id = :d AND (source = 'text_layer' OR reviewed = 1)
                        ORDER BY page_number
                        """)
                .bind("d", documentId).map(PAGE).list());
    }

    /** Image-read pages a creator has not reviewed yet, in page order. */
    public List<DocumentPage> listPagesNeedingReview(String documentId) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT * FROM document_pages WHERE document_id = :d AND source = 'ai_read' AND reviewed = 0
                        ORDER BY page_number
                        """)
                .bind("d", documentId).map(PAGE).list());
    }

    public int countPagesNeedingReview(String documentId) {
        return db.withHandle(h -> h.createQuery(
                        "SELECT COUNT(*) FROM document_pages WHERE document_id = :d AND source = 'ai_read' AND reviewed = 0")
                .bind("d", documentId).mapTo(Integer.class).one());
    }

    /** Numbers of the pages already stored for a document (to resume extraction). */
    public Set<Integer> storedPageNumbers(String documentId) {
        return Set.copyOf(db.withHandle(h -> h.createQuery("SELECT page_number FROM document_pages WHERE document_id = :d")
                .bind("d", documentId).mapTo(Integer.class).list()));
    }

    /** Replaces a page's text, for example after a creator corrected an image-read page, and marks it reviewed. */
    public boolean updatePageText(String documentId, int pageNumber, String text, boolean reviewed) {
        return db.withHandle(h -> h.createUpdate("""
                        UPDATE document_pages SET text = :t, reviewed = :r, updated_at = :now
                        WHERE document_id = :d AND page_number = :n
                        """)
                .bind("d", documentId).bind("n", pageNumber).bind("t", text).bind("r", Rows.flag(reviewed))
                .bind("now", clock.millis()).execute()) > 0;
    }

    /** Marks pages as reviewed. Returns how many changed. */
    public int markPagesReviewed(String documentId, Collection<Integer> pageNumbers) {
        if (pageNumbers.isEmpty()) {
            return 0;
        }
        return db.withHandle(h -> h.createUpdate("""
                        UPDATE document_pages SET reviewed = 1, updated_at = :now
                        WHERE document_id = :d AND page_number IN (<pages>) AND reviewed = 0
                        """)
                .bind("d", documentId).bindList("pages", List.copyOf(pageNumbers)).bind("now", clock.millis())
                .execute());
    }

    /** Stores figure descriptions for a page (one per line), or clears them with {@code null}. */
    public boolean setFigureNotes(String documentId, int pageNumber, String notes) {
        return db.withHandle(h -> h.createUpdate("""
                        UPDATE document_pages SET figure_notes = :f, updated_at = :now
                        WHERE document_id = :d AND page_number = :n
                        """)
                .bind("d", documentId).bind("n", pageNumber).bind("f", notes).bind("now", clock.millis())
                .execute()) > 0;
    }

    /** Deletes every page of a document (before extracting it again). */
    public int deletePages(String documentId) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM document_pages WHERE document_id = :d")
                .bind("d", documentId).execute());
    }

    private static double clamp(double progress) {
        return Math.max(0, Math.min(1, progress));
    }
}
