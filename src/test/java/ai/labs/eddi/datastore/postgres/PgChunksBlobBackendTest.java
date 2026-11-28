/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PgChunksBlobBackend} paths that need a JDBC driver to misbehave, which
 * a real PostgreSQL will not do on demand. The contract is in
 * {@code PgChunksBlobBackendIT}.
 */
@DisplayName("PgChunksBlobBackend failure paths")
class PgChunksBlobBackendTest {

    /**
     * The first INSERT is auto-committed; closing its statement then throws. The
     * row is this upload's, so the failed put must remove it — else the key is
     * taken by a fragment and a retry is refused as "already exists".
     */
    @Test
    void firstRowIsRemovedWhenClosingItsStatementFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        PreparedStatement insert = mock(PreparedStatement.class);
        when(insert.executeUpdate()).thenReturn(1);
        doThrow(new SQLException("close failed")).when(insert).close();
        PreparedStatement delete = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenAnswer(inv -> {
            String sql = inv.getArgument(0);
            return sql.startsWith("DELETE") ? delete : insert;
        });
        var backend = new PgChunksBlobBackend(() -> dataSource, 1024);

        assertThrows(IOException.class, () -> backend.put("k1", new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, 3));

        verify(connection).prepareStatement(argThat((String sql) -> sql.startsWith("DELETE")));
        verify(delete).executeUpdate();
    }

    /**
     * A conflict on the first row means the key is someone else's: never deleted.
     */
    @Test
    void conflictOnTheFirstRowDeletesNothingEvenIfCloseFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(mock(Statement.class));
        PreparedStatement insert = mock(PreparedStatement.class);
        when(insert.executeUpdate()).thenReturn(0);
        doThrow(new SQLException("close failed")).when(insert).close();
        when(connection.prepareStatement(anyString())).thenReturn(insert);
        var backend = new PgChunksBlobBackend(() -> dataSource, 1024);

        assertThrows(IOException.class, () -> backend.put("k1", new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, 3));

        verify(connection, never()).prepareStatement(argThat((String sql) -> sql.startsWith("DELETE")));
    }
}
