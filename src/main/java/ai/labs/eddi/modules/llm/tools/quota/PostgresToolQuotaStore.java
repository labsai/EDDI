/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IToolQuotaStore}.
 * <p>
 * {@link #tryAcquire} is one {@code INSERT ... ON CONFLICT DO UPDATE ... WHERE
 * used < limit RETURNING used}: the conflict path takes the row lock,
 * re-evaluates the {@code WHERE} against the committed value and increments, so
 * concurrent callers on any node cannot overshoot. No row comes back when the
 * counter is at the limit. The {@code WHERE} on the {@code DO UPDATE} is
 * load-bearing: without it the row is rewritten and reported as updated even at
 * the limit.
 * <p>
 * Expiry is a sweep ({@code ToolQuotaMaintenance}), like the other Postgres
 * stores that expire rows; the window is part of the key, so an expired row is
 * never read again whether or not it has been swept.
 */
@ApplicationScoped
@DefaultBean
public class PostgresToolQuotaStore implements IToolQuotaStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresToolQuotaStore.class);

    static final String TABLE = "tool_quota_counters";

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS tool_quota_counters (
                agent_id VARCHAR(255) NOT NULL,
                counter_name VARCHAR(255) NOT NULL,
                subject VARCHAR(512) NOT NULL,
                window_start BIGINT NOT NULL,
                used INT NOT NULL,
                expires_at_ms BIGINT NOT NULL,
                PRIMARY KEY (agent_id, counter_name, subject, window_start)
            )
            """;

    private static final String CREATE_SUBJECT_INDEX = "CREATE INDEX IF NOT EXISTS idx_tool_quota_subject ON tool_quota_counters (subject)";
    private static final String CREATE_EXPIRES_INDEX = "CREATE INDEX IF NOT EXISTS idx_tool_quota_expires ON tool_quota_counters (expires_at_ms)";

    private static final String ACQUIRE = """
            INSERT INTO tool_quota_counters (agent_id, counter_name, subject, window_start, used, expires_at_ms)
            VALUES (?, ?, ?, ?, 1, ?)
            ON CONFLICT (agent_id, counter_name, subject, window_start)
            DO UPDATE SET used = tool_quota_counters.used + 1
            WHERE tool_quota_counters.used < ?
            RETURNING used
            """;

    private static final String RELEASE = """
            UPDATE tool_quota_counters SET used = used - 1
            WHERE agent_id = ? AND counter_name = ? AND subject = ? AND window_start = ? AND used > 0
            """;

    private static final String READ = """
            SELECT used FROM tool_quota_counters
            WHERE agent_id = ? AND counter_name = ? AND subject = ? AND window_start = ?
            """;

    /**
     * Resolved lazily, never at construction: on a MongoDB deployment the
     * datasource bean is inactive, and resolving it while the bean is built would
     * abort the boot.
     */
    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresToolQuotaStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void createSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_SUBJECT_INDEX);
            statement.execute(CREATE_EXPIRES_INDEX);
            schemaInitialized = true;
        } catch (SQLException e) {
            // Not rethrown here: the statement that follows fails too, and it is the one
            // that reports as a ToolQuotaStoreException the caller fails closed on.
            LOGGER.errorf(e, "Failed to create the %s schema", TABLE);
        }
    }

    @Override
    public Acquisition tryAcquire(ToolQuotaKey key, int limit, Instant expiresAt) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(ACQUIRE)) {
            bindKey(statement, key);
            statement.setLong(5, expiresAt.toEpochMilli());
            statement.setInt(6, limit);
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    return new Acquisition(true, rows.getInt(1));
                }
            }
            return new Acquisition(false, Math.max(limit, used(key)));
        } catch (SQLException e) {
            throw new ToolQuotaStoreException("Could not count a tool call", e);
        }
    }

    @Override
    public void release(ToolQuotaKey key) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(RELEASE)) {
            bindKey(statement, key);
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new ToolQuotaStoreException("Could not release a counted tool call", e);
        }
    }

    @Override
    public int used(ToolQuotaKey key) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(READ)) {
            bindKey(statement, key);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new ToolQuotaStoreException("Could not read a tool quota counter", e);
        }
    }

    @Override
    public long deleteBySubject(String subject) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM tool_quota_counters WHERE subject = ?")) {
            statement.setString(1, subject);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new ToolQuotaStoreException("Could not delete tool quota counters", e);
        }
    }

    @Override
    public List<ToolQuotaUsage> findBySubject(String subject, int max) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT agent_id, counter_name, window_start, used FROM tool_quota_counters WHERE subject = ? "
                                + "ORDER BY window_start DESC LIMIT ?")) {
            statement.setString(1, subject);
            statement.setInt(2, max);
            List<ToolQuotaUsage> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(new ToolQuotaUsage(rows.getString(1), rows.getString(2), rows.getLong(3), rows.getInt(4)));
                }
            }
            return found;
        } catch (SQLException e) {
            throw new ToolQuotaStoreException("Could not read tool quota counters", e);
        }
    }

    @Override
    public int deleteExpired(Instant now) {
        createSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM tool_quota_counters WHERE expires_at_ms < ?")) {
            statement.setLong(1, now.toEpochMilli());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new ToolQuotaStoreException("Could not delete expired tool quota counters", e);
        }
    }

    private static void bindKey(PreparedStatement statement, ToolQuotaKey key) throws SQLException {
        statement.setString(1, key.agentId());
        statement.setString(2, key.counter());
        statement.setString(3, key.subject());
        statement.setLong(4, key.windowStart());
    }
}
