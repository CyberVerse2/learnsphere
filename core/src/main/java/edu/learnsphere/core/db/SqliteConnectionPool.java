package edu.learnsphere.core.db;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Deque;
import org.jdbi.v3.core.ConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Hands out SQLite connections configured with foreign keys, WAL, a busy timeout and immediate
 * transactions, keeping a few idle ones for reuse so callers do not pay for opening a connection and
 * applying pragmas on every query.
 */
final class SqliteConnectionPool implements ConnectionFactory, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(SqliteConnectionPool.class);
    static final int BUSY_TIMEOUT_MS = 5000;

    private final SQLiteDataSource source;
    private final int maxIdle;
    private final Deque<Connection> idle = new ArrayDeque<>();
    private boolean closed;

    SqliteConnectionPool(String url, int maxIdle) {
        SQLiteConfig config = new SQLiteConfig();
        config.enforceForeignKeys(true);
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        config.setBusyTimeout(BUSY_TIMEOUT_MS);
        // Writers take the lock when the transaction begins, so two transactions never deadlock upgrading.
        config.setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE);
        this.source = new SQLiteDataSource(config);
        this.source.setUrl(url);
        this.maxIdle = maxIdle;
    }

    @Override
    public Connection openConnection() throws SQLException {
        synchronized (this) {
            if (closed) {
                throw new SQLException("Database is closed");
            }
            Connection pooled = idle.pollFirst();
            if (pooled != null) {
                return pooled;
            }
        }
        return source.getConnection();
    }

    @Override
    public void closeConnection(Connection connection) throws SQLException {
        if (connection.isClosed()) {
            return;
        }
        try {
            if (!connection.getAutoCommit()) {
                connection.rollback();
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            LOG.debug("Discarding connection that could not be reset", e);
            connection.close();
            return;
        }
        synchronized (this) {
            if (!closed && idle.size() < maxIdle) {
                idle.addFirst(connection);
                return;
            }
        }
        connection.close();
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Connection connection : idle) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.warn("Could not close connection", e);
            }
        }
        idle.clear();
    }
}
