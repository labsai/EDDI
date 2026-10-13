/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.model.LogEntry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.sql.*;
import java.util.List;
import org.mockito.ArgumentCaptor;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PostgresDatabaseLogsUnitTest {

    @Mock
    private Instance<DataSource> dataSourceInstance;
    @Mock
    private DataSource dataSource;
    @Mock
    private Connection connection;
    @Mock
    private Statement statement;
    @Mock
    private PreparedStatement preparedStatement;
    @Mock
    private ResultSet resultSet;

    private PostgresDatabaseLogs databaseLogs;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        databaseLogs = new PostgresDatabaseLogs(dataSourceInstance);
    }

    // ─── getLogs ───

    @Test
    void getLogs_allFilters() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        Timestamp ts = new Timestamp(System.currentTimeMillis());
        when(resultSet.getTimestamp("timestamp")).thenReturn(ts);
        when(resultSet.getString("level")).thenReturn("INFO");
        when(resultSet.getString("logger_name")).thenReturn("ai.labs.test");
        when(resultSet.getString("message")).thenReturn("test message");
        when(resultSet.getString("environment")).thenReturn("production");
        when(resultSet.getString("AGENT_ID")).thenReturn("agent-1");
        when(resultSet.getObject("AGENT_VERSION")).thenReturn(1);
        when(resultSet.getString("conversation_id")).thenReturn("conv-1");
        when(resultSet.getString("user_id")).thenReturn("user-1");
        when(resultSet.getString("instance_id")).thenReturn("inst-1");

        List<LogEntry> logs = databaseLogs.getLogs(Environment.production, "agent-1", 1, "conv-1", "user-1", "inst-1", 0, 10);

        assertEquals(1, logs.size());
        assertEquals("INFO", logs.get(0).level());
        assertEquals("test message", logs.get(0).message());
    }

    @Test
    void getLogs_noFilters() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        List<LogEntry> logs = databaseLogs.getLogs(null, null, null, null, null, null, null, null);
        assertTrue(logs.isEmpty());
    }

    @Test
    void getLogs_nullEnvironment() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        List<LogEntry> logs = databaseLogs.getLogs(null, "agent-1", 1, null, null, null, 0, 10);
        assertTrue(logs.isEmpty());
    }

    @Test
    void getLogs_sqlException_returnsEmptyList() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));

        List<LogEntry> logs = databaseLogs.getLogs(Environment.production, "agent-1", 1, null, null, null, 0, 10);
        assertTrue(logs.isEmpty());
    }

    @Test
    void getLogs_nullTimestamp_returnsZero() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getTimestamp("timestamp")).thenReturn(null);
        when(resultSet.getString("level")).thenReturn("WARN");
        when(resultSet.getString("logger_name")).thenReturn("logger");
        when(resultSet.getString("message")).thenReturn("msg");
        when(resultSet.getString("environment")).thenReturn("test");
        when(resultSet.getString("AGENT_ID")).thenReturn("a");
        when(resultSet.getObject("AGENT_VERSION")).thenReturn(null);
        when(resultSet.getString("conversation_id")).thenReturn(null);
        when(resultSet.getString("user_id")).thenReturn(null);
        when(resultSet.getString("instance_id")).thenReturn(null);

        List<LogEntry> logs = databaseLogs.getLogs(null, null, null, null, null, null, null, null);
        assertEquals(1, logs.size());
        assertEquals(0L, logs.get(0).timestamp());
    }

    @Test
    void getLogs_withSkipAndLimit() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        List<LogEntry> logs = databaseLogs.getLogs(Environment.production, "agent-1", 1, "conv", "user", "inst", 5, 20);
        assertTrue(logs.isEmpty());
    }

    @Test
    void getLogs_zeroOrNegativeLimitAndSkip() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        // Zero limit/skip should not append LIMIT/OFFSET
        List<LogEntry> logs = databaseLogs.getLogs(null, null, null, null, null, null, 0, 0);
        assertTrue(logs.isEmpty());
    }

    // ─── addLogsBatch ───

    @Test
    void addLogsBatch_happyPath() throws Exception {
        LogEntry entry = new LogEntry(System.currentTimeMillis(), "INFO", "ai.labs.test", "msg",
                "production", "agent-1", 1, "conv-1", "user-1", "inst-1");

        databaseLogs.addLogsBatch(List.of(entry));

        verify(preparedStatement).addBatch();
        verify(preparedStatement).executeBatch();
    }

    @Test
    void addLogsBatch_nullVersion() throws Exception {
        LogEntry entry = new LogEntry(System.currentTimeMillis(), "INFO", "logger", "msg",
                "test", "agent-1", null, "conv-1", "user-1", "inst-1");

        databaseLogs.addLogsBatch(List.of(entry));

        verify(preparedStatement).setNull(7, Types.INTEGER);
    }

    @Test
    void addLogsBatch_nullEntries_doesNothing() {
        assertDoesNotThrow(() -> databaseLogs.addLogsBatch(null));
        verifyNoInteractions(dataSource);
    }

    @Test
    void addLogsBatch_emptyEntries_doesNothing() {
        assertDoesNotThrow(() -> databaseLogs.addLogsBatch(List.of()));
        verifyNoInteractions(dataSource);
    }

    @Test
    void addLogsBatch_sqlException_logsError() throws Exception {
        when(preparedStatement.executeBatch()).thenThrow(new SQLException("batch error"));

        LogEntry entry = new LogEntry(System.currentTimeMillis(), "INFO", "logger", "msg",
                "production", "agent-1", 1, "conv-1", "user-1", "inst-1");

        // Should not throw, just log
        assertDoesNotThrow(() -> databaseLogs.addLogsBatch(List.of(entry)));
    }

    // ─── GDPR: pseudonymizeByUserId ───

    @Test
    void pseudonymizeByUserId_returnsCount() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(5);

        assertEquals(5, databaseLogs.pseudonymizeByUserId("user-1", "anon-123"));
        verify(preparedStatement).setString(1, "anon-123");
        verify(preparedStatement).setString(2, "user-1");
    }

    /**
     * Returning 0 on a failed UPDATE made the GDPR cascade read "this user has no
     * log entries" and report the erasure complete while the raw id stayed in the
     * table.
     */
    @Test
    void pseudonymizeByUserId_sqlException_throwsSoTheErasureIsReportedIncomplete() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));

        assertThrows(IllegalStateException.class, () -> databaseLogs.pseudonymizeByUserId("user-1", "anon"));
    }

    // ─── Retention: deleteOlderThan ───

    @Test
    void deleteOlderThan_deletesBeforeTheCutoff() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(7);
        long before = System.currentTimeMillis();

        assertEquals(7, databaseLogs.deleteOlderThan(30));

        var sql = ArgumentCaptor.forClass(String.class);
        verify(connection, atLeastOnce()).prepareStatement(sql.capture());
        assertTrue(sql.getAllValues().stream().anyMatch(q -> q.startsWith("DELETE FROM database_logs WHERE ctid IN")), sql.getAllValues().toString());
        var cutoff = ArgumentCaptor.forClass(Timestamp.class);
        verify(preparedStatement).setTimestamp(eq(1), cutoff.capture());
        long expected = before - Duration.ofDays(30).toMillis();
        assertTrue(Math.abs(cutoff.getValue().getTime() - expected) < 60_000, "cutoff is now minus 30 days");
    }

    @Test
    void deleteOlderThan_repeatsUntilABatchIsShort() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(PostgresDatabaseLogs.RETENTION_BATCH_SIZE, PostgresDatabaseLogs.RETENTION_BATCH_SIZE, 5);

        assertEquals(2L * PostgresDatabaseLogs.RETENTION_BATCH_SIZE + 5, databaseLogs.deleteOlderThan(30));
        verify(preparedStatement, times(3)).executeUpdate();
    }

    @Test
    void deleteOlderThan_rejectsANonPositiveRetention() {
        assertThrows(IllegalArgumentException.class, () -> databaseLogs.deleteOlderThan(0));
    }

    @Test
    void deleteOlderThan_sqlException_throws() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));
        assertThrows(IllegalStateException.class, () -> databaseLogs.deleteOlderThan(5));
    }
}
