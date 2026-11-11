/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.postgres;

import ai.labs.eddi.engine.health.HealthDailyCodec;
import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthDailyStore;
import ai.labs.eddi.engine.health.model.HealthTurnAggregate;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * PostgreSQL implementation of {@link IHealthDailyStore}: table
 * {@code health_daily}, primary key
 * {@code (agent_id, agent_version, origin, day)} with the counters in a
 * {@code JSONB} column ({@link HealthDailyCodec}). A row without a version is
 * stored as version {@code -1} (a primary-key column cannot be null). The
 * schema is created on first use.
 */
@ApplicationScoped
@DefaultBean
public class PostgresHealthDailyStore implements IHealthDailyStore {

    static final String TABLE = "health_daily";
    /** The stored version of a row without one. */
    static final int NO_VERSION = -1;

    private static final ObjectMapper JSON = new ObjectMapper();

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS health_daily (
                agent_id VARCHAR(255) NOT NULL,
                agent_version INTEGER NOT NULL,
                origin VARCHAR(32) NOT NULL,
                day DATE NOT NULL,
                counters JSONB NOT NULL,
                PRIMARY KEY (agent_id, agent_version, origin, day)
            )
            """;

    static final String[] CREATE_INDEXES = {"CREATE INDEX IF NOT EXISTS idx_health_daily_agent_day ON health_daily (agent_id, day)",
            "CREATE INDEX IF NOT EXISTS idx_health_daily_day ON health_daily (day)"};

    static final String UPSERT = "INSERT INTO health_daily (agent_id, agent_version, origin, day, counters) VALUES (?, ?, ?, ?, CAST(? AS JSONB)) "
            + "ON CONFLICT (agent_id, agent_version, origin, day) DO UPDATE SET counters = EXCLUDED.counters";
    static final String SELECT = "SELECT agent_id, agent_version, origin, day, counters::text FROM health_daily ";
    static final String BY_AGENT = SELECT + "WHERE agent_id = ? AND day >= ? AND day <= ?";
    static final String BY_AGENTS = SELECT + "WHERE agent_id = ANY(?) AND day >= ? AND day <= ?";
    static final String FIRST_DAYS = "SELECT agent_version, MIN(day) FROM health_daily WHERE agent_id = ? AND agent_version <> -1 "
            + "GROUP BY agent_version";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresHealthDailyStore(Instance<DataSource> dataSourceInstance) {
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

    /** The delete of the agent-day's rows not among {@code keep} rows. */
    static String deleteOthers(int keep) {
        StringBuilder sql = new StringBuilder("DELETE FROM health_daily WHERE agent_id = ? AND day = ?");
        for (int i = 0; i < keep; i++) {
            sql.append(" AND NOT (agent_version = ? AND origin = ?)");
        }
        return sql.toString();
    }

    @Override
    public void replaceDay(String agentId, LocalDate day, List<HealthTurnAggregate> rows) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement upsert = connection.prepareStatement(UPSERT)) {
                    for (HealthTurnAggregate row : rows) {
                        upsert.setString(1, row.agentId());
                        upsert.setInt(2, row.agentVersion() == null ? NO_VERSION : row.agentVersion());
                        upsert.setString(3, row.origin());
                        upsert.setObject(4, row.day());
                        upsert.setString(5, JSON.writeValueAsString(HealthDailyCodec.counters(row)));
                        upsert.addBatch();
                    }
                    if (!rows.isEmpty()) {
                        upsert.executeBatch();
                    }
                }
                try (PreparedStatement delete = connection.prepareStatement(deleteOthers(rows.size()))) {
                    int i = 1;
                    delete.setString(i++, agentId);
                    delete.setObject(i++, day);
                    for (HealthTurnAggregate row : rows) {
                        delete.setInt(i++, row.agentVersion() == null ? NO_VERSION : row.agentVersion());
                        delete.setString(i++, row.origin());
                    }
                    delete.executeUpdate();
                }
                connection.commit();
            } catch (SQLException | JsonProcessingException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException | JsonProcessingException e) {
            throw new HealthStoreException("Could not store the daily health rows of an agent's day", e);
        }
    }

    @Override
    public List<HealthTurnAggregate> find(String agentId, LocalDate from, LocalDate to) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(BY_AGENT)) {
            statement.setString(1, agentId);
            statement.setObject(2, from);
            statement.setObject(3, to);
            return read(statement);
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read daily health rows", e);
        }
    }

    @Override
    public List<HealthTurnAggregate> findForAgents(Collection<String> agentIds, LocalDate from, LocalDate to) {
        if (agentIds == null || agentIds.isEmpty()) {
            return List.of();
        }
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(BY_AGENTS)) {
            statement.setArray(1, connection.createArrayOf("text", agentIds.toArray(new String[0])));
            statement.setObject(2, from);
            statement.setObject(3, to);
            return read(statement);
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read daily health rows", e);
        }
    }

    private static List<HealthTurnAggregate> read(PreparedStatement statement) throws SQLException {
        List<HealthTurnAggregate> rows = new ArrayList<>();
        try (ResultSet r = statement.executeQuery()) {
            while (r.next()) {
                int version = r.getInt(2);
                rows.add(HealthDailyCodec.fromCounters(r.getString(1), version == NO_VERSION ? null : version, r.getString(3),
                        r.getObject(4, LocalDate.class), counters(r.getString(5))));
            }
        }
        return rows;
    }

    static Map<?, ?> counters(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    @Override
    public Map<Integer, LocalDate> firstDayByVersion(String agentId) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(FIRST_DAYS)) {
            statement.setString(1, agentId);
            Map<Integer, LocalDate> first = new TreeMap<>();
            try (ResultSet r = statement.executeQuery()) {
                while (r.next()) {
                    first.put(r.getInt(1), r.getObject(2, LocalDate.class));
                }
            }
            return first;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read the first day of each agent version", e);
        }
    }

    @Override
    public long deleteOlderThan(LocalDate day) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement("DELETE FROM health_daily WHERE day < ?")) {
            statement.setObject(1, day);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not delete expired daily health rows", e);
        }
    }
}
