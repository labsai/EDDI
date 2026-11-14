/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health.postgres;

import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.health.model.HealthTurn;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PostgresHealthTurnStore — SQL shape and binding")
class PostgresHealthTurnStoreTest {

    private static HealthTurn turn() {
        return new HealthTurn("conv", 3, "exec", "agent", 2, null, "user", "api", LocalDate.of(2026, 11, 10), Instant.parse("2026-11-10T10:00:00Z"),
                Instant.parse("2026-11-10T11:00:00Z"), 0.25, null, 10L, null, 15L, 900L, null, false,
                List.of(new HealthFailure("TIMEOUT", null, "retry")),
                false, 2, 1, 0, 0, true, true, false, List.of("greet"), false);
    }

    private static long placeholders(String sql) {
        return sql.chars().filter(c -> c == '?').count();
    }

    @Test
    @DisplayName("the self_reported_outcome check looks at the health_turns the search path resolves, not a namesake in another schema")
    void selfReportedColumnOnResolvedTable() {
        String sql = PostgresHealthTurnStore.HAS_SELF_REPORTED_COLUMN;
        assertTrue(sql.contains("attrelid = to_regclass('health_turns')"), sql);
        assertFalse(sql.contains("information_schema"), sql);
    }

    @Test
    @DisplayName("the feed after a (time, conversation) pair binds since twice more and the id, in HAVING, keeping the order")
    void feedAfterPair() {
        String sql = PostgresHealthTurnStore.CONVERSATIONS_UPDATED_AFTER;
        assertEquals(placeholders(PostgresHealthTurnStore.CONVERSATIONS_UPDATED_BETWEEN) + 3, placeholders(sql));
        assertTrue(sql.contains("HAVING MAX(updated_at_ms) > ? OR (MAX(updated_at_ms) = ? AND conversation_id > ?)"), sql);
        assertTrue(sql.endsWith("ORDER BY last, conversation_id LIMIT ?"), sql);
    }

    @Test
    @DisplayName("the upsert binds every column once, replaces on conflict, keeps the first day, guards on updated_at, discard sticky")
    void upsertShape() {
        String sql = PostgresHealthTurnStore.UPSERT;
        assertEquals(31, PostgresHealthTurnStore.WRITTEN.size(), "H3 added self_reported_outcome");
        // Every column once, then the late-write stamp.
        assertEquals(PostgresHealthTurnStore.WRITTEN.size() + 1, placeholders(sql));
        assertTrue(sql.contains("ON CONFLICT (conversation_id, execution_id) DO UPDATE SET"));
        assertTrue(sql.contains("discarded = health_turns.discarded OR EXCLUDED.discarded"));
        assertTrue(sql.contains("day = COALESCE(health_turns.day, EXCLUDED.day)"));
        assertTrue(sql.contains("recorded_at_ms = COALESCE(health_turns.recorded_at_ms, EXCLUDED.recorded_at_ms)"));
        assertTrue(sql.endsWith("WHERE health_turns.updated_at_ms IS NULL OR health_turns.updated_at_ms <= EXCLUDED.updated_at_ms"));
        assertFalse(sql.contains("day = EXCLUDED.day"), "a replace must not move the row to another day");
        assertFalse(sql.matches("(?s).*=\\s*health_turns\\.\\w+\\s*\\+.*"), "never an increment");
        for (String column : PostgresHealthTurnStore.WRITTEN) {
            if (!List.of("conversation_id", "execution_id", "day", "recorded_at_ms", "discarded").contains(column)) {
                assertTrue(sql.contains(column + " = EXCLUDED." + column), column + " is not replaced on conflict");
            }
        }
        assertTrue(PostgresHealthTurnStore.INSERT_IF_ABSENT.endsWith("DO NOTHING"));
        assertTrue(PostgresHealthTurnStore.CREATE_TABLE.contains("PRIMARY KEY (conversation_id, execution_id)"));
    }

    @Test
    @DisplayName("bind sets all 30 parameters in column order, nulls as typed nulls, the day as a LocalDate")
    void bind() throws Exception {
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        Array array = mock(Array.class);
        when(connection.createArrayOf(eq("text"), any())).thenReturn(array);

        PostgresHealthTurnStore.bind(connection, statement, turn());

        verify(statement).setString(1, "conv");
        verify(statement).setString(2, "exec");
        verify(statement).setInt(3, 3);
        verify(statement).setNull(6, Types.INTEGER);
        verify(statement).setObject(9, LocalDate.of(2026, 11, 10));
        verify(statement).setLong(10, Instant.parse("2026-11-10T10:00:00Z").toEpochMilli());
        verify(statement).setLong(11, Instant.parse("2026-11-10T11:00:00Z").toEpochMilli());
        verify(statement).setDouble(12, 0.25);
        verify(statement).setNull(13, Types.DOUBLE);
        verify(statement).setNull(15, Types.BIGINT);
        verify(statement).setString(18, null);
        verify(statement).setString(eq(20), anyString());
        verify(statement).setInt(24, 0);
        verify(statement).setArray(29, array);
        verify(statement).setBoolean(30, false);
    }

    @Test
    @DisplayName("the failure list survives its JSON column; junk reads as no failures")
    void failuresJson() throws Exception {
        List<HealthFailure> failures = List.of(new HealthFailure("TIMEOUT", "m", "cascade"), new HealthFailure("AUTH", null, "none"));
        assertEquals(failures, PostgresHealthTurnStore.failuresOf(PostgresHealthTurnStore.failuresJson(failures)));
        assertEquals(List.of(), PostgresHealthTurnStore.failuresOf("not json"));
        assertEquals(List.of(), PostgresHealthTurnStore.failuresOf(null));
    }

    @Test
    @DisplayName("reads skip discard markers; markers are sticky; retention takes old days and old markers")
    void queries() {
        assertTrue(PostgresHealthTurnStore.BY_AGENT_DAYS.contains("WHERE agent_id = ? AND day >= ? AND day <= ?"));
        assertTrue(PostgresHealthTurnStore.BY_AGENT_DAY.contains("WHERE agent_id = ? AND day = ?"));
        assertTrue(PostgresHealthTurnStore.BY_CONVERSATION.contains("recorded_at_ms IS NOT NULL"));
        assertTrue(PostgresHealthTurnStore.BY_USER.contains("recorded_at_ms IS NOT NULL"));
        assertTrue(PostgresHealthTurnStore.MARK_DISCARDED.contains("DO UPDATE SET discarded = TRUE"));
        // The discard time is set once, when the row flips, and updated_at_ms is left
        // alone.
        assertTrue(PostgresHealthTurnStore.MARK_DISCARDED.contains("discarded_at_ms = CASE WHEN health_turns.discarded "
                + "THEN COALESCE(health_turns.discarded_at_ms, EXCLUDED.discarded_at_ms) ELSE EXCLUDED.discarded_at_ms END"));
        assertFalse(PostgresHealthTurnStore.MARK_DISCARDED.contains("updated_at_ms"));
        assertTrue(PostgresHealthTurnStore.AGENT_DAYS_UPDATED_SINCE
                .contains("((updated_at_ms >= ? AND updated_at_ms < ?) OR (discarded_at_ms >= ? AND discarded_at_ms < ?) OR "));
        assertTrue(String.join(" ", PostgresHealthTurnStore.CREATE_INDEXES).contains("idx_health_turns_discarded_rows"));
        assertTrue(PostgresHealthTurnStore.DELETE_OLDER.contains("day < ? OR (day IS NULL AND discarded_at_ms < ?)"));
        assertTrue(String.join(" ", PostgresHealthTurnStore.CREATE_INDEXES).contains("(agent_id, day)"));
        assertTrue(PostgresHealthTurnStore.CONVERSATIONS_UPDATED_SINCE
                .contains("SELECT DISTINCT conversation_id FROM health_turns WHERE updated_at_ms >= ? OR (day IS NULL AND discarded_at_ms >= ?)"));
        assertTrue(PostgresHealthTurnStore.CONVERSATIONS_UPDATED_SINCE.endsWith("LIMIT ?"));
        assertTrue(String.join(" ", PostgresHealthTurnStore.CREATE_INDEXES)
                .contains("CREATE INDEX IF NOT EXISTS idx_health_turns_updated ON health_turns (updated_at_ms)"));
    }

    @Test
    @DisplayName("an unreachable database is a HealthStoreException, and the schema is retried on the next call")
    @SuppressWarnings("unchecked")
    void failures() throws Exception {
        Instance<DataSource> instance = mock(Instance.class);
        DataSource dataSource = mock(DataSource.class);
        when(instance.get()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));
        PostgresHealthTurnStore store = new PostgresHealthTurnStore(instance);
        assertThrows(HealthStoreException.class, () -> store.upsert(turn()));
        assertThrows(HealthStoreException.class, () -> store.insertIfAbsent(turn()));
        assertThrows(HealthStoreException.class, () -> store.deleteByUserId("u"));
        assertThrows(HealthStoreException.class, () -> store.forEachConversationUpdatedSince(Instant.now(), 10, id -> {
        }));
        assertEquals(0L, store.markDiscarded(turn(), List.of()));
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the orphan sweep binds the instant twice and the limit, streams in a transaction, and restores auto-commit")
    void forEachConversationUpdatedSince() throws Exception {
        Instance<DataSource> instance = mock(Instance.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(instance.get()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.prepareStatement(PostgresHealthTurnStore.CONVERSATIONS_UPDATED_SINCE)).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn("c1", "c2");

        List<String> ids = new ArrayList<>();
        Instant since = Instant.parse("2026-11-08T00:00:00Z");
        new PostgresHealthTurnStore(instance).forEachConversationUpdatedSince(since, 0, ids::add);

        assertEquals(List.of("c1", "c2"), ids);
        verify(statement).setLong(1, since.toEpochMilli());
        verify(statement).setLong(2, since.toEpochMilli());
        verify(statement).setInt(3, 1);
        verify(statement).setFetchSize(PostgresHealthTurnStore.STREAM_FETCH_SIZE);
        verify(connection).setAutoCommit(false);
        verify(connection).commit();
        verify(connection).setAutoCommit(true);
    }

    @Test
    @DisplayName("a late write stamps written_at_ms in the same statement, an on-time one clears it, and the self-heal matches it")
    void lateWriteStamp() {
        assertTrue(PostgresHealthTurnStore.UPSERT.contains(", written_at_ms) VALUES ("));
        assertTrue(PostgresHealthTurnStore.UPSERT.contains("written_at_ms = EXCLUDED.written_at_ms"), "every applied write replaces the stamp");
        assertTrue(PostgresHealthTurnStore.INSERT_IF_ABSENT.contains(", written_at_ms) VALUES ("));
        assertEquals(PostgresHealthTurnStore.WRITTEN.size() + 1, placeholders(PostgresHealthTurnStore.INSERT_IF_ABSENT));
        assertTrue(PostgresHealthTurnStore.AGENT_DAYS_UPDATED_SINCE.contains("OR (written_at_ms >= ? AND written_at_ms < ?))"));
        assertEquals(7, placeholders(PostgresHealthTurnStore.AGENT_DAYS_UPDATED_SINCE));
        assertTrue(PostgresHealthTurnStore.CREATE_TABLE.contains("written_at_ms BIGINT"));
        assertEquals(List.of("ALTER TABLE health_turns ADD COLUMN IF NOT EXISTS written_at_ms BIGINT"),
                List.of(PostgresHealthTurnStore.ADD_COLUMNS));
        assertTrue(String.join(" ", PostgresHealthTurnStore.CREATE_INDEXES)
                .contains("idx_health_turns_written_late ON health_turns (written_at_ms) WHERE written_at_ms IS NOT NULL"), "late rows only");
    }

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("the stamp is bound after the row's columns, the discard time is the late write's, the self-heal binds both windows")
    void lateWriteBinding() throws Exception {
        Instance<DataSource> instance = mock(Instance.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement ddl = mock(Statement.class);
        PreparedStatement upsert = mock(PreparedStatement.class);
        PreparedStatement insert = mock(PreparedStatement.class);
        PreparedStatement discard = mock(PreparedStatement.class);
        PreparedStatement selfHeal = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(instance.get()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(ddl);
        when(connection.createArrayOf(eq("text"), any())).thenReturn(mock(Array.class));
        when(connection.prepareStatement(PostgresHealthTurnStore.UPSERT)).thenReturn(upsert);
        when(connection.prepareStatement(PostgresHealthTurnStore.INSERT_IF_ABSENT)).thenReturn(insert);
        when(connection.prepareStatement(PostgresHealthTurnStore.MARK_DISCARDED)).thenReturn(discard);
        when(connection.prepareStatement(PostgresHealthTurnStore.AGENT_DAYS_UPDATED_SINCE)).thenReturn(selfHeal);
        when(selfHeal.executeQuery()).thenReturn(rows);
        PostgresHealthTurnStore store = new PostgresHealthTurnStore(instance);
        Instant written = Instant.parse("2026-11-10T11:05:00Z");

        store.upsert(turn(), written);
        verify(upsert).setLong(PostgresHealthTurnStore.WRITTEN.size() + 1, written.toEpochMilli());
        store.upsert(turn());
        verify(upsert).setNull(PostgresHealthTurnStore.WRITTEN.size() + 1, Types.BIGINT);
        store.insertIfAbsent(turn(), written);
        verify(insert).setLong(PostgresHealthTurnStore.WRITTEN.size() + 1, written.toEpochMilli());
        verify(ddl).execute("ALTER TABLE health_turns ADD COLUMN IF NOT EXISTS written_at_ms BIGINT");

        store.markDiscarded(turn(), List.of("old"), written);
        verify(discard).setLong(5, written.toEpochMilli());
        store.markDiscarded(turn(), List.of("old"));
        verify(discard).setLong(5, turn().updatedAt().toEpochMilli());

        Instant since = Instant.parse("2026-11-10T11:00:00Z");
        Instant until = Instant.parse("2026-11-10T11:10:00Z");
        store.forEachAgentDayUpdatedSince(since, until, 10, (a, d) -> {
        });
        for (int i : new int[]{1, 3, 5}) {
            verify(selfHeal).setLong(i, since.toEpochMilli());
            verify(selfHeal).setLong(i + 1, until.toEpochMilli());
        }
        verify(selfHeal).setInt(7, 10);
    }
}