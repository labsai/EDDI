/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.postgres;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthMarkerStore;
import ai.labs.eddi.engine.health.model.HealthMarker;
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
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * PostgreSQL implementation of {@link IHealthMarkerStore}: table
 * {@code health_markers}. A deployment-wide marker has a null {@code agent_id}.
 * Created on first use.
 */
@ApplicationScoped
@DefaultBean
public class PostgresHealthMarkerStore implements IHealthMarkerStore {

    static final String TABLE = "health_markers";

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS health_markers (
                id VARCHAR(64) PRIMARY KEY,
                agent_id VARCHAR(255),
                at_ms BIGINT NOT NULL,
                kind VARCHAR(40) NOT NULL,
                label VARCHAR(500) NOT NULL,
                version INTEGER,
                tenant_id VARCHAR(255)
            )
            """;
    static final String[] CREATE_INDEXES = {"CREATE INDEX IF NOT EXISTS idx_health_markers_agent_at ON health_markers (agent_id, at_ms)",
            "CREATE INDEX IF NOT EXISTS idx_health_markers_at ON health_markers (at_ms)",
            "CREATE INDEX IF NOT EXISTS idx_health_markers_tenant_at ON health_markers (tenant_id, at_ms) WHERE agent_id IS NULL"};

    static final String INSERT = "INSERT INTO health_markers (id, agent_id, at_ms, kind, label, version, tenant_id) VALUES (?, ?, ?, ?, ?, ?, ?) "
            + "ON CONFLICT (id) DO NOTHING";
    static final String SELECT = "SELECT id, agent_id, at_ms, kind, label, version, tenant_id FROM health_markers ";
    static final String FIND_FOR_AGENT = SELECT + "WHERE agent_id = ? AND at_ms >= ? AND at_ms < ? ORDER BY at_ms DESC LIMIT ?";
    static final String FIND_GLOBAL = SELECT
            + "WHERE agent_id IS NULL AND tenant_id = ANY(?) AND at_ms >= ? AND at_ms < ? ORDER BY at_ms DESC LIMIT ?";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresHealthMarkerStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection connection = dataSourceInstance.get().getConnection(); Statement statement = connection.createStatement()) {
            statement.execute(CREATE_TABLE);
            for (String index : CREATE_INDEXES) {
                statement.execute(index);
            }
            schemaInitialized = true;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not create the " + TABLE + " schema", e);
        }
    }

    @Override
    public void append(HealthMarker marker) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(INSERT)) {
            statement.setString(1, marker.id());
            statement.setString(2, marker.agentId());
            statement.setLong(3, marker.at().toEpochMilli());
            statement.setString(4, marker.kind());
            statement.setString(5, marker.label());
            if (marker.version() == null) {
                statement.setNull(6, Types.INTEGER);
            } else {
                statement.setInt(6, marker.version());
            }
            statement.setString(7, marker.tenantId());
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not store a health marker", e);
        }
    }

    @Override
    public List<HealthMarker> findForAgent(String agentId, Instant from, Instant to, int limit) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(FIND_FOR_AGENT)) {
            statement.setString(1, agentId);
            statement.setLong(2, from.toEpochMilli());
            statement.setLong(3, to.toEpochMilli());
            statement.setInt(4, Math.max(1, limit));
            return read(statement);
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read health markers", e);
        }
    }

    @Override
    public List<HealthMarker> findGlobal(Collection<String> tenantIds, Instant from, Instant to, int limit) {
        if (tenantIds == null || tenantIds.isEmpty()) {
            return List.of();
        }
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(FIND_GLOBAL)) {
            statement.setArray(1, connection.createArrayOf("text", tenantIds.toArray(new String[0])));
            statement.setLong(2, from.toEpochMilli());
            statement.setLong(3, to.toEpochMilli());
            statement.setInt(4, Math.max(1, limit));
            return read(statement);
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read health markers", e);
        }
    }

    private static List<HealthMarker> read(PreparedStatement statement) throws SQLException {
        List<HealthMarker> found = new ArrayList<>();
        try (ResultSet r = statement.executeQuery()) {
            while (r.next()) {
                int version = r.getInt(6);
                Integer v = r.wasNull() ? null : version;
                found.add(new HealthMarker(r.getString(1), r.getString(2), Instant.ofEpochMilli(r.getLong(3)), r.getString(4), r.getString(5), v,
                        r.getString(7)));
            }
        }
        return found;
    }

    @Override
    public long deleteOlderThan(Instant before) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM health_markers WHERE at_ms < ?")) {
            statement.setLong(1, before.toEpochMilli());
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not delete expired health markers", e);
        }
    }
}
