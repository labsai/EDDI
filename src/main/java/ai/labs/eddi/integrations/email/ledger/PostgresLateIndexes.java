/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email.ledger;

import org.jboss.logging.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Builds indexes that arrive after a table already holds rows with
 * {@code CREATE INDEX CONCURRENTLY}, which does not block writes, the way
 * {@code PostgresAuditStore} builds its late indexes. Each build holds a
 * session advisory lock, so replicas starting together do not build the same
 * index twice; an index an interrupted build left INVALID is dropped and
 * rebuilt, and a build that ends INVALID is retried rather than trusted.
 */
final class PostgresLateIndexes {

    private static final Logger LOGGER = Logger.getLogger(PostgresLateIndexes.class);

    /** Delays before the retries of a build that failed transiently. */
    static final long[] RETRY_DELAYS_MILLIS = {30_000L, 120_000L, 600_000L};

    @FunctionalInterface
    interface ConnectionSource {
        Connection get() throws SQLException;
    }

    private PostgresLateIndexes() {
    }

    /**
     * Builds every {@code {name, "table (columns)"}} entry, retrying with back-off;
     * logs and gives up after the last retry (the next restart tries again).
     */
    static void buildWithRetries(ConnectionSource connections, int lockClass, List<String[]> indexes, String what, String withoutThem) {
        for (int attempt = 0;; attempt++) {
            try {
                build(connections, lockClass, indexes);
                return;
            } catch (SQLException e) {
                if (attempt >= RETRY_DELAYS_MILLIS.length) {
                    LOGGER.warnf("Could not build the %s indexes %s (%s). %s; the next restart retries.", what,
                            indexes.stream().map(index -> index[0]).toList(), e.getMessage(), withoutThem);
                    return;
                }
                LOGGER.infof("%s index build failed (%s, SQLState %s); retrying in %d s", what, e.getMessage(), e.getSQLState(),
                        RETRY_DELAYS_MILLIS[attempt] / 1000);
                try {
                    Thread.sleep(RETRY_DELAYS_MILLIS[attempt]);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    static void build(ConnectionSource connections, int lockClass, List<String[]> indexes) throws SQLException {
        try (Connection conn = connections.get()) {
            conn.setAutoCommit(true); // CREATE INDEX CONCURRENTLY refuses to run inside a transaction
            for (String[] index : indexes) {
                String name = index[0];
                int key = name.hashCode();
                if (!tryLock(conn, lockClass, key)) {
                    throw new SQLException("another instance is building " + name, "55P03");
                }
                try (Statement stmt = conn.createStatement()) {
                    if (Boolean.FALSE.equals(isValid(conn, name))) {
                        LOGGER.infof("Rebuilding index %s, left invalid by an interrupted build", name);
                        stmt.execute("DROP INDEX CONCURRENTLY IF EXISTS " + name);
                    }
                    stmt.execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS " + name + " ON " + index[1]);
                    if (!Boolean.TRUE.equals(isValid(conn, name))) {
                        throw new SQLException("index " + name + " is not valid after its build", "55000");
                    }
                } finally {
                    unlock(conn, lockClass, key);
                }
            }
        }
    }

    /**
     * Whether a relation (table or index) of that name exists in the search path.
     */
    static boolean exists(Connection conn, String relation) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            ps.setString(1, relation);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    /**
     * Whether {@code table} (resolved through the search path) has an index named
     * {@code indexName}. Not {@code to_regclass(indexName)}: that also finds a
     * same-named index of another schema's table.
     */
    static boolean hasIndex(Connection conn, String table, String indexName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE i.indrelid = to_regclass(?) AND c.relname = ?")) {
            ps.setString(1, table);
            ps.setString(2, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /** {@code pg_index.indisvalid} of an index, or null when it does not exist. */
    static Boolean isValid(Connection conn, String indexName) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT i.indisvalid FROM pg_index i WHERE i.indexrelid = to_regclass(?)")) {
            ps.setString(1, indexName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBoolean(1) : null;
            }
        }
    }

    private static boolean tryLock(Connection conn, int lockClass, int key) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_try_advisory_lock(?, ?)")) {
            ps.setInt(1, lockClass);
            ps.setInt(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getBoolean(1);
            }
        }
    }

    private static void unlock(Connection conn, int lockClass, int key) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pg_advisory_unlock(?, ?)")) {
            ps.setInt(1, lockClass);
            ps.setInt(2, key);
            ps.execute();
        } catch (SQLException e) {
            LOGGER.warnf("Could not release the index build lock: %s", e.getMessage());
        }
    }
}
