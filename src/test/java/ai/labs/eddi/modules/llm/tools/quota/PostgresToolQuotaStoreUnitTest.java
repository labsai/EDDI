/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PostgresToolQuotaStore with the database down")
class PostgresToolQuotaStoreUnitTest {

    @Test
    @DisplayName("every call throws ToolQuotaStoreException (so the caller fails closed) and the next call retries the DDL")
    void downDatabaseThrowsAndRetries() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("down"));
        @SuppressWarnings("unchecked")
        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(dataSource);
        var store = new PostgresToolQuotaStore(instance);
        var key = new ToolQuotaKey("aaaaaaaaaaaaaaaaaaaaaaaa", "tool:a", "u", 0L);

        assertThrows(ToolQuotaStoreException.class, () -> store.tryAcquire(key, 1, Instant.now()));
        assertThrows(ToolQuotaStoreException.class, () -> store.tryAcquire(key, 1, Instant.now()));

        verify(dataSource, times(2)).getConnection();
    }

    @Test
    @DisplayName("a refused acquire reads the count on the connection it already holds, never a second one")
    void refusalUsesOneConnection() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        Statement ddl = mock(Statement.class);
        PreparedStatement prepared = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(ddl);
        when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(prepared);
        when(prepared.executeQuery()).thenReturn(rows);
        // First query (the conditional upsert) returns no row = refused; second (the
        // read) returns one.
        when(rows.next()).thenReturn(false, true);
        when(rows.getInt(1)).thenReturn(3);
        @SuppressWarnings("unchecked")
        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(dataSource);
        var store = new PostgresToolQuotaStore(instance);
        var key = new ToolQuotaKey("aaaaaaaaaaaaaaaaaaaaaaaa", "tool:a", "u", 0L);

        var acquisition = store.tryAcquire(key, 2, Instant.now());

        assertFalse(acquisition.granted());
        assertEquals(3, acquisition.used());
        // One connection for the DDL, one for the acquire - the read must not take a
        // third.
        verify(dataSource, times(2)).getConnection();
    }
}
