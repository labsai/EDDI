/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.org;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Objects;

/**
 * PostgreSQL {@link IOrgMarkerStore}: one row, {@code id = 'org-platform'}, in
 * {@code org_platform_state}.
 * <p>
 * {@link #read()} checks {@code to_regclass} first and never creates the table:
 * it runs in {@link DataLayoutGuard} before anything may write. The writers
 * create the table on first use.
 */
@ApplicationScoped
@DefaultBean
public class PostgresOrgMarkerStore implements IOrgMarkerStore {

    static final String TABLE = "org_platform_state";
    static final String RECORD_ID = "org-platform";
    /** Bounds one read, so the guard's retry loop keeps its own deadline. */
    static final int READ_TIMEOUT_SECONDS = 5;

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS org_platform_state (
                id TEXT PRIMARY KEY,
                marker_state TEXT NOT NULL,
                required_layout INTEGER NOT NULL,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """;
    static final String ENSURE_RECORD = "INSERT INTO org_platform_state (id, marker_state, required_layout) VALUES (?, 'ABSENT', 0) "
            + "ON CONFLICT (id) DO NOTHING";
    static final String READ = "SELECT marker_state, required_layout, updated_at FROM org_platform_state WHERE id = ?";
    static final String MOVE = "UPDATE org_platform_state SET marker_state = ?, updated_at = now() WHERE id = ? AND marker_state = ?";
    static final String RAISE = "UPDATE org_platform_state SET required_layout = ?, updated_at = now() WHERE id = ? AND required_layout < ?";

    private final Instance<DataSource> dataSource;
    private volatile boolean schemaReady;

    @Inject
    public PostgresOrgMarkerStore(Instance<DataSource> dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public OrgMarker read() {
        try (Connection connection = dataSource.get().getConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(READ_TIMEOUT_SECONDS);
                try (ResultSet exists = statement.executeQuery("SELECT to_regclass('" + TABLE + "') IS NOT NULL")) {
                    exists.next();
                    if (!exists.getBoolean(1)) {
                        return OrgMarker.NONE;
                    }
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(READ)) {
                // bounds a read that a hung server or an exclusive lock on the table would
                // block
                statement.setQueryTimeout(READ_TIMEOUT_SECONDS);
                statement.setString(1, RECORD_ID);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next()) {
                        return OrgMarker.NONE;
                    }
                    Timestamp updatedAt = row.getTimestamp(3);
                    return OrgMarker.parse(row.getString(1), row.getObject(2), updatedAt == null ? null : updatedAt.toInstant());
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not read the org layout record", e);
        }
    }

    @Override
    public boolean moveMarker(OrgMarkerState expected, OrgMarkerState next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        if (!expected.canMoveTo(next)) {
            throw new IllegalArgumentException("The org marker cannot move from " + expected + " to " + next);
        }
        try (Connection connection = prepared()) {
            try (PreparedStatement statement = connection.prepareStatement(MOVE)) {
                statement.setString(1, next.name());
                statement.setString(2, RECORD_ID);
                statement.setString(3, expected.name());
                return statement.executeUpdate() == 1;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not move the org marker", e);
        }
    }

    @Override
    public int raiseRequiredLayout(int level) {
        if (level < 0) {
            throw new IllegalArgumentException("level must not be negative");
        }
        try (Connection connection = prepared()) {
            try (PreparedStatement statement = connection.prepareStatement(RAISE)) {
                statement.setInt(1, level);
                statement.setString(2, RECORD_ID);
                statement.setInt(3, level);
                statement.executeUpdate();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not raise the required org layout", e);
        }
        return read().requiredLayout();
    }

    /** A connection on which the table and the record exist. */
    private Connection prepared() throws SQLException {
        Connection connection = dataSource.get().getConnection();
        try {
            if (!schemaReady) {
                createTable(connection);
                schemaReady = true;
            }
            try (PreparedStatement statement = connection.prepareStatement(ENSURE_RECORD)) {
                statement.setString(1, RECORD_ID);
                statement.executeUpdate();
            }
            return connection;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                e.addSuppressed(closeFailure);
            }
            throw e;
        }
    }

    /**
     * {@code CREATE TABLE IF NOT EXISTS} is not safe against a concurrent create:
     * two nodes can both pass the check and one fails on the catalog's unique index
     * (23505) or as a duplicate table (42P07). The table exists either way, so
     * those two are not errors here.
     */
    private static void createTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
        } catch (SQLException e) {
            if (!"23505".equals(e.getSQLState()) && !"42P07".equals(e.getSQLState())) {
                throw e;
            }
        }
    }
}
