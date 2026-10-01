/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How the background build treats a failure. On a live boot, the first build
 * ran into the other storages' schema setup and PostgreSQL aborted it as a
 * deadlock (SQLState 40P01), leaving an index INVALID; such a failure must be
 * retried, and one that waiting cannot fix must not be.
 */
@DisplayName("PostgreSQL substring-search indexes: retries")
class PostgresSubstringSearchIndexesRetryTest {

    private Statement statement;
    private PostgresSubstringSearchIndexes indexes;

    @BeforeEach
    void setUp() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        statement = mock(Statement.class);
        PreparedStatement preparedStatement = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        // The advisory lock is granted, and every index is found valid once built.
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getBoolean(1)).thenReturn(true);
        indexes = new PostgresSubstringSearchIndexes(dataSource, "descriptors", List.of("name"));
    }

    @Test
    @DisplayName("a deadlock is retried, and the retry builds the indexes")
    void deadlockIsRetried() throws Exception {
        when(statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm"))
                .thenThrow(new SQLException("deadlock detected", "40P01"))
                .thenReturn(false);

        assertTrue(indexes.buildWithRetries(0, 0));
        verify(statement, times(2)).execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
    }

    @Test
    @DisplayName("a refused privilege is not retried")
    void insufficientPrivilegeIsNotRetried() throws Exception {
        when(statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm"))
                .thenThrow(new SQLException("permission denied to create extension", "42501"));

        assertFalse(indexes.buildWithRetries(0, 0, 0));
        verify(statement, times(1)).execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
    }

    @Test
    @DisplayName("retries stop after the last delay")
    void retriesAreBounded() throws Exception {
        when(statement.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm"))
                .thenThrow(new SQLException("deadlock detected", "40P01"));

        assertFalse(indexes.buildWithRetries(0, 0));
        verify(statement, times(3)).execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
    }

    @Test
    @DisplayName("deadlock, serialization failure, lock timeout and cancellation are transient; the rest are not")
    void transientClassification() {
        assertTrue(PostgresSubstringSearchIndexes.isTransient(new SQLException("x", "40P01")));
        assertTrue(PostgresSubstringSearchIndexes.isTransient(new SQLException("x", "40001")));
        assertTrue(PostgresSubstringSearchIndexes.isTransient(new SQLException("x", "55P03")));
        assertTrue(PostgresSubstringSearchIndexes.isTransient(new SQLException("x", "57014")));
        assertFalse(PostgresSubstringSearchIndexes.isTransient(new SQLException("x", "42501")));
        assertFalse(PostgresSubstringSearchIndexes.isTransient(new SQLException("x", "58P01")));
        assertFalse(PostgresSubstringSearchIndexes.isTransient(new SQLException("x")));
    }
}
