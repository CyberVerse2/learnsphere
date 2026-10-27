package edu.learnsphere.core.db;

import edu.learnsphere.core.error.NotFoundException;
import edu.learnsphere.core.error.UserMessage;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.CourseOrigin;
import edu.learnsphere.core.model.CourseStatus;
import edu.learnsphere.core.model.CourseVersion;
import edu.learnsphere.core.model.Enrolment;
import edu.learnsphere.core.model.EnrolmentStatus;
import edu.learnsphere.core.model.Visibility;
import edu.learnsphere.core.util.Ids;
import java.time.Clock;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.PreparedBatch;

/** Courses with their published versions and the learner's enrolments. */
public final class CourseRepository {

    static final RowMapper<Course> COURSE = (rs, ctx) -> new Course(
            rs.getString("id"),
            rs.getString("title"),
            rs.getString("subtitle"),
            rs.getString("description"),
            rs.getString("author_name"),
            CourseOrigin.fromCode(rs.getString("origin")),
            Visibility.fromCode(rs.getString("visibility")),
            CourseStatus.fromCode(rs.getString("status")),
            rs.getInt("cover_hue"),
            rs.getInt("version_number"),
            Rows.nullableLong(rs, "published_at"),
            rs.getString("package_id"),
            Rows.nullableLong(rs, "last_opened_at"),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    static final RowMapper<CourseVersion> VERSION = (rs, ctx) -> new CourseVersion(
            rs.getString("id"),
            rs.getString("course_id"),
            rs.getInt("number"),
            rs.getString("notes"),
            rs.getLong("published_at"),
            rs.getLong("created_at"));

    static final RowMapper<Enrolment> ENROLMENT = (rs, ctx) -> new Enrolment(
            rs.getString("id"),
            rs.getString("course_id"),
            rs.getLong("enrolled_at"),
            EnrolmentStatus.fromCode(rs.getString("status")),
            rs.getLong("created_at"),
            rs.getLong("updated_at"));

    /**
     * Counts that course cards and headers need, computed for many courses in one query.
     *
     * @param moduleCount         modules
     * @param documentCount       documents
     * @param documentsProcessing documents still in the import pipeline
     * @param documentsNeedingAttention documents waiting for page review or failed
     * @param approvedItems       approved items (what learners study)
     * @param draftItems          items waiting for review
     * @param startedItems        approved items reviewed at least once
     * @param dueItems            approved items reviewed before and due at or before the given time
     * @param openFlags           unresolved flags on the course's items
     */
    public record CourseCounts(int moduleCount, int documentCount, int documentsProcessing, int documentsNeedingAttention,
                               int approvedItems, int draftItems, int startedItems, int dueItems, int openFlags) {

        /** Counts for a course with nothing in it. */
        public static final CourseCounts EMPTY = new CourseCounts(0, 0, 0, 0, 0, 0, 0, 0, 0);

        /** Approved items never reviewed. */
        public int newItems() {
            return approvedItems - startedItems;
        }
    }

    private static final String COUNTS_SQL = """
            SELECT c.id AS course_id,
              (SELECT COUNT(*) FROM modules m WHERE m.course_id = c.id) AS module_count,
              (SELECT COUNT(*) FROM documents d WHERE d.course_id = c.id) AS document_count,
              (SELECT COUNT(*) FROM documents d WHERE d.course_id = c.id
                 AND d.status NOT IN ('ready', 'failed', 'needs_review')) AS documents_processing,
              (SELECT COUNT(*) FROM documents d WHERE d.course_id = c.id
                 AND d.status IN ('failed', 'needs_review')) AS documents_attention,
              (SELECT COUNT(*) FROM learning_items i WHERE i.course_id = c.id AND i.status = 'approved') AS approved_items,
              (SELECT COUNT(*) FROM learning_items i WHERE i.course_id = c.id AND i.status = 'draft') AS draft_items,
              (SELECT COUNT(*) FROM learning_items i JOIN review_states r ON r.item_id = i.id
                 WHERE i.course_id = c.id AND i.status = 'approved' AND r.state <> 'new') AS started_items,
              (SELECT COUNT(*) FROM learning_items i JOIN review_states r ON r.item_id = i.id
                 WHERE i.course_id = c.id AND i.status = 'approved' AND r.state <> 'new' AND r.due_at <= :now) AS due_items,
              (SELECT COUNT(*) FROM flags f JOIN learning_items i ON i.id = f.item_id
                 WHERE i.course_id = c.id AND f.resolved_at IS NULL) AS open_flags
            FROM courses c
            """;

    private final Database db;
    private final Clock clock;

    public CourseRepository(Database db, Clock clock) {
        this.db = Objects.requireNonNull(db, "db");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ---- courses ------------------------------------------------------------------------------

    /** Inserts {@code course} as given. */
    public void insert(Course course) {
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO courses (id, title, subtitle, description, author_name, origin, visibility, status,
                          cover_hue, version_number, published_at, package_id, last_opened_at, created_at, updated_at)
                        VALUES (:id, :title, :subtitle, :description, :authorName, :origin, :visibility, :status,
                          :coverHue, :versionNumber, :publishedAt, :packageId, :lastOpenedAt, :createdAt, :updatedAt)
                        """)
                .bind("id", course.id())
                .bind("title", course.title())
                .bind("subtitle", course.subtitle())
                .bind("description", course.description())
                .bind("authorName", course.authorName())
                .bind("origin", course.origin().code())
                .bind("visibility", course.visibility().code())
                .bind("status", course.status().code())
                .bind("coverHue", course.coverHue())
                .bind("versionNumber", course.versionNumber())
                .bind("publishedAt", course.publishedAt())
                .bind("packageId", course.packageId())
                .bind("lastOpenedAt", course.lastOpenedAt())
                .bind("createdAt", course.createdAt())
                .bind("updatedAt", course.updatedAt())
                .execute());
    }

    /** Writes every mutable column of {@code course} and stamps {@code updated_at}. */
    public boolean update(Course course) {
        return db.withHandle(h -> h.createUpdate("""
                        UPDATE courses SET title = :title, subtitle = :subtitle, description = :description,
                          author_name = :authorName, origin = :origin, visibility = :visibility, status = :status,
                          cover_hue = :coverHue, version_number = :versionNumber, published_at = :publishedAt,
                          package_id = :packageId, last_opened_at = :lastOpenedAt, updated_at = :now
                        WHERE id = :id
                        """)
                .bind("id", course.id())
                .bind("title", course.title())
                .bind("subtitle", course.subtitle())
                .bind("description", course.description())
                .bind("authorName", course.authorName())
                .bind("origin", course.origin().code())
                .bind("visibility", course.visibility().code())
                .bind("status", course.status().code())
                .bind("coverHue", course.coverHue())
                .bind("versionNumber", course.versionNumber())
                .bind("publishedAt", course.publishedAt())
                .bind("packageId", course.packageId())
                .bind("lastOpenedAt", course.lastOpenedAt())
                .bind("now", clock.millis())
                .execute()) > 0;
    }

    public Optional<Course> find(String id) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM courses WHERE id = :id").bind("id", id).map(COURSE).findOne());
    }

    /** @throws NotFoundException when there is no such course */
    public Course get(String id) {
        return find(id).orElseThrow(() -> NotFoundException.of(UserMessage.COURSE_NOT_FOUND, "course", id));
    }

    public boolean exists(String id) {
        return db.withHandle(h -> h.createQuery("SELECT COUNT(*) FROM courses WHERE id = :id").bind("id", id)
                .mapTo(Integer.class).one()) > 0;
    }

    /** Every course, most recently opened (then updated) first. */
    public List<Course> list() {
        return db.withHandle(h -> h.createQuery(
                        "SELECT * FROM courses ORDER BY COALESCE(last_opened_at, 0) DESC, updated_at DESC")
                .map(COURSE).list());
    }

    /** Courses created here or imported, most recently opened first. */
    public List<Course> listByOrigin(CourseOrigin origin) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT * FROM courses WHERE origin = :origin
                        ORDER BY COALESCE(last_opened_at, 0) DESC, updated_at DESC
                        """)
                .bind("origin", origin.code()).map(COURSE).list());
    }

    /** Courses with an active enrolment, most recently opened first. */
    public List<Course> listEnrolled() {
        return db.withHandle(h -> h.createQuery("""
                        SELECT c.* FROM courses c JOIN enrolments e ON e.course_id = c.id
                        WHERE e.status = 'active'
                        ORDER BY COALESCE(c.last_opened_at, 0) DESC, c.updated_at DESC
                        """)
                .map(COURSE).list());
    }

    /** Courses with active enrolments that were opened most recently, for the sidebar and "Continue". */
    public List<Course> listRecentlyOpened(int limit) {
        return db.withHandle(h -> h.createQuery("""
                        SELECT c.* FROM courses c JOIN enrolments e ON e.course_id = c.id
                        WHERE e.status = 'active' AND c.status <> 'archived'
                        ORDER BY COALESCE(c.last_opened_at, e.enrolled_at) DESC LIMIT :limit
                        """)
                .bind("limit", limit).map(COURSE).list());
    }

    /** The course with this package id (used to recognise an update when importing a package). */
    public Optional<Course> findByPackageId(String packageId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM courses WHERE package_id = :p ORDER BY created_at LIMIT 1")
                .bind("p", packageId).map(COURSE).findOne());
    }

    public boolean updateDetails(String id, String title, String subtitle, String description, String authorName) {
        return db.withHandle(h -> h.createUpdate("""
                        UPDATE courses SET title = :title, subtitle = :subtitle, description = :description,
                          author_name = :authorName, updated_at = :now WHERE id = :id
                        """)
                .bind("id", id).bind("title", title).bind("subtitle", subtitle).bind("description", description)
                .bind("authorName", authorName).bind("now", clock.millis())
                .execute()) > 0;
    }

    public boolean setCoverHue(String id, int hue) {
        return set(id, "cover_hue", hue);
    }

    public boolean setVisibility(String id, Visibility visibility) {
        return set(id, "visibility", visibility.code());
    }

    public boolean setStatus(String id, CourseStatus status) {
        return set(id, "status", status.code());
    }

    /** Records a new published version number and time and sets the status to published. */
    public boolean markPublished(String id, int versionNumber, long publishedAt) {
        return db.withHandle(h -> h.createUpdate("""
                        UPDATE courses SET version_number = :v, published_at = :p, status = 'published', updated_at = :now
                        WHERE id = :id
                        """)
                .bind("id", id).bind("v", versionNumber).bind("p", publishedAt).bind("now", clock.millis())
                .execute()) > 0;
    }

    /** Records when the learner opened the course (does not change {@code updated_at}). */
    public boolean markOpened(String id, long openedAt) {
        return db.withHandle(h -> h.createUpdate("UPDATE courses SET last_opened_at = :t WHERE id = :id")
                .bind("id", id).bind("t", openedAt).execute()) > 0;
    }

    /** Deletes the course and, by cascade, everything in it. */
    public boolean delete(String id) {
        return db.withHandle(h -> h.createUpdate("DELETE FROM courses WHERE id = :id").bind("id", id).execute()) > 0;
    }

    private boolean set(String id, String column, Object value) {
        return db.withHandle(h -> h.createUpdate("UPDATE courses SET " + column + " = :value, updated_at = :now WHERE id = :id")
                .bind("id", id).bindByType("value", value, value.getClass()).bind("now", clock.millis())
                .execute()) > 0;
    }

    // ---- counts -------------------------------------------------------------------------------

    /** {@link CourseCounts} for every course, keyed by course id; due counts use {@code now}. */
    public Map<String, CourseCounts> counts(long now) {
        return db.withHandle(h -> {
            Map<String, CourseCounts> result = new HashMap<>();
            h.createQuery(COUNTS_SQL).bind("now", now)
                    .map((rs, ctx) -> Map.entry(rs.getString("course_id"), new CourseCounts(
                            rs.getInt("module_count"), rs.getInt("document_count"), rs.getInt("documents_processing"),
                            rs.getInt("documents_attention"), rs.getInt("approved_items"), rs.getInt("draft_items"),
                            rs.getInt("started_items"), rs.getInt("due_items"), rs.getInt("open_flags"))))
                    .forEach(e -> result.put(e.getKey(), e.getValue()));
            return result;
        });
    }

    /** {@link CourseCounts} for one course ({@link CourseCounts#EMPTY} when it does not exist). */
    public CourseCounts counts(String courseId, long now) {
        return db.withHandle(h -> h.createQuery(COUNTS_SQL + " WHERE c.id = :id").bind("now", now).bind("id", courseId)
                .map((rs, ctx) -> new CourseCounts(
                        rs.getInt("module_count"), rs.getInt("document_count"), rs.getInt("documents_processing"),
                        rs.getInt("documents_attention"), rs.getInt("approved_items"), rs.getInt("draft_items"),
                        rs.getInt("started_items"), rs.getInt("due_items"), rs.getInt("open_flags")))
                .findOne().orElse(CourseCounts.EMPTY));
    }

    // ---- versions -----------------------------------------------------------------------------

    public void insertVersion(CourseVersion version) {
        db.useHandle(h -> h.createUpdate("""
                        INSERT INTO course_versions (id, course_id, number, notes, published_at, created_at)
                        VALUES (:id, :courseId, :number, :notes, :publishedAt, :createdAt)
                        """)
                .bind("id", version.id()).bind("courseId", version.courseId()).bind("number", version.number())
                .bind("notes", version.notes()).bind("publishedAt", version.publishedAt())
                .bind("createdAt", version.createdAt())
                .execute());
    }

    /** Records which items a version contains. */
    public void addVersionItems(String versionId, Collection<String> itemIds) {
        if (itemIds.isEmpty()) {
            return;
        }
        long now = clock.millis();
        db.useHandle(h -> {
            PreparedBatch batch = h.prepareBatch(
                    "INSERT OR IGNORE INTO course_version_items (version_id, item_id, created_at) VALUES (:v, :i, :t)");
            for (String itemId : itemIds) {
                batch.bind("v", versionId).bind("i", itemId).bind("t", now).add();
            }
            batch.execute();
        });
    }

    /**
     * Publishes a version in one transaction: inserts the version and its items and marks the course
     * published with the version's number.
     */
    public CourseVersion publishVersion(String courseId, String notes, Collection<String> itemIds, long publishedAt) {
        return db.inTransaction(h -> {
            int number = nextVersionNumber(courseId);
            CourseVersion version = new CourseVersion(Ids.newId(), courseId, number, notes, publishedAt, publishedAt);
            insertVersion(version);
            addVersionItems(version.id(), itemIds);
            markPublished(courseId, number, publishedAt);
            return version;
        });
    }

    /** Versions of a course, newest first. */
    public List<CourseVersion> listVersions(String courseId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM course_versions WHERE course_id = :c ORDER BY number DESC")
                .bind("c", courseId).map(VERSION).list());
    }

    public Optional<CourseVersion> latestVersion(String courseId) {
        return db.withHandle(h -> h.createQuery(
                        "SELECT * FROM course_versions WHERE course_id = :c ORDER BY number DESC LIMIT 1")
                .bind("c", courseId).map(VERSION).findOne());
    }

    /** Ids of the items a version contains. */
    public List<String> versionItemIds(String versionId) {
        return db.withHandle(h -> h.createQuery(
                        "SELECT item_id FROM course_version_items WHERE version_id = :v ORDER BY item_id")
                .bind("v", versionId).mapTo(String.class).list());
    }

    /** The number the next published version will get (1 for the first). */
    public int nextVersionNumber(String courseId) {
        return db.withHandle(h -> h.createQuery(
                        "SELECT COALESCE(MAX(number), 0) + 1 FROM course_versions WHERE course_id = :c")
                .bind("c", courseId).mapTo(Integer.class).one());
    }

    // ---- enrolments ---------------------------------------------------------------------------

    /** Enrols the learner (or re-activates a previous enrolment) and returns the enrolment. */
    public Enrolment enrol(String courseId, long enrolledAt) {
        return db.inTransaction(h -> {
            h.createUpdate("""
                            INSERT INTO enrolments (id, course_id, enrolled_at, status, created_at, updated_at)
                            VALUES (:id, :c, :t, 'active', :t, :t)
                            ON CONFLICT (course_id) DO UPDATE SET status = 'active', updated_at = :t
                            """)
                    .bind("id", Ids.newId()).bind("c", courseId).bind("t", enrolledAt)
                    .execute();
            return findEnrolment(courseId).orElseThrow();
        });
    }

    public Optional<Enrolment> findEnrolment(String courseId) {
        return db.withHandle(h -> h.createQuery("SELECT * FROM enrolments WHERE course_id = :c")
                .bind("c", courseId).map(ENROLMENT).findOne());
    }

    /** True when the learner is actively enrolled. */
    public boolean isEnrolled(String courseId) {
        return findEnrolment(courseId).map(Enrolment::isActive).orElse(false);
    }

    /** Marks the enrolment as left; progress is kept. */
    public boolean leave(String courseId) {
        return db.withHandle(h -> h.createUpdate(
                        "UPDATE enrolments SET status = 'left', updated_at = :now WHERE course_id = :c")
                .bind("c", courseId).bind("now", clock.millis()).execute()) > 0;
    }

    public List<Enrolment> listActiveEnrolments() {
        return db.withHandle(h -> h.createQuery("SELECT * FROM enrolments WHERE status = 'active' ORDER BY enrolled_at DESC")
                .map(ENROLMENT).list());
    }
}
