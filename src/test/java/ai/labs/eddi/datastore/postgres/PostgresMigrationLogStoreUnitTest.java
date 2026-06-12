/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import ai.labs.eddi.configs.migration.model.MigrationLog;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.sql.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PostgresMigrationLogStoreUnitTest {

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

    private PostgresMigrationLogStore store;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        store = new PostgresMigrationLogStore(dataSourceInstance);
    }

    // ─── readMigrationLog ───

    @Test
    void readMigrationLog_found() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("name")).thenReturn("migration-v6");

        MigrationLog result = store.readMigrationLog("migration-v6");

        assertNotNull(result);
        assertEquals("migration-v6", result.getName());
    }

    @Test
    void readMigrationLog_notFound_returnsNull() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertNull(store.readMigrationLog("missing"));
    }

    @Test
    void readMigrationLog_sqlException_returnsNull() throws Exception {
        when(preparedStatement.executeQuery()).thenThrow(new SQLException("error"));

        // Should not throw, logs error and returns null
        assertNull(store.readMigrationLog("fail"));
    }

    // ─── createMigrationLog ───

    @Test
    void createMigrationLog_happyPath() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        MigrationLog log = new MigrationLog("migration-v6");
        assertDoesNotThrow(() -> store.createMigrationLog(log));

        verify(preparedStatement).setString(1, "migration-v6");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    void createMigrationLog_sqlException_logsError() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("error"));

        MigrationLog log = new MigrationLog("fail");
        // Should not throw, just logs
        assertDoesNotThrow(() -> store.createMigrationLog(log));
    }

    // ─── ensureSchema idempotency ───

    @Test
    void ensureSchema_calledOnlyOnce() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        store.readMigrationLog("a");
        store.readMigrationLog("b");

        verify(connection, times(1)).createStatement();
    }
}
