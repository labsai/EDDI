/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.hitl.oversight.OversightJson;
import ai.labs.eddi.engine.hitl.oversight.autonomy.AutonomyState;
import ai.labs.eddi.engine.hitl.oversight.autonomy.IAutonomyStateStore;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL implementation of {@link IAutonomyStateStore}: table
 * {@code autonomy_state}, primary key {@code (agent_id, category)}. The state
 * is JSON text in {@code data}; {@code version} and {@code suspension_active}
 * are columns for compare-and-set and the overlay lookup.
 *
 * @since 6.7.0
 */
@ApplicationScoped
@DefaultBean
public class PostgresAutonomyStateStore implements IAutonomyStateStore {

    private static final Logger LOGGER = Logger.getLogger(PostgresAutonomyStateStore.class);

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS autonomy_state (
                agent_id VARCHAR(255) NOT NULL,
                category VARCHAR(255) NOT NULL,
                version BIGINT NOT NULL,
                suspension_active BOOLEAN NOT NULL DEFAULT FALSE,
                data TEXT NOT NULL,
                PRIMARY KEY (agent_id, category)
            )
            """;

    static final String CREATE_INDEX = "CREATE INDEX IF NOT EXISTS idx_autonomy_state_suspended ON autonomy_state (agent_id) "
            + "WHERE suspension_active";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresAutonomyStateStore(Instance<DataSource> dataSourceInstance) {
        this.dataSourceInstance = dataSourceInstance;
    }

    private synchronized void ensureSchema() {
        if (schemaInitialized) {
            return;
        }
        try (Connection conn = dataSourceInstance.get().getConnection(); Statement stmt = conn.createStatement()) {
            stmt.execute(CREATE_TABLE);
            stmt.execute(CREATE_INDEX);
            schemaInitialized = true;
            LOGGER.info("PostgresAutonomyStateStore schema initialized");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to initialize autonomy_state table", e);
        }
    }

    @Override
    public Optional<AutonomyState> read(String agentId, String category) {
        ensureSchema();
        return select("SELECT version, data FROM autonomy_state WHERE agent_id = ? AND category = ?", List.of(agentId, category)).stream()
                .findFirst();
    }

    @Override
    public List<AutonomyState> list(String agentId, int limit) {
        ensureSchema();
        if (agentId == null) {
            return select("SELECT version, data FROM autonomy_state ORDER BY agent_id, category LIMIT ?", List.of(Math.max(0, limit)));
        }
        return select("SELECT version, data FROM autonomy_state WHERE agent_id = ? ORDER BY category LIMIT ?",
                List.of(agentId, Math.max(0, limit)));
    }

    @Override
    public List<AutonomyState> listSuspended(String agentId) {
        ensureSchema();
        return select("SELECT version, data FROM autonomy_state WHERE agent_id = ? AND suspension_active", List.of(agentId));
    }

    @Override
    public long countSuspended() {
        ensureSchema();
        try (Connection conn = dataSourceInstance.get().getConnection();
                PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM autonomy_state WHERE suspension_active");
                ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to count suspensions", e);
        }
    }

    @Override
    public Optional<AutonomyState> save(AutonomyState state, long expectedVersion) {
        ensureSchema();
        AutonomyState stored = state.withVersion(expectedVersion + 1);
        String sql = expectedVersion == 0
                ? "INSERT INTO autonomy_state (version, suspension_active, data, agent_id, category) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (agent_id, category) DO NOTHING"
                : "UPDATE autonomy_state SET version = ?, suspension_active = ?, data = ? WHERE agent_id = ? AND category = ? AND version = ?";
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, stored.version());
            ps.setBoolean(2, stored.suspended());
            ps.setString(3, OversightJson.write(stored));
            ps.setString(4, state.agentId());
            ps.setString(5, state.category());
            if (expectedVersion != 0) {
                ps.setLong(6, expectedVersion);
            }
            return ps.executeUpdate() > 0 ? Optional.of(stored) : Optional.empty();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store autonomy state", e);
        }
    }

    private List<AutonomyState> select(String sql, List<Object> params) {
        try (Connection conn = dataSourceInstance.get().getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
            PostgresApprovalDecisionStore.bind(ps, params);
            List<AutonomyState> rows = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(OversightJson.read(rs.getString("data"), AutonomyState.class).withVersion(rs.getLong("version")));
                }
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read autonomy state", e);
        }
    }
}
