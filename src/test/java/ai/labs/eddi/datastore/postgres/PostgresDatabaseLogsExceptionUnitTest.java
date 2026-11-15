/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.engine.model.LogEntry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * PostgreSQL persistence of {@link LogEntry#exception()}.
 */
@SuppressWarnings("unchecked")
class PostgresDatabaseLogsExceptionUnitTest {

    private Statement statement;
    private PreparedStatement preparedStatement;
    private Connection connection;
    private PostgresDatabaseLogs databaseLogs;

    @BeforeEach
    void setUp() throws Exception {
        Instance<DataSource> instance = mock(Instance.class);
        DataSource dataSource = mock(DataSource.class);
        connection = mock(Connection.class);
        statement = mock(Statement.class);
        preparedStatement = mock(PreparedStatement.class);
        when(instance.get()).thenReturn(dataSource);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        databaseLogs = new PostgresDatabaseLogs(instance);
    }

    @Test
    @DisplayName("an existing table gets the exception column added, compatibly")
    void schemaAddsColumnIfMissing() throws Exception {
        databaseLogs.addLogsBatch(List.of(new LogEntry(1L, "ERROR", "l", "m", null, null, null, null, null, null, "x")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(statement, atLeastOnce()).execute(sql.capture());
        assertTrue(sql.getAllValues().contains("ALTER TABLE database_logs ADD COLUMN IF NOT EXISTS exception TEXT"), sql.getAllValues().toString());
    }

    @Test
    @DisplayName("the exception is bound as the eleventh insert parameter")
    void insertBindsException() throws Exception {
        databaseLogs.addLogsBatch(List.of(new LogEntry(1L, "ERROR", "l", "m", null, null, null, null, null, null, "java.io.IOException: x")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("instance_id, exception)"), sql.getValue());
        verify(preparedStatement).setString(11, "java.io.IOException: x");
    }

    @Test
    @DisplayName("an entry without an exception binds a null")
    void insertBindsNullWhenAbsent() throws Exception {
        databaseLogs.addLogsBatch(List.of(new LogEntry(1L, "WARN", "l", "m", null, null, null, null, null, null)));

        verify(preparedStatement).setString(11, null);
    }

    @Test
    @DisplayName("the exception column is read back; a row from before the column reads as null")
    void readsExceptionAndOldRows() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(preparedStatement.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(true, true, false);
        when(rs.getTimestamp("timestamp")).thenReturn(new Timestamp(7L));
        when(rs.getString("exception")).thenReturn("java.io.IOException: x", (String) null);

        List<LogEntry> logs = databaseLogs.getLogs(null, null, null, null, null, null, 0, 0);

        assertEquals(2, logs.size());
        assertEquals("java.io.IOException: x", logs.get(0).exception());
        assertNull(logs.get(1).exception());
    }
}
