/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.postgres;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthAlertStateStore;
import ai.labs.eddi.engine.health.model.HealthAlertState;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PostgreSQL implementation of {@link IHealthAlertStateStore}: table
 * {@code health_alert_state}, primary key {@code (rule_id, agent_id)}. Created
 * on first use.
 */
@ApplicationScoped
@DefaultBean
public class PostgresHealthAlertStateStore implements IHealthAlertStateStore {

    static final String TABLE = "health_alert_state";

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS health_alert_state (
                rule_id VARCHAR(255) NOT NULL,
                agent_id VARCHAR(255) NOT NULL,
                status VARCHAR(32),
                active TEXT[],
                evaluated_at_ms BIGINT,
                PRIMARY KEY (rule_id, agent_id)
            )
            """;
    static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_health_alert_state_evaluated ON health_alert_state (evaluated_at_ms)";

    static final String UPSERT = "INSERT INTO health_alert_state (rule_id, agent_id, status, active, evaluated_at_ms) VALUES (?, ?, ?, ?, ?) "
            + "ON CONFLICT (rule_id, agent_id) DO UPDATE SET status = EXCLUDED.status, active = EXCLUDED.active, "
            + "evaluated_at_ms = EXCLUDED.evaluated_at_ms";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresHealthAlertStateStore(Instance<DataSource> dataSourceInstance) {
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

    private Connection connection() throws SQLException {
        ensureSchema();
        return dataSourceInstance.get().getConnection();
    }

    @Override
    public Map<String, HealthAlertState> read(String ruleId, Collection<String> agentIds) {
        Map<String, HealthAlertState> found = new LinkedHashMap<>();
        if (agentIds == null || agentIds.isEmpty()) {
            return found;
        }
        try (Connection c = connection();
                PreparedStatement s = c.prepareStatement("SELECT rule_id, agent_id, status, active, evaluated_at_ms FROM health_alert_state "
                        + "WHERE rule_id = ? AND agent_id = ANY(?)")) {
            s.setString(1, ruleId);
            s.setArray(2, c.createArrayOf("text", agentIds.toArray(new String[0])));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    Array active = r.getArray(4);
                    List<String> conditions = active == null ? List.of() : Arrays.asList((String[]) active.getArray());
                    long at = r.getLong(5);
                    Instant evaluated = r.wasNull() ? null : Instant.ofEpochMilli(at);
                    found.put(r.getString(2), new HealthAlertState(r.getString(1), r.getString(2), r.getString(3), conditions, evaluated));
                }
            }
            return found;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read health alert states", e);
        }
    }

    @Override
    public void write(Collection<HealthAlertState> states) {
        if (states == null || states.isEmpty()) {
            return;
        }
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement(UPSERT)) {
            for (HealthAlertState state : states) {
                s.setString(1, state.ruleId());
                s.setString(2, state.agentId());
                s.setString(3, state.status());
                s.setArray(4, c.createArrayOf("text", state.active().toArray(new String[0])));
                s.setLong(5, state.evaluatedAt() == null ? 0L : state.evaluatedAt().toEpochMilli());
                s.addBatch();
            }
            s.executeBatch();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not store health alert states", e);
        }
    }

    @Override
    public long deleteEvaluatedBefore(Instant before) {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("DELETE FROM health_alert_state WHERE evaluated_at_ms < ?")) {
            s.setLong(1, before.toEpochMilli());
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not delete stale health alert states", e);
        }
    }

    @Override
    public long deleteByAgent(String agentId) {
        try (Connection c = connection(); PreparedStatement s = c.prepareStatement("DELETE FROM health_alert_state WHERE agent_id = ?")) {
            s.setString(1, agentId);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not delete the health alert states of an agent", e);
        }
    }
}
