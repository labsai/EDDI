/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.postgres;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthEventStore;
import ai.labs.eddi.engine.health.model.HealthEvent;
import ai.labs.eddi.engine.health.model.HealthEventCount;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PostgreSQL implementation of {@link IHealthEventStore}: table
 * {@code health_events}, primary key {@code id}; a redelivered event is
 * {@code ON CONFLICT DO NOTHING}. The schema is created on first use, never at
 * construction: on a MongoDB deployment the datasource bean is inactive.
 */
@ApplicationScoped
@DefaultBean
public class PostgresHealthEventStore implements IHealthEventStore {

    static final String TABLE = "health_events";

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS health_events (
                id VARCHAR(128) NOT NULL PRIMARY KEY,
                integration_id VARCHAR(255),
                agent_id VARCHAR(255),
                event_type VARCHAR(128),
                ref VARCHAR(256),
                ref_value VARCHAR(256),
                conversation_id VARCHAR(255),
                value DOUBLE PRECISION,
                at_ms BIGINT,
                received_at_ms BIGINT,
                day DATE,
                state VARCHAR(16) NOT NULL,
                matched_step INTEGER,
                agent_version INTEGER,
                origin VARCHAR(32),
                user_id VARCHAR(512),
                evidence VARCHAR(32),
                matched_at_ms BIGINT
            )
            """;

    static final String[] CREATE_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_health_events_pending ON health_events (received_at_ms) WHERE state = 'PENDING'",
            "CREATE INDEX IF NOT EXISTS idx_health_events_agent_day ON health_events (agent_id, day)",
            "CREATE INDEX IF NOT EXISTS idx_health_events_conversation ON health_events (conversation_id)",
            "CREATE INDEX IF NOT EXISTS idx_health_events_user ON health_events (user_id)",
            "CREATE INDEX IF NOT EXISTS idx_health_events_day ON health_events (day)"};

    static final List<String> COLUMNS = List.of("id", "integration_id", "agent_id", "event_type", "ref", "ref_value", "conversation_id", "value",
            "at_ms", "received_at_ms", "day", "state", "matched_step", "agent_version", "origin", "user_id", "evidence", "matched_at_ms");

    static final String INSERT = "INSERT INTO health_events (" + String.join(", ", COLUMNS) + ") VALUES ("
            + COLUMNS.stream().map(c -> "?").collect(Collectors.joining(", ")) + ") ON CONFLICT (id) DO NOTHING";

    private static final String SELECT = "SELECT " + String.join(", ", COLUMNS) + " FROM health_events ";

    static final String PENDING = SELECT + "WHERE state = 'PENDING' ORDER BY received_at_ms LIMIT ?";
    static final String MARK_MATCHED = "UPDATE health_events SET state = 'MATCHED', conversation_id = ?, matched_step = ?, agent_version = ?, "
            + "origin = ?, user_id = ?, evidence = ?, matched_at_ms = ?, ref_value = NULL WHERE id = ? AND state = 'PENDING'";
    static final String BY_CONVERSATION = SELECT + "WHERE conversation_id = ? AND state = 'MATCHED' ORDER BY at_ms, received_at_ms";
    static final String BY_USER = SELECT + "WHERE user_id = ? ORDER BY at_ms DESC LIMIT ?";

    static String countsSql(boolean withVersion, boolean perDay) {
        String day = perDay ? "day" : "CAST(NULL AS DATE)";
        return "SELECT event_type, evidence, " + day + " AS d, COUNT(*), COALESCE(SUM(value), 0), COUNT(value) FROM health_events "
                + "WHERE agent_id = ? AND state = 'MATCHED' AND day >= ? AND day <= ? AND origin = ANY(?)"
                + (withVersion ? " AND agent_version = ?" : "")
                + " GROUP BY event_type, evidence, d";
    }

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresHealthEventStore(Instance<DataSource> dataSourceInstance) {
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

    private Connection connection() throws SQLException {
        ensureSchema();
        return dataSourceInstance.get().getConnection();
    }

    @Override
    public int insert(Collection<HealthEvent> rows) {
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        try (Connection connection = connection(); PreparedStatement s = connection.prepareStatement(INSERT)) {
            for (HealthEvent row : rows) {
                bind(s, row);
                s.addBatch();
            }
            int inserted = 0;
            for (int count : s.executeBatch()) {
                // SUCCESS_NO_INFO is a row the driver did not count: assume it was new.
                inserted += count == Statement.SUCCESS_NO_INFO ? 1 : Math.max(0, count);
            }
            return inserted;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not store health events", e);
        }
    }

    static void bind(PreparedStatement s, HealthEvent e) throws SQLException {
        int i = 1;
        s.setString(i++, e.id());
        s.setString(i++, e.integrationId());
        s.setString(i++, e.agentId());
        s.setString(i++, e.eventType());
        s.setString(i++, e.ref());
        s.setString(i++, e.refValue());
        s.setString(i++, e.conversationId());
        if (e.value() == null) {
            s.setNull(i++, Types.DOUBLE);
        } else {
            s.setDouble(i++, e.value());
        }
        setLong(s, i++, millis(e.at()));
        setLong(s, i++, millis(e.receivedAt()));
        s.setObject(i++, e.day());
        s.setString(i++, e.state());
        setInteger(s, i++, e.matchedStep());
        setInteger(s, i++, e.agentVersion());
        s.setString(i++, e.origin());
        s.setString(i++, e.userId());
        s.setString(i++, e.evidence());
        setLong(s, i, millis(e.matchedAt()));
    }

    @Override
    public List<HealthEvent> findPending(int limit) {
        return query(PENDING, "Could not read the pending health events", s -> s.setInt(1, Math.max(1, limit)));
    }

    @Override
    public boolean markMatched(HealthEvent m) {
        return update(MARK_MATCHED, "Could not record a health event match", s -> {
            int i = 1;
            s.setString(i++, m.conversationId());
            setInteger(s, i++, m.matchedStep());
            setInteger(s, i++, m.agentVersion());
            s.setString(i++, m.origin());
            s.setString(i++, m.userId());
            s.setString(i++, m.evidence());
            setLong(s, i++, millis(m.matchedAt()));
            s.setString(i, m.id());
        }) > 0;
    }

    @Override
    public long delete(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0L;
        }
        try (Connection connection = connection(); PreparedStatement s = connection.prepareStatement("DELETE FROM health_events WHERE id = ANY(?)")) {
            s.setArray(1, connection.createArrayOf("text", new LinkedHashSet<>(ids).toArray(new String[0])));
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not delete health events", e);
        }
    }

    @Override
    public long deletePendingReceivedBefore(Instant before) {
        return update("DELETE FROM health_events WHERE state = 'PENDING' AND received_at_ms < ?", "Could not delete unmatched health events",
                s -> s.setLong(1, before.toEpochMilli()));
    }

    @Override
    public long countPending(String agentId) {
        try (Connection connection = connection();
                PreparedStatement s = connection.prepareStatement("SELECT COUNT(*) FROM health_events WHERE state = 'PENDING' AND agent_id = ?")) {
            s.setString(1, agentId);
            try (ResultSet r = s.executeQuery()) {
                return r.next() ? r.getLong(1) : 0L;
            }
        } catch (SQLException e) {
            throw new HealthStoreException("Could not count the pending health events of an agent", e);
        }
    }

    @Override
    public List<HealthEvent> findMatchedByConversation(String conversationId) {
        return query(BY_CONVERSATION, "Could not read the health events of a conversation", s -> s.setString(1, conversationId));
    }

    @Override
    public List<HealthEventCount> counts(HealthUsageQuery query, boolean perDay) {
        if (query.origins().isEmpty()) {
            return List.of();
        }
        try (Connection connection = connection(); PreparedStatement s = connection.prepareStatement(countsSql(query.version() != null, perDay))) {
            int i = 1;
            s.setString(i++, query.agentId());
            s.setObject(i++, query.from());
            s.setObject(i++, query.to());
            s.setArray(i++, connection.createArrayOf("text", query.origins().toArray(new String[0])));
            if (query.version() != null) {
                s.setInt(i, query.version());
            }
            List<HealthEventCount> counts = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    counts.add(new HealthEventCount(r.getObject(3, LocalDate.class), r.getString(1), r.getString(2), r.getLong(4), r.getDouble(5),
                            r.getLong(6)));
                }
            }
            return counts;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not count the health events of an agent", e);
        }
    }

    @Override
    public List<HealthEvent> findByUserId(String userId, int limit) {
        return query(BY_USER, "Could not read a user's health events", s -> {
            s.setString(1, userId);
            s.setInt(2, Math.max(1, limit));
        });
    }

    @Override
    public long deleteByUserId(String userId) {
        return update("DELETE FROM health_events WHERE user_id = ?", "Could not delete a user's health events", s -> s.setString(1, userId));
    }

    @Override
    public long deleteByConversationId(String conversationId) {
        return update("DELETE FROM health_events WHERE conversation_id = ?", "Could not delete a conversation's health events",
                s -> s.setString(1, conversationId));
    }

    @Override
    public long deleteOlderThan(LocalDate day) {
        return update("DELETE FROM health_events WHERE day < ?", "Could not delete expired health events", s -> s.setObject(1, day));
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private List<HealthEvent> query(String sql, String failure, Binder binder) {
        try (Connection connection = connection(); PreparedStatement s = connection.prepareStatement(sql)) {
            binder.bind(s);
            List<HealthEvent> found = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    found.add(read(r));
                }
            }
            return found;
        } catch (SQLException e) {
            throw new HealthStoreException(failure, e);
        }
    }

    private long update(String sql, String failure, Binder binder) {
        try (Connection connection = connection(); PreparedStatement s = connection.prepareStatement(sql)) {
            binder.bind(s);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException(failure, e);
        }
    }

    static HealthEvent read(ResultSet r) throws SQLException {
        double value = r.getDouble("value");
        Double boxed = r.wasNull() ? null : value;
        return new HealthEvent(r.getString("id"), r.getString("integration_id"), r.getString("agent_id"), r.getString("event_type"),
                r.getString("ref"), r.getString("ref_value"), r.getString("conversation_id"), boxed, instant(r, "at_ms"),
                instant(r, "received_at_ms"),
                r.getObject("day", LocalDate.class), r.getString("state"), integer(r, "matched_step"), integer(r, "agent_version"),
                r.getString("origin"), r.getString("user_id"), r.getString("evidence"), instant(r, "matched_at_ms"));
    }

    private static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant instant(ResultSet r, String column) throws SQLException {
        long value = r.getLong(column);
        return r.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static Integer integer(ResultSet r, String column) throws SQLException {
        int value = r.getInt(column);
        return r.wasNull() ? null : value;
    }

    private static void setInteger(PreparedStatement s, int index, Integer value) throws SQLException {
        if (value == null) {
            s.setNull(index, Types.INTEGER);
        } else {
            s.setInt(index, value);
        }
    }

    private static void setLong(PreparedStatement s, int index, Long value) throws SQLException {
        if (value == null) {
            s.setNull(index, Types.BIGINT);
        } else {
            s.setLong(index, value);
        }
    }
}
