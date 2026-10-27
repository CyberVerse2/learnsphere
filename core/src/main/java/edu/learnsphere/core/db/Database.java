package edu.learnsphere.core.db;

import edu.learnsphere.core.error.LearnSphereException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Objects;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.HandleCallback;
import org.jdbi.v3.core.HandleConsumer;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlite3.SQLitePlugin;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

/**
 * One profile's SQLite database: foreign keys on, WAL journal, {@code busy_timeout} 5000 ms, immediate
 * transactions, and a {@link Jdbi} instance over a small connection pool.
 *
 * <p>Repositories run every statement through {@link #withHandle} / {@link #useHandle}. Wrapping several
 * repository calls in {@link #inTransaction} makes them one transaction: while it runs, every repository
 * call on the same thread joins it. Nested {@code inTransaction} calls join the outer transaction.
 *
 * <p>Transactions begin {@code IMMEDIATE}, so each one holds the database's single write lock from start
 * to finish and every other writer waits (up to {@code busy_timeout}, then fails). Keep them short: read
 * and compute first, then write in one transaction. Never call an SPI implementation (AI requests, PDF
 * reading, embedding, search index writes) or wait on anything slow inside {@code inTransaction}.
 *
 * <p>Thread-safe.
 */
public final class Database implements AutoCloseable {

    /** File name of the database inside a profile folder. */
    public static final String FILE_NAME = "learnsphere.db";

    private static final int MAX_IDLE_CONNECTIONS = 4;

    private final Path file;
    private final SqliteConnectionPool pool;
    private final Jdbi jdbi;
    private final ThreadLocal<Handle> transactionHandle = new ThreadLocal<>();

    private Database(Path file) {
        this.file = file;
        this.pool = new SqliteConnectionPool("jdbc:sqlite:" + file.toAbsolutePath(), MAX_IDLE_CONNECTIONS);
        this.jdbi = Jdbi.create(pool).installPlugin(new SQLitePlugin());
    }

    /**
     * Opens (creating if needed) the database at {@code file} and applies pending migrations.
     *
     * @throws LearnSphereException when the file cannot be opened or a migration fails
     */
    public static Database open(Path file, Clock clock) {
        Database database = openWithoutMigrations(file);
        try {
            new Migrations(database, clock).migrate();
            return database;
        } catch (RuntimeException e) {
            database.close();
            throw e;
        }
    }

    /** Opens the database without touching its schema. Mostly for tests of {@link Migrations}. */
    public static Database openWithoutMigrations(Path file) {
        Objects.requireNonNull(file, "file");
        try {
            Path parent = file.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create folder for " + file, e);
        }
        Database database = new Database(file);
        database.useHandle(handle -> handle.execute("SELECT 1"));
        return database;
    }

    /** The database file. */
    public Path file() {
        return file;
    }

    /** The underlying Jdbi instance. Prefer {@link #withHandle} so calls join an active transaction. */
    public Jdbi jdbi() {
        return jdbi;
    }

    /** Runs {@code callback} with a handle, joining the current thread's transaction if there is one. */
    public <R, X extends Exception> R withHandle(HandleCallback<R, X> callback) throws X {
        Handle active = transactionHandle.get();
        if (active != null) {
            return callback.withHandle(active);
        }
        return jdbi.withHandle(callback);
    }

    /** Runs {@code consumer} with a handle, joining the current thread's transaction if there is one. */
    public <X extends Exception> void useHandle(HandleConsumer<X> consumer) throws X {
        withHandle(handle -> {
            consumer.useHandle(handle);
            return null;
        });
    }

    /**
     * Runs {@code callback} in a transaction that commits when it returns and rolls back when it throws.
     * Repository calls made on this thread inside the callback take part in the transaction.
     */
    public <R, X extends Exception> R inTransaction(HandleCallback<R, X> callback) throws X {
        Handle active = transactionHandle.get();
        if (active != null) {
            return callback.withHandle(active);
        }
        try (Handle handle = jdbi.open()) {
            transactionHandle.set(handle);
            try {
                return handle.inTransaction(callback);
            } finally {
                transactionHandle.remove();
            }
        }
    }

    /** {@link #inTransaction} without a result. */
    public <X extends Exception> void useTransaction(HandleConsumer<X> consumer) throws X {
        inTransaction(handle -> {
            consumer.useHandle(handle);
            return null;
        });
    }

    /**
     * Runs {@code work} with a pooled JDBC connection used by nobody else meanwhile, outside Jdbi (for
     * {@link Migrations}, which needs connection-level pragmas and plain statements). The connection
     * starts in auto-commit mode with foreign keys on; {@code work} must leave it that way.
     */
    <R> R withConnection(ConnectionWork<R> work) throws SQLException {
        Connection connection = pool.openConnection();
        try {
            return work.apply(connection);
        } finally {
            pool.closeConnection(connection);
        }
    }

    /** Work on a raw connection. */
    @FunctionalInterface
    interface ConnectionWork<R> {
        R apply(Connection connection) throws SQLException;
    }

    /** True while the calling thread is inside {@link #inTransaction}. */
    public boolean isInTransaction() {
        return transactionHandle.get() != null;
    }

    /**
     * Copies the write-ahead log into the main file so the database is a single consistent file, for
     * example before a backup zips the profile folder.
     */
    public void checkpoint() {
        useHandle(handle -> handle.createQuery("PRAGMA wal_checkpoint(TRUNCATE)").mapToMap().list());
    }

    /** True when {@code error} (or a cause) is a SQLite constraint violation (unique, foreign key, check). */
    public static boolean isConstraintViolation(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof SQLiteException e) {
                SQLiteErrorCode code = e.getResultCode();
                return code != null && code.name().startsWith("SQLITE_CONSTRAINT");
            }
            if (t instanceof SQLException e && e.getErrorCode() == SQLiteErrorCode.SQLITE_CONSTRAINT.code) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        pool.close();
    }
}
