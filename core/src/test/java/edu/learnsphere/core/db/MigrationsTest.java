package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.error.LearnSphereException;
import edu.learnsphere.core.model.Course;
import edu.learnsphere.core.model.Document;
import edu.learnsphere.core.model.DocumentPage;
import edu.learnsphere.core.model.ItemStatus;
import edu.learnsphere.core.model.ItemType;
import edu.learnsphere.core.model.LearningItem;
import edu.learnsphere.core.model.PageSource;
import edu.learnsphere.core.model.SchemaVersion;
import edu.learnsphere.core.testing.MutableClock;
import edu.learnsphere.core.testing.TestDb;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MigrationsTest {

    private static final List<String> TABLES = List.of("schema_version", "settings", "courses", "course_versions",
            "course_version_items", "enrolments", "modules", "documents", "document_pages", "chunks", "concepts",
            "learning_items", "item_concepts", "review_states", "study_sessions", "attempts", "chat_threads",
            "chat_messages", "jobs", "flags", "class_insights", "audit_log");

    @TempDir
    Path dir;

    @Test
    void appliesEveryScriptOnAFreshDatabase() {
        try (Database db = Database.openWithoutMigrations(dir.resolve("fresh.db"))) {
            Migrations migrations = new Migrations(db, MutableClock.fixed());
            assertThat(migrations.currentVersion()).isZero();

            List<SchemaVersion> applied = migrations.migrate();

            assertThat(applied).extracting(SchemaVersion::version)
                    .containsExactlyElementsOf(migrations.scripts().stream().map(Migrations.Script::version).toList());
            assertThat(applied.getFirst().name()).isEqualTo("V1__initial.sql");
            assertThat(migrations.currentVersion()).isEqualTo(applied.getLast().version());
            List<String> tables = db.withHandle(h -> h.createQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").mapTo(String.class).list());
            assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
        }
    }

    @Test
    void isIdempotent() {
        int scripts;
        try (Database db = Database.openWithoutMigrations(dir.resolve("again.db"))) {
            Migrations migrations = new Migrations(db, MutableClock.fixed());
            scripts = migrations.migrate().size();
            assertThat(migrations.migrate()).isEmpty();
            assertThat(migrations.applied()).hasSize(scripts);
        }
        try (Database reopened = Database.open(dir.resolve("again.db"), MutableClock.fixed())) {
            assertThat(new Migrations(reopened, MutableClock.fixed()).applied()).hasSize(scripts);
        }
    }

    @Test
    void createsTheDocumentedIndexes() {
        try (Database db = Database.open(dir.resolve("idx.db"), MutableClock.fixed())) {
            List<String> indexes = db.withHandle(h -> h.createQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND name LIKE 'idx_%'").mapTo(String.class).list());
            assertThat(indexes).contains("idx_modules_course_position", "idx_documents_course", "idx_documents_status",
                    "idx_chunks_document", "idx_chunks_course", "idx_items_course_status", "idx_items_module",
                    "idx_items_source_chunk", "idx_review_states_due", "idx_attempts_item", "idx_attempts_created",
                    "idx_chat_messages_thread", "idx_jobs_status_priority");
        }
    }

    @Test
    void configuresConnections() {
        try (Database db = Database.open(dir.resolve("pragmas.db"), MutableClock.fixed())) {
            assertThat(db.<Integer, RuntimeException>withHandle(h -> h.createQuery("PRAGMA foreign_keys").mapTo(Integer.class).one())).isEqualTo(1);
            assertThat(db.<String, RuntimeException>withHandle(h -> h.createQuery("PRAGMA journal_mode").mapTo(String.class).one())).isEqualToIgnoringCase("wal");
            assertThat(db.<Integer, RuntimeException>withHandle(h -> h.createQuery("PRAGMA busy_timeout").mapTo(Integer.class).one())).isEqualTo(5000);
        }
    }

    @Test
    void rejectsUnknownEnumCodes() {
        try (Database db = Database.open(dir.resolve("check.db"), MutableClock.fixed())) {
            assertThatThrownBy(() -> db.useHandle(h -> h.execute("""
                    INSERT INTO courses (id, title, origin, visibility, status, cover_hue, package_id, created_at, updated_at)
                    VALUES ('c', 'T', 'local', 'private', 'nonsense', 10, 'p', 0, 0)""")))
                    .satisfies(e -> assertThat(Database.isConstraintViolation(e)).isTrue());
        }
    }

    @Test
    void splitsStatementsOutsideCommentsAndQuotes() {
        String sql = """
                -- a comment; with a semicolon
                CREATE TABLE a (x TEXT DEFAULT 'semi;colon');
                /* block; comment */
                INSERT INTO a VALUES ('it''s; fine');
                CREATE TRIGGER t AFTER INSERT ON a BEGIN
                  UPDATE a SET x = 'y';
                  DELETE FROM a WHERE x = 'z';
                END;
                """;
        List<String> statements = Migrations.splitStatements(sql);
        assertThat(statements).hasSize(3);
        assertThat(statements.get(0)).startsWith("CREATE TABLE a").contains("'semi;colon'");
        assertThat(statements.get(1)).contains("'it''s; fine'");
        assertThat(statements.get(2)).startsWith("CREATE TRIGGER").endsWith("END");
    }

    @Test
    void rebuildsATableWithoutDeletingRowsThatReferenceIt() {
        try (TestDb t = TestDb.open(dir)) {
            Course course = t.course("Biology");
            Document document = t.document(course, null, "Cells");
            t.repos.documents().upsertPage(new DocumentPage(document.id(), 1, "A cell is…", PageSource.TEXT_LAYER, false,
                    null, false, t.now(), t.now()));
            t.chunk(document, 0, "A cell is the smallest unit of life.");
            LearningItem item = t.item(course, null, ItemType.FLASHCARD, ItemStatus.APPROVED, "What is a cell?");

            List<SchemaVersion> applied = new Migrations(t.db, t.clock, getClass().getClassLoader(), "db/test-rebuild").migrate();

            assertThat(applied).extracting(SchemaVersion::version).containsExactly(900);
            assertThat(t.repos.documents().find(document.id())).isPresent();
            assertThat(t.repos.documents().listPages(document.id())).hasSize(1);
            assertThat(t.repos.chunks().countByDocument(document.id())).isEqualTo(1);
            assertThat(t.repos.items().find(item.id())).isPresent();
            assertThat(count(t.db, "SELECT matches FROM escape_check")).isEqualTo(1);
            assertThat(t.db.<Integer, RuntimeException>withHandle(h -> h.createQuery("PRAGMA foreign_keys")
                    .mapTo(Integer.class).one())).as("foreign keys are back on").isEqualTo(1);

            t.repos.documents().delete(document.id());
            assertThat(t.repos.chunks().countByDocument(document.id())).as("cascades still work").isZero();
        }
    }

    @Test
    void rollsBackAScriptThatLeavesBrokenReferences() {
        try (TestDb t = TestDb.open(dir)) {
            t.course("Biology");
            Migrations migrations = new Migrations(t.db, t.clock, getClass().getClassLoader(), "db/test-dangling");
            int before = migrations.currentVersion();

            assertThatThrownBy(migrations::migrate).isInstanceOf(LearnSphereException.class)
                    .hasMessageContaining("V900__orphan_chunk.sql");

            assertThat(migrations.currentVersion()).isEqualTo(before);
            assertThat(count(t.db, "SELECT COUNT(*) FROM chunks")).isZero();
            assertThat(count(t.db, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'should_not_exist'")).isZero();
            assertThat(count(t.db, "PRAGMA foreign_keys")).isEqualTo(1);
        }
    }

    private static int count(Database db, String sql) {
        return db.withHandle(h -> h.createQuery(sql).mapTo(Integer.class).one());
    }

    @Test
    void findsScriptsInOrder() {
        try (Database db = Database.openWithoutMigrations(dir.resolve("scripts.db"))) {
            List<Migrations.Script> scripts = new Migrations(db, MutableClock.fixed()).scripts();
            assertThat(scripts).isNotEmpty();
            assertThat(scripts).extracting(Migrations.Script::version).isSorted();
        }
    }
}
