/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.postgres;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * PostgreSQL implementation of {@link IHealthTurnStore}: table
 * {@code health_turns}, primary key {@code (conversation_id, execution_id)}.
 * <p>
 * {@link #upsert} is one {@code INSERT ... ON CONFLICT DO UPDATE SET} of every
 * column from {@code EXCLUDED} — a replace, never an increment — except:
 * {@code day} and {@code recorded_at_ms} keep the stored value
 * ({@code COALESCE}, first recorded wins); {@code discarded} only ever turns
 * true ({@code health_turns.discarded OR EXCLUDED.discarded}); and the update
 * applies only
 * {@code WHERE health_turns.updated_at_ms <= EXCLUDED.updated_at_ms} (or the
 * stored row is a discard marker), so a stale write is ignored.
 * {@link #insertIfAbsent} is {@code ON CONFLICT DO NOTHING}.
 * <p>
 * A discard marker has {@code discarded_at_ms} and no {@code recorded_at_ms};
 * reads skip it. Actions are a {@code TEXT[]}, the failure list a small
 * {@code JSONB}. The schema is created on first use, never at construction: on
 * a MongoDB deployment the datasource bean is inactive.
 */
@ApplicationScoped
@DefaultBean
public class PostgresHealthTurnStore implements IHealthTurnStore {

    static final String TABLE = "health_turns";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> FAILURE_LIST = new TypeReference<>() {
    };

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS health_turns (
                conversation_id VARCHAR(255) NOT NULL,
                execution_id VARCHAR(64) NOT NULL,
                step_index INTEGER,
                agent_id VARCHAR(255),
                agent_version INTEGER,
                previous_agent_version INTEGER,
                user_id VARCHAR(512),
                origin VARCHAR(32),
                day DATE,
                recorded_at_ms BIGINT,
                updated_at_ms BIGINT,
                cost_usd DOUBLE PRECISION,
                discarded_cost_usd DOUBLE PRECISION,
                input_tokens BIGINT,
                output_tokens BIGINT,
                total_tokens BIGINT,
                duration_ms BIGINT,
                error_kind VARCHAR(32),
                paused BOOLEAN NOT NULL DEFAULT FALSE,
                failures JSONB,
                fallback_served BOOLEAN NOT NULL DEFAULT FALSE,
                tool_calls INTEGER NOT NULL DEFAULT 0,
                tool_failures INTEGER NOT NULL DEFAULT 0,
                tool_refusals INTEGER NOT NULL DEFAULT 0,
                guardrail_trips INTEGER NOT NULL DEFAULT 0,
                user_input BOOLEAN NOT NULL DEFAULT FALSE,
                agent_output BOOLEAN NOT NULL DEFAULT FALSE,
                last_output_question BOOLEAN NOT NULL DEFAULT FALSE,
                actions TEXT[],
                discarded BOOLEAN NOT NULL DEFAULT FALSE,
                discarded_at_ms BIGINT,
                PRIMARY KEY (conversation_id, execution_id)
            )
            """;

    /**
     * Rollups and drill-down read an agent's days; erasure finds rows by user;
     * retention deletes by day and markers by their time; the orphan sweep finds
     * recently written rows by {@code updated_at_ms} (and markers by their time).
     * Rows by conversation use the primary key.
     */
    static final String[] CREATE_INDEXES = {"CREATE INDEX IF NOT EXISTS idx_health_turns_agent_day ON health_turns (agent_id, day)",
            "CREATE INDEX IF NOT EXISTS idx_health_turns_user ON health_turns (user_id)",
            "CREATE INDEX IF NOT EXISTS idx_health_turns_day ON health_turns (day)",
            "CREATE INDEX IF NOT EXISTS idx_health_turns_discarded_at ON health_turns (discarded_at_ms) WHERE day IS NULL",
            "CREATE INDEX IF NOT EXISTS idx_health_turns_updated ON health_turns (updated_at_ms)"};

    /** The columns a row write binds, in bind order. */
    static final List<String> WRITTEN = List.of("conversation_id", "execution_id", "step_index", "agent_id", "agent_version",
            "previous_agent_version", "user_id", "origin", "day", "recorded_at_ms", "updated_at_ms", "cost_usd", "discarded_cost_usd",
            "input_tokens", "output_tokens", "total_tokens", "duration_ms", "error_kind", "paused", "failures", "fallback_served", "tool_calls",
            "tool_failures", "tool_refusals", "guardrail_trips", "user_input", "agent_output", "last_output_question", "actions", "discarded");

    /** Columns a replace does not overwrite with the written value. */
    private static final Set<String> NOT_REPLACED = Set.of("conversation_id", "execution_id", "day", "recorded_at_ms", "discarded");

    static final String COLUMNS = String.join(", ", WRITTEN);

    private static final String VALUES = WRITTEN.stream().map(c -> "failures".equals(c) ? "CAST(? AS JSONB)" : "?")
            .collect(Collectors.joining(", "));

    private static final String INSERT = "INSERT INTO health_turns (" + COLUMNS + ") VALUES (" + VALUES + ") ";

    static final String UPSERT = INSERT + "ON CONFLICT (conversation_id, execution_id) DO UPDATE SET "
            + WRITTEN.stream().filter(c -> !NOT_REPLACED.contains(c)).map(c -> c + " = EXCLUDED." + c).collect(Collectors.joining(", "))
            + ", day = COALESCE(health_turns.day, EXCLUDED.day), recorded_at_ms = COALESCE(health_turns.recorded_at_ms, EXCLUDED.recorded_at_ms)"
            + ", discarded = health_turns.discarded OR EXCLUDED.discarded"
            + " WHERE health_turns.updated_at_ms IS NULL OR health_turns.updated_at_ms <= EXCLUDED.updated_at_ms";

    static final String INSERT_IF_ABSENT = INSERT + "ON CONFLICT (conversation_id, execution_id) DO NOTHING";

    static final String MARK_DISCARDED = "INSERT INTO health_turns (conversation_id, execution_id, agent_id, user_id, discarded, discarded_at_ms) "
            + "VALUES (?, ?, ?, ?, TRUE, ?) ON CONFLICT (conversation_id, execution_id) DO UPDATE SET discarded = TRUE";

    private static final String SELECT = "SELECT " + WRITTEN.stream().map(c -> "failures".equals(c) ? "failures::text AS failures" : c)
            .collect(Collectors.joining(", ")) + " FROM health_turns ";

    static final String BY_CONVERSATION = SELECT
            + "WHERE conversation_id = ? AND recorded_at_ms IS NOT NULL ORDER BY step_index, recorded_at_ms LIMIT ?";
    static final String BY_AGENT_DAYS = SELECT + "WHERE agent_id = ? AND day >= ? AND day <= ? ORDER BY day, recorded_at_ms LIMIT ?";
    static final String BY_AGENT_DAY = SELECT + "WHERE agent_id = ? AND day = ?";
    static final String BY_USER = SELECT + "WHERE user_id = ? AND recorded_at_ms IS NOT NULL ORDER BY recorded_at_ms DESC LIMIT ?";
    static final String CONVERSATIONS_UPDATED_SINCE = "SELECT DISTINCT conversation_id FROM health_turns "
            + "WHERE updated_at_ms >= ? OR (day IS NULL AND discarded_at_ms >= ?) LIMIT ?";
    static final String DELETE_OLDER = "DELETE FROM health_turns WHERE day < ? OR (day IS NULL AND discarded_at_ms < ?)";

    /** Rows fetched per round trip while a rollup streams an agent-day. */
    static final int STREAM_FETCH_SIZE = 500;

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresHealthTurnStore(Instance<DataSource> dataSourceInstance) {
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
            // Thrown, not logged: the recorder rate-limits its own report, and
            // schemaInitialized stays false so the next call retries the DDL.
            throw new HealthStoreException("Could not create the " + TABLE + " schema", e);
        }
    }

    @Override
    public void upsert(HealthTurn turn) {
        write(UPSERT, turn);
    }

    @Override
    public void insertIfAbsent(HealthTurn turn) {
        write(INSERT_IF_ABSENT, turn);
    }

    private void write(String sql, HealthTurn turn) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, turn);
            statement.executeUpdate();
        } catch (SQLException | JsonProcessingException e) {
            throw new HealthStoreException("Could not store a health turn", e);
        }
    }

    static void bind(Connection connection, PreparedStatement s, HealthTurn turn) throws SQLException, JsonProcessingException {
        int i = 1;
        s.setString(i++, turn.conversationId());
        s.setString(i++, turn.executionId());
        s.setInt(i++, turn.stepIndex());
        s.setString(i++, turn.agentId());
        setInteger(s, i++, turn.agentVersion());
        setInteger(s, i++, turn.previousAgentVersion());
        s.setString(i++, turn.userId());
        s.setString(i++, turn.origin());
        s.setObject(i++, turn.day());
        setLong(s, i++, millis(turn.timestamp()));
        setLong(s, i++, millis(turn.updatedAt()));
        setDouble(s, i++, turn.costUsd());
        setDouble(s, i++, turn.discardedCostUsd());
        setLong(s, i++, turn.inputTokens());
        setLong(s, i++, turn.outputTokens());
        setLong(s, i++, turn.totalTokens());
        setLong(s, i++, turn.durationMs());
        s.setString(i++, turn.errorKind());
        s.setBoolean(i++, turn.paused());
        s.setString(i++, failuresJson(turn.failures()));
        s.setBoolean(i++, turn.fallbackServed());
        s.setInt(i++, turn.toolCalls());
        s.setInt(i++, turn.toolFailures());
        s.setInt(i++, turn.toolRefusals());
        s.setInt(i++, turn.guardrailTrips());
        s.setBoolean(i++, turn.userInput());
        s.setBoolean(i++, turn.agentOutput());
        s.setBoolean(i++, turn.lastOutputWasQuestion());
        s.setArray(i++, connection.createArrayOf("text", turn.actions().toArray(new String[0])));
        s.setBoolean(i, turn.discarded());
    }

    private static Long millis(Instant instant) {
        return instant == null ? null : instant.toEpochMilli();
    }

    static String failuresJson(List<HealthFailure> failures) throws JsonProcessingException {
        List<Map<String, Object>> entries = new ArrayList<>(failures.size());
        for (HealthFailure failure : failures) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put(HealthFailure.KEY_CLASS, failure.failureClass());
            entry.put(HealthFailure.KEY_MODEL, failure.model());
            entry.put(HealthFailure.KEY_RECOVERY, failure.recovery());
            entries.add(entry);
        }
        return JSON.writeValueAsString(entries);
    }

    static List<HealthFailure> failuresOf(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<HealthFailure> failures = new ArrayList<>();
            for (Map<String, Object> entry : JSON.readValue(json, FAILURE_LIST)) {
                HealthFailure failure = HealthFailure.fromRecord(entry);
                if (failure != null) {
                    failures.add(failure);
                }
            }
            return failures;
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    @Override
    public long markDiscarded(HealthTurn replacing, Collection<String> executionIds) {
        if (replacing == null || replacing.conversationId() == null || executionIds == null || executionIds.isEmpty()) {
            return 0L;
        }
        ensureSchema();
        long at = (replacing.updatedAt() != null ? replacing.updatedAt() : Instant.now()).toEpochMilli();
        try (Connection connection = dataSourceInstance.get().getConnection();
                PreparedStatement statement = connection.prepareStatement(MARK_DISCARDED)) {
            Set<String> ids = new LinkedHashSet<>(executionIds);
            for (String executionId : ids) {
                statement.setString(1, replacing.conversationId());
                statement.setString(2, executionId);
                statement.setString(3, replacing.agentId());
                statement.setString(4, replacing.userId());
                statement.setLong(5, at);
                statement.addBatch();
            }
            statement.executeBatch();
            return ids.size();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not mark health turns discarded", e);
        }
    }

    @Override
    public List<HealthTurn> findByConversation(String conversationId, int limit) {
        return query(BY_CONVERSATION, "Could not read the health turns of a conversation", s -> {
            s.setString(1, conversationId);
            s.setInt(2, Math.max(1, limit));
        });
    }

    @Override
    public List<HealthTurn> findByAgentAndDayRange(String agentId, LocalDate from, LocalDate to, int limit) {
        return query(BY_AGENT_DAYS, "Could not read the health turns of an agent", s -> {
            s.setString(1, agentId);
            s.setObject(2, from);
            s.setObject(3, to);
            s.setInt(4, Math.max(1, limit));
        });
    }

    @Override
    public void forEachByAgentAndDay(String agentId, LocalDate day, Consumer<HealthTurn> consumer) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection()) {
            // A cursor-backed fetch needs a transaction on PostgreSQL; without one the
            // driver reads the whole result before returning the first row.
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(BY_AGENT_DAY)) {
                statement.setFetchSize(STREAM_FETCH_SIZE);
                statement.setString(1, agentId);
                statement.setObject(2, day);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        consumer.accept(read(rows));
                    }
                }
                connection.commit();
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read the health turns of an agent's day", e);
        }
    }

    @Override
    public List<HealthTurn> findByUserId(String userId, int limit) {
        return query(BY_USER, "Could not read a user's health turns", s -> {
            s.setString(1, userId);
            s.setInt(2, Math.max(1, limit));
        });
    }

    @Override
    public long deleteByUserId(String userId) {
        return delete("DELETE FROM health_turns WHERE user_id = ?", "Could not delete a user's health turns", s -> s.setString(1, userId));
    }

    @Override
    public long deleteByConversationId(String conversationId) {
        return delete("DELETE FROM health_turns WHERE conversation_id = ?", "Could not delete a conversation's health turns",
                s -> s.setString(1, conversationId));
    }

    @Override
    public void forEachConversationUpdatedSince(Instant since, int limit, Consumer<String> consumer) {
        ensureSchema();
        long at = since.toEpochMilli();
        try (Connection connection = dataSourceInstance.get().getConnection()) {
            // Cursor-backed, as in forEachByAgentAndDay: a large window streams.
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(CONVERSATIONS_UPDATED_SINCE)) {
                statement.setFetchSize(STREAM_FETCH_SIZE);
                statement.setLong(1, at);
                statement.setLong(2, at);
                statement.setInt(3, Math.max(1, limit));
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        consumer.accept(rows.getString(1));
                    }
                }
                connection.commit();
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read the conversations of recent health turns", e);
        }
    }

    @Override
    public long deleteOlderThan(LocalDate day) {
        return delete(DELETE_OLDER, "Could not delete expired health turns", s -> {
            s.setObject(1, day);
            s.setLong(2, day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli());
        });
    }

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    private List<HealthTurn> query(String sql, String failure, Binder binder) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            List<HealthTurn> found = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(read(rows));
                }
            }
            return found;
        } catch (SQLException e) {
            throw new HealthStoreException(failure, e);
        }
    }

    private long delete(String sql, String failure, Binder binder) {
        ensureSchema();
        try (Connection connection = dataSourceInstance.get().getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException(failure, e);
        }
    }

    static HealthTurn read(ResultSet r) throws SQLException {
        List<String> actions = new ArrayList<>();
        Array array = r.getArray("actions");
        if (array != null && array.getArray() instanceof Object[] values) {
            for (Object value : values) {
                if (value != null) {
                    actions.add(String.valueOf(value));
                }
            }
        }
        Integer stepIndex = integer(r, "step_index");
        Long recorded = longValue(r, "recorded_at_ms");
        Long updated = longValue(r, "updated_at_ms");
        return new HealthTurn(r.getString("conversation_id"), stepIndex == null ? -1 : stepIndex, r.getString("execution_id"),
                r.getString("agent_id"), integer(r, "agent_version"), integer(r, "previous_agent_version"), r.getString("user_id"),
                r.getString("origin"), r.getObject("day", LocalDate.class), recorded == null ? null : Instant.ofEpochMilli(recorded),
                updated == null ? null : Instant.ofEpochMilli(updated), decimal(r, "cost_usd"), decimal(r, "discarded_cost_usd"),
                longValue(r, "input_tokens"), longValue(r, "output_tokens"), longValue(r, "total_tokens"), longValue(r, "duration_ms"),
                r.getString("error_kind"), r.getBoolean("paused"), failuresOf(r.getString("failures")), r.getBoolean("fallback_served"),
                r.getInt("tool_calls"), r.getInt("tool_failures"), r.getInt("tool_refusals"), r.getInt("guardrail_trips"), r.getBoolean("user_input"),
                r.getBoolean("agent_output"), r.getBoolean("last_output_question"), actions, r.getBoolean("discarded"));
    }

    private static Integer integer(ResultSet r, String column) throws SQLException {
        int value = r.getInt(column);
        return r.wasNull() ? null : value;
    }

    private static Long longValue(ResultSet r, String column) throws SQLException {
        long value = r.getLong(column);
        return r.wasNull() ? null : value;
    }

    private static Double decimal(ResultSet r, String column) throws SQLException {
        double value = r.getDouble(column);
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

    private static void setDouble(PreparedStatement s, int index, Double value) throws SQLException {
        if (value == null || !Double.isFinite(value)) {
            s.setNull(index, Types.DOUBLE);
        } else {
            s.setDouble(index, value);
        }
    }
}
