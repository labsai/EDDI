/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.postgres;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthOutcomeStore;
import ai.labs.eddi.engine.health.model.HealthFeedCursor;
import ai.labs.eddi.engine.health.model.HealthOutcome;
import ai.labs.eddi.engine.health.model.HealthOutcomeCount;
import ai.labs.eddi.engine.health.model.HealthPortfolioSessions;
import ai.labs.eddi.engine.health.model.HealthSessionQuery;
import ai.labs.eddi.engine.health.model.HealthUsageQuery;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * PostgreSQL implementation of {@link IHealthOutcomeStore}: table
 * {@code health_outcomes}, primary key {@code (conversation_id, session_no)},
 * written by {@code INSERT ... ON CONFLICT DO UPDATE} of every column. The
 * incremental feed's cursor is two rows of {@code health_rollup_state}: the
 * forward position in {@value #WATERMARK} ({@code at_ms}, {@code ref} the last
 * conversation read at it) and the overlap replay's in {@value #REPLAY}, absent
 * when none is pending — written together in one transaction. The schema is
 * created on first use, never at construction: on a MongoDB deployment the
 * datasource bean is inactive.
 */
@ApplicationScoped
@DefaultBean
public class PostgresHealthOutcomeStore implements IHealthOutcomeStore {

    static final String TABLE = "health_outcomes";
    static final String WATERMARK = "outcomes";
    static final String REPLAY = "outcomes.replay";

    static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS health_outcomes (
                conversation_id VARCHAR(255) NOT NULL,
                session_no INTEGER NOT NULL,
                agent_id VARCHAR(255),
                agent_version INTEGER,
                user_id VARCHAR(512),
                origin VARCHAR(32),
                engaged BOOLEAN NOT NULL DEFAULT FALSE,
                started_at_ms BIGINT,
                ended_at_ms BIGINT,
                turns INTEGER NOT NULL DEFAULT 0,
                cost_usd DOUBLE PRECISION NOT NULL DEFAULT 0,
                failures INTEGER NOT NULL DEFAULT 0,
                outcome VARCHAR(32),
                evidence VARCHAR(32),
                provisional BOOLEAN NOT NULL DEFAULT FALSE,
                day DATE,
                due_at_ms BIGINT,
                first_output_step INTEGER,
                last_actions TEXT[],
                evaluated_at_ms BIGINT,
                PRIMARY KEY (conversation_id, session_no)
            )
            """;

    static final String[] CREATE_INDEXES = {"CREATE INDEX IF NOT EXISTS idx_health_outcomes_agent_day ON health_outcomes (agent_id, day)",
            "CREATE INDEX IF NOT EXISTS idx_health_outcomes_user ON health_outcomes (user_id)",
            "CREATE INDEX IF NOT EXISTS idx_health_outcomes_day ON health_outcomes (day)",
            "CREATE INDEX IF NOT EXISTS idx_health_outcomes_due ON health_outcomes (due_at_ms) WHERE due_at_ms IS NOT NULL",
            "CREATE INDEX IF NOT EXISTS idx_health_outcomes_evaluated ON health_outcomes (evaluated_at_ms)"};

    /** The rollup's state table, shared with the dirty-day store. */
    static final String CREATE_STATE = "CREATE TABLE IF NOT EXISTS health_rollup_state (name VARCHAR(64) PRIMARY KEY, at_ms BIGINT NOT NULL)";
    /**
     * The conversation a feed position was read up to, sized like
     * {@code conversation_id}; nullable, so the dirty-day store's rows, which never
     * set it, are unaffected — whichever store creates the table first.
     */
    static final String ADD_STATE_REF_COLUMN = "ALTER TABLE health_rollup_state ADD COLUMN IF NOT EXISTS ref VARCHAR(255)";

    static final List<String> COLUMNS = List.of("conversation_id", "session_no", "agent_id", "agent_version", "user_id", "origin", "engaged",
            "started_at_ms", "ended_at_ms", "turns", "cost_usd", "failures", "outcome", "evidence", "provisional", "day", "due_at_ms",
            "first_output_step", "last_actions", "evaluated_at_ms");

    static final String UPSERT = "INSERT INTO health_outcomes (" + String.join(", ", COLUMNS) + ") VALUES ("
            + COLUMNS.stream().map(c -> "?").collect(Collectors.joining(", ")) + ") ON CONFLICT (conversation_id, session_no) DO UPDATE SET "
            + COLUMNS.stream().filter(c -> !c.equals("conversation_id") && !c.equals("session_no") && !c.equals("due_at_ms"))
                    .map(c -> c + " = EXCLUDED." + c).collect(Collectors.joining(", "))
            // A mark set at or after this evaluation started is kept, as the earlier time.
            + ", due_at_ms = CASE WHEN health_outcomes.due_at_ms IS NOT NULL AND health_outcomes.due_at_ms >= EXCLUDED.evaluated_at_ms "
            + "AND health_outcomes.due_at_ms <= EXCLUDED.evaluated_at_ms + " + MARK_WINDOW_MS + " "
            + "THEN LEAST(health_outcomes.due_at_ms, COALESCE(EXCLUDED.due_at_ms, health_outcomes.due_at_ms)) ELSE EXCLUDED.due_at_ms END";

    private static final String SELECT = "SELECT " + String.join(", ", COLUMNS) + " FROM health_outcomes ";

    static final String BY_CONVERSATION = SELECT + "WHERE conversation_id = ? ORDER BY session_no";
    static final String DUE = SELECT + "WHERE due_at_ms IS NOT NULL AND due_at_ms <= ? ORDER BY due_at_ms LIMIT ?";
    static final String MARK_DUE = "UPDATE health_outcomes SET due_at_ms = ? WHERE conversation_id = ?";
    static final String MARK_DUE_SESSIONS = MARK_DUE + " AND session_no = ANY(?)";
    static final String BY_USER = SELECT + "WHERE user_id = ? ORDER BY ended_at_ms DESC LIMIT ?";
    static final String EVALUATED_SINCE = "SELECT DISTINCT conversation_id FROM health_outcomes WHERE evaluated_at_ms >= ? LIMIT ?";
    static final String READ_CURSOR = "SELECT name, at_ms, ref FROM health_rollup_state WHERE name IN (?, ?)";
    static final String WRITE_CURSOR = "INSERT INTO health_rollup_state (name, at_ms, ref) VALUES (?, ?, ?) ON CONFLICT (name) DO UPDATE SET "
            + "at_ms = EXCLUDED.at_ms, ref = EXCLUDED.ref";
    static final String DELETE_CURSOR = "DELETE FROM health_rollup_state WHERE name = ?";

    /**
     * Binds agent, from, to, the origins array and, with a version, the version.
     */
    static String scopeWhere(boolean withVersion) {
        return "agent_id = ? AND day >= ? AND day <= ? AND origin = ANY(?)" + (withVersion ? " AND agent_version = ?" : "");
    }

    static String countsSql(boolean withVersion, boolean perDay, String groupBy) {
        String group = switch (groupBy == null ? "none" : groupBy) {
            case "version" -> "CAST(agent_version AS TEXT)";
            case "origin" -> "origin";
            default -> "CAST(NULL AS TEXT)";
        };
        String day = perDay ? "day" : "CAST(NULL AS DATE)";
        return "SELECT outcome, evidence, engaged, provisional, " + day + " AS d, " + group + " AS g, COUNT(*), COALESCE(SUM(turns), 0) "
                + "FROM health_outcomes WHERE " + scopeWhere(withVersion) + " GROUP BY outcome, evidence, engaged, provisional, d, g";
    }

    static String endingActionsSql(boolean withVersion) {
        return "SELECT a, COUNT(*) FROM (SELECT DISTINCT conversation_id, session_no, UNNEST(last_actions) AS a FROM health_outcomes WHERE "
                + scopeWhere(withVersion) + " AND engaged AND NOT provisional AND outcome IS NOT NULL) x GROUP BY a";
    }

    static String sessionsSql(boolean withOutcomes, boolean withEvidence) {
        return SELECT + "WHERE agent_id = ? AND day >= ? AND day <= ? AND agent_version = ANY(?) AND origin = ANY(?)"
                + (withOutcomes ? " AND outcome = ANY(?)" : "") + (withEvidence ? " AND evidence = ANY(?)" : "")
                + " ORDER BY ended_at_ms DESC, conversation_id, session_no OFFSET ? LIMIT ?";
    }

    /**
     * Binds the window's first day and the interactive origins in the select list,
     * then agents, baseline start, last day and all origins.
     */
    static final String PORTFOLIO_SESSIONS = "SELECT agent_id, COUNT(*) FILTER (WHERE day >= ? AND origin = ANY(?)), "
            + "COUNT(*) FILTER (WHERE day >= ?), COUNT(*) FILTER (WHERE day < ? AND origin = ANY(?)), COUNT(*) FILTER (WHERE day < ?) "
            + "FROM health_outcomes WHERE agent_id = ANY(?) AND day >= ? AND day <= ? AND origin = ANY(?) GROUP BY agent_id";

    private final Instance<DataSource> dataSourceInstance;
    private volatile boolean schemaInitialized;

    @Inject
    public PostgresHealthOutcomeStore(Instance<DataSource> dataSourceInstance) {
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
            statement.execute(CREATE_STATE);
            statement.execute(ADD_STATE_REF_COLUMN);
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
    public void upsert(Collection<HealthOutcome> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        try (Connection connection = connection(); PreparedStatement s = connection.prepareStatement(UPSERT)) {
            for (HealthOutcome row : rows) {
                bind(connection, s, row);
                s.addBatch();
            }
            s.executeBatch();
        } catch (SQLException e) {
            throw new HealthStoreException("Could not store health outcomes", e);
        }
    }

    static void bind(Connection connection, PreparedStatement s, HealthOutcome o) throws SQLException {
        int i = 1;
        s.setString(i++, o.conversationId());
        s.setInt(i++, o.sessionNo());
        s.setString(i++, o.agentId());
        setInteger(s, i++, o.agentVersion());
        s.setString(i++, o.userId());
        s.setString(i++, o.origin());
        s.setBoolean(i++, o.engaged());
        setLong(s, i++, millis(o.startedAt()));
        setLong(s, i++, millis(o.endedAt()));
        s.setInt(i++, o.turns());
        s.setDouble(i++, Double.isFinite(o.costUsd()) ? o.costUsd() : 0.0);
        s.setInt(i++, o.failures());
        s.setString(i++, o.outcome());
        s.setString(i++, o.evidence());
        s.setBoolean(i++, o.provisional());
        s.setObject(i++, o.day());
        setLong(s, i++, millis(o.dueAt()));
        setInteger(s, i++, o.firstOutputStep());
        s.setArray(i++, connection.createArrayOf("text", o.lastActions().toArray(new String[0])));
        setLong(s, i, millis(o.evaluatedAt()));
    }

    @Override
    public List<HealthOutcome> findByConversation(String conversationId) {
        return query(BY_CONVERSATION, "Could not read the health outcomes of a conversation", (c, s) -> s.setString(1, conversationId));
    }

    @Override
    public long deleteSessions(String conversationId, Collection<Integer> sessionNos) {
        if (conversationId == null || sessionNos == null || sessionNos.isEmpty()) {
            return 0L;
        }
        return update("DELETE FROM health_outcomes WHERE conversation_id = ? AND session_no = ANY(?)", "Could not delete health outcomes", (c, s) -> {
            s.setString(1, conversationId);
            s.setArray(2, c.createArrayOf("integer", new LinkedHashSet<>(sessionNos).toArray(new Integer[0])));
        });
    }

    @Override
    public List<HealthOutcome> findDue(Instant now, int limit) {
        return query(DUE, "Could not read the due health outcomes", (c, s) -> {
            s.setLong(1, now.toEpochMilli());
            s.setInt(2, Math.max(1, limit));
        });
    }

    @Override
    public long markDue(String conversationId, Collection<Integer> sessionNos, Instant at) {
        if (sessionNos != null && sessionNos.isEmpty()) {
            return 0L;
        }
        return update(sessionNos == null ? MARK_DUE : MARK_DUE_SESSIONS, "Could not mark health outcomes due", (c, s) -> {
            s.setLong(1, at.toEpochMilli());
            s.setString(2, conversationId);
            if (sessionNos != null) {
                s.setArray(3, c.createArrayOf("integer", new LinkedHashSet<>(sessionNos).toArray(new Integer[0])));
            }
        });
    }

    private static int bindScope(Connection connection, PreparedStatement s, HealthUsageQuery query, int start) throws SQLException {
        int i = start;
        s.setString(i++, query.agentId());
        s.setObject(i++, query.from());
        s.setObject(i++, query.to());
        s.setArray(i++, connection.createArrayOf("text", query.origins().toArray(new String[0])));
        if (query.version() != null) {
            s.setInt(i++, query.version());
        }
        return i;
    }

    @Override
    public List<HealthOutcomeCount> counts(HealthUsageQuery query, boolean perDay, String groupBy) {
        if (query.origins().isEmpty()) {
            return List.of();
        }
        try (Connection connection = connection();
                PreparedStatement s = connection.prepareStatement(countsSql(query.version() != null, perDay, groupBy))) {
            bindScope(connection, s, query, 1);
            List<HealthOutcomeCount> counts = new ArrayList<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    counts.add(
                            new HealthOutcomeCount(r.getObject(5, LocalDate.class), r.getString(6), r.getString(1), r.getString(2), r.getBoolean(3),
                                    r.getBoolean(4), r.getLong(7), r.getLong(8)));
                }
            }
            return counts;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not count the health outcomes of an agent", e);
        }
    }

    @Override
    public List<HealthOutcome> sessions(HealthSessionQuery query) {
        if (query.origins().isEmpty() || query.versions().isEmpty()) {
            return List.of();
        }
        boolean withOutcomes = !query.outcomes().isEmpty();
        boolean withEvidence = !query.evidence().isEmpty();
        return query(sessionsSql(withOutcomes, withEvidence), "Could not read the health sessions of an agent", (c, s) -> {
            int i = 1;
            s.setString(i++, query.agentId());
            s.setObject(i++, query.from());
            s.setObject(i++, query.to());
            s.setArray(i++, c.createArrayOf("integer", query.versions().toArray(new Integer[0])));
            s.setArray(i++, c.createArrayOf("text", query.origins().toArray(new String[0])));
            if (withOutcomes) {
                s.setArray(i++, c.createArrayOf("text", query.outcomes().toArray(new String[0])));
            }
            if (withEvidence) {
                s.setArray(i++, c.createArrayOf("text", query.evidence().toArray(new String[0])));
            }
            s.setInt(i++, Math.max(0, query.offset()));
            s.setInt(i, Math.max(1, query.limit()));
        });
    }

    @Override
    public Map<String, Long> endingActions(HealthUsageQuery query) {
        if (query.origins().isEmpty()) {
            return Map.of();
        }
        try (Connection connection = connection(); PreparedStatement s = connection.prepareStatement(endingActionsSql(query.version() != null))) {
            bindScope(connection, s, query, 1);
            Map<String, Long> counts = new LinkedHashMap<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (r.getString(1) != null) {
                        counts.put(r.getString(1), r.getLong(2));
                    }
                }
            }
            return counts;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not count the ending actions of an agent", e);
        }
    }

    @Override
    public Map<String, HealthPortfolioSessions> portfolioSessions(Collection<String> agentIds, LocalDate baselineFrom, LocalDate windowFrom,
                                                                  LocalDate to, Collection<String> interactive, Collection<String> origins) {
        if (agentIds == null || agentIds.isEmpty() || origins == null || origins.isEmpty()) {
            return Map.of();
        }
        try (Connection connection = connection(); PreparedStatement s = connection.prepareStatement(PORTFOLIO_SESSIONS)) {
            Array interactiveArray = connection.createArrayOf("text", (interactive == null ? List.<String>of() : interactive).toArray(new String[0]));
            s.setObject(1, windowFrom);
            s.setArray(2, interactiveArray);
            s.setObject(3, windowFrom);
            s.setObject(4, windowFrom);
            s.setArray(5, interactiveArray);
            s.setObject(6, windowFrom);
            s.setArray(7, connection.createArrayOf("text", agentIds.toArray(new String[0])));
            s.setObject(8, baselineFrom);
            s.setObject(9, to);
            s.setArray(10, connection.createArrayOf("text", origins.toArray(new String[0])));
            Map<String, HealthPortfolioSessions> sessions = new LinkedHashMap<>();
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    sessions.put(r.getString(1), new HealthPortfolioSessions(r.getLong(2), r.getLong(3), r.getLong(4), r.getLong(5)));
                }
            }
            return sessions;
        } catch (SQLException e) {
            throw new HealthStoreException("Could not count the sessions of agents", e);
        }
    }

    @Override
    public List<HealthOutcome> findByUserId(String userId, int limit) {
        return query(BY_USER, "Could not read a user's health outcomes", (c, s) -> {
            s.setString(1, userId);
            s.setInt(2, Math.max(1, limit));
        });
    }

    @Override
    public long deleteByUserId(String userId) {
        return update("DELETE FROM health_outcomes WHERE user_id = ?", "Could not delete a user's health outcomes", (c, s) -> s.setString(1, userId));
    }

    @Override
    public long deleteByConversationId(String conversationId) {
        return update("DELETE FROM health_outcomes WHERE conversation_id = ?", "Could not delete a conversation's health outcomes",
                (c, s) -> s.setString(1, conversationId));
    }

    @Override
    public void forEachConversationEvaluatedSince(Instant since, int limit, Consumer<String> consumer) {
        try (Connection connection = connection(); PreparedStatement s = connection.prepareStatement(EVALUATED_SINCE)) {
            s.setLong(1, since.toEpochMilli());
            s.setInt(2, Math.max(1, limit));
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    consumer.accept(r.getString(1));
                }
            }
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read the conversations of recent health outcomes", e);
        }
    }

    @Override
    public long deleteOlderThan(LocalDate day) {
        return update("DELETE FROM health_outcomes WHERE day < ?", "Could not delete expired health outcomes", (c, s) -> s.setObject(1, day));
    }

    @Override
    public Optional<HealthFeedCursor> feedCursor() {
        try (Connection connection = connection(); PreparedStatement s = connection.prepareStatement(READ_CURSOR)) {
            s.setString(1, WATERMARK);
            s.setString(2, REPLAY);
            Instant watermark = null;
            String afterId = null;
            Instant replayAt = null;
            String replayAfterId = null;
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    if (WATERMARK.equals(r.getString(1))) {
                        watermark = Instant.ofEpochMilli(r.getLong(2));
                        afterId = r.getString(3);
                    } else {
                        replayAt = Instant.ofEpochMilli(r.getLong(2));
                        replayAfterId = r.getString(3);
                    }
                }
            }
            return watermark == null ? Optional.empty() : Optional.of(new HealthFeedCursor(watermark, afterId, replayAt, replayAfterId));
        } catch (SQLException e) {
            throw new HealthStoreException("Could not read the health outcome feed cursor", e);
        }
    }

    @Override
    public void setFeedCursor(HealthFeedCursor cursor) {
        try (Connection connection = connection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement s = connection.prepareStatement(WRITE_CURSOR)) {
                    s.setString(1, WATERMARK);
                    s.setLong(2, cursor.watermark().toEpochMilli());
                    s.setString(3, cursor.afterId());
                    s.executeUpdate();
                }
                if (cursor.replayAt() == null) {
                    try (PreparedStatement s = connection.prepareStatement(DELETE_CURSOR)) {
                        s.setString(1, REPLAY);
                        s.executeUpdate();
                    }
                } else {
                    try (PreparedStatement s = connection.prepareStatement(WRITE_CURSOR)) {
                        s.setString(1, REPLAY);
                        s.setLong(2, cursor.replayAt().toEpochMilli());
                        s.setString(3, cursor.replayAfterId());
                        s.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (SQLException e) {
            throw new HealthStoreException("Could not store the health outcome feed cursor", e);
        }
    }

    @FunctionalInterface
    private interface Binder {
        void bind(Connection connection, PreparedStatement statement) throws SQLException;
    }

    private List<HealthOutcome> query(String sql, String failure, Binder binder) {
        try (Connection connection = connection(); PreparedStatement s = connection.prepareStatement(sql)) {
            binder.bind(connection, s);
            List<HealthOutcome> found = new ArrayList<>();
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
            binder.bind(connection, s);
            return s.executeUpdate();
        } catch (SQLException e) {
            throw new HealthStoreException(failure, e);
        }
    }

    static HealthOutcome read(ResultSet r) throws SQLException {
        List<String> actions = new ArrayList<>();
        Array array = r.getArray("last_actions");
        if (array != null && array.getArray() instanceof Object[] values) {
            for (Object value : values) {
                if (value != null) {
                    actions.add(String.valueOf(value));
                }
            }
        }
        return new HealthOutcome(r.getString("conversation_id"), r.getInt("session_no"), r.getString("agent_id"), integer(r, "agent_version"),
                r.getString("user_id"), r.getString("origin"), r.getBoolean("engaged"), instant(r, "started_at_ms"), instant(r, "ended_at_ms"),
                r.getInt("turns"), r.getDouble("cost_usd"), r.getInt("failures"), r.getString("outcome"), r.getString("evidence"),
                r.getBoolean("provisional"), r.getObject("day", LocalDate.class), instant(r, "due_at_ms"), integer(r, "first_output_step"), actions,
                instant(r, "evaluated_at_ms"));
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
