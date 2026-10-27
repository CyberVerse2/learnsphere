package edu.learnsphere.core.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.learnsphere.core.MutableClock;
import edu.learnsphere.core.model.SchemaVersion;
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

            assertThat(applied).extracting(SchemaVersion::version).containsExactly(1);
            assertThat(applied.getFirst().name()).isEqualTo("V1__initial.sql");
            assertThat(migrations.currentVersion()).isEqualTo(1);
            List<String> tables = db.withHandle(h -> h.createQuery(
                    "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'").mapTo(String.class).list());
            assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
        }
    }

    @Test
    void isIdempotent() {
        try (Database db = Database.openWithoutMigrations(dir.resolve("again.db"))) {
            Migrations migrations = new Migrations(db, MutableClock.fixed());
            migrations.migrate();
            assertThat(migrations.migrate()).isEmpty();
            assertThat(migrations.applied()).hasSize(1);
        }
        try (Database reopened = Database.open(dir.resolve("again.db"), MutableClock.fixed())) {
            assertThat(new Migrations(reopened, MutableClock.fixed()).applied()).hasSize(1);
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
    void findsScriptsInOrder() {
        try (Database db = Database.openWithoutMigrations(dir.resolve("scripts.db"))) {
            List<Migrations.Script> scripts = new Migrations(db, MutableClock.fixed()).scripts();
            assertThat(scripts).isNotEmpty();
            assertThat(scripts).extracting(Migrations.Script::version).isSorted();
        }
    }
}
