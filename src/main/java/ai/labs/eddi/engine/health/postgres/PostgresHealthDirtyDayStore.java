/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.postgres;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthDirtyDayStore;
import ai.labs.eddi.engine.health.model.HealthDirtyDay;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * PostgreSQL implementation of {@link IHealthDirtyDayStore}: table
 * {@code health_dirty}, primary key {@code (agent_id, day)}. Created on first
 * use.
 */
@ApplicationScoped
@DefaultBean
public class PostgresHealthDirtyDayStore implements IHealthDirtyDayStore {

    static final String TABLE = "health_dirty";

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS health_dirty (
                agent_id VARCHAR(255) NOT NULL,
                day DATE NOT NULL,
                token VARCHAR(64) NOT NULL,
                marked_at_ms BIGINT NOT NULL,
                PRIMARY KEY (agent_id, day)
            )
            """;
    static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_health_dirty_marked ON health_dirty (marked_at_ms)";

    static final String MARK = "INSERT INTO health_dirty (agent_id, day, token, marked_at_ms) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (agent_id, day) DO UPDATE SET token = EXCLUDED.token, marked_at_ms = EXCLUDED.marked_at_ms";
    static final String OLDEST = "SELECT agent_id, day, token, marked_at_ms FROM health_dirty ORDER BY marked_at_ms LIMIT ?";
    static final String CLEAR = "DELETE FROM health_dirty WHERE agent_id = ? AND day = ? AND token = ?";
    static final String EARLIEST = "SELECT MIN(day) FROM health_dirty WHERE agent_id = ?";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresHealthDirtyDayStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            statement.execute(CREATE_INDEX);
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not create the " + TABLE + " schema", e);
        }
    }

    @Override
    public void mark(String agentId, Collection<LocalDate> days) {
        if (agentId == null || days == null || days.isEmpty()) {
            return;
        }
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(MARK)) {
            long now = System.currentTimeMillis();
            for (LocalDate day : new TreeSet<>(days)) {
                statement.setString(1, agentId);
                statement.setObject(2, day);
                statement.setString(3, UUID.randomUUID().toString());
                statement.setLong(4, now);
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not mark health days dirty", e);
        }
    }

    @Override
    public List<HealthDirtyDay> oldest(int limit) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(OLDEST)) {
            statement.setInt(1, Math.max(1, limit));
            List<HealthDirtyDay> entries = new ArrayList<>();
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    entries.add(
                            new HealthDirtyDay(r.getString(1), r.getObject(2, LocalDate.class), r.getString(3), Instant.ofEpochMilli(r.getLong(4))));
                }
            }
            return entries;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read dirty health days", e);
        }
    }

    @Override
    public boolean clear(HealthDirtyDay entry) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(CLEAR)) {
            statement.setString(1, entry.agentId());
            statement.setObject(2, entry.day());
            statement.setString(3, entry.token());
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not clear a dirty health day", e);
        }
    }

    @Override
    public Optional<LocalDate> earliestPending(String agentId) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(EARLIEST)) {
            statement.setString(1, agentId);
            try (ResultSet r = statement.executeQuery()) {
                return r.next() ? Optional.ofNullable(r.getObject(1, LocalDate.class)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read dirty health days", e);
        }
    }

    @Override
    public long count() {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                Statement statement = connection.createStatement();
                ResultSet r = statement.executeQuery("SELECT COUNT(*) FROM health_dirty")) {
            return r.next() ? r.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not count dirty health days", e);
        }
    }
}
