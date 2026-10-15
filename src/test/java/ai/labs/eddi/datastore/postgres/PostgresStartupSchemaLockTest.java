/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Replicas booting together against an empty PostgreSQL database raced each
 * other's {@code CREATE TABLE IF NOT EXISTS} and the loser failed its boot; the
 * startup phase now runs under one advisory lock.
 */
@DisplayName("PostgreSQL startup lock")
class PostgresStartupSchemaLockTest {

    @SuppressWarnings("unchecked")
    private static Instance<DataSource> dataSource(Connection connection) throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(connection);
        Instance<DataSource> instance = mock(Instance.class);
        when(instance.get()).thenReturn(ds);
        return instance;
    }

    @Test
    @DisplayName("takes the advisory lock (bounded wait) for the startup and releases it at its end")
    void locksAroundStartup() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        var lock = new PostgresStartupSchemaLock(dataSource(connection), "postgres", 90);

        lock.acquire();
        InOrder order = inOrder(statement);
        order.verify(statement).execute("SET lock_timeout = '90s'");
        order.verify(statement).execute("SELECT pg_advisory_lock(" + PostgresStartupSchemaLock.LOCK_KEY + ")");
        verify(connection, never()).close();

        lock.release();
        verify(statement).execute("SELECT pg_advisory_unlock(" + PostgresStartupSchemaLock.LOCK_KEY + ")");
        verify(connection).close();
    }

    @Test
    @DisplayName("a lock that cannot be had in time does not stop the node from starting")
    void timeoutStartsWithoutTheLock() throws Exception {
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenReturn(true);
        when(statement.execute("SELECT pg_advisory_lock(" + PostgresStartupSchemaLock.LOCK_KEY + ")"))
                .thenThrow(new SQLException("canceling statement due to lock timeout", "55P03"));
        var lock = new PostgresStartupSchemaLock(dataSource(connection), "postgres", 90);

        assertDoesNotThrow(() -> lock.acquire());
        verify(connection).close();
        assertDoesNotThrow(() -> lock.release());
    }

    @Test
    @DisplayName("MongoDB never touches a datasource")
    void mongoIsANoOp() throws Exception {
        @SuppressWarnings("unchecked")
        Instance<DataSource> instance = mock(Instance.class);
        var lock = new PostgresStartupSchemaLock(instance, "mongodb", 90);
        lock.acquire();
        lock.release();
        verifyNoInteractions(instance);
    }
}
