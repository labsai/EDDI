/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.postgres;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The background replacement of the ascending field indexes: concurrent, under
 * a lock, and never dropping the old index without a valid successor.
 */
@DisplayName("PostgresFieldIndexSwaps")
class PostgresFieldIndexSwapsTest {

    private static final PostgresFieldIndexSwaps.Swap SWAP = new PostgresFieldIndexSwaps.Swap("idx_resources_fdesc_name",
            "resources (collection_name, (data ->> 'name') DESC NULLS LAST)", "idx_resources_field_name");

    private Connection conn;
    private Statement stmt;
    private ResultSet lockResult;
    private ResultSet validity;
    private PostgresFieldIndexSwaps swaps;

    @BeforeEach
    void setUp() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        conn = mock(Connection.class);
        stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);

        PreparedStatement lock = mock(PreparedStatement.class);
        lockResult = mock(ResultSet.class);
        when(conn.prepareStatement(contains("pg_try_advisory_lock"))).thenReturn(lock);
        when(lock.executeQuery()).thenReturn(lockResult);
        when(lockResult.next()).thenReturn(true);
        when(lockResult.getBoolean(1)).thenReturn(true);
        when(conn.prepareStatement(contains("pg_advisory_unlock"))).thenReturn(mock(PreparedStatement.class));

        PreparedStatement lookup = mock(PreparedStatement.class);
        validity = mock(ResultSet.class);
        when(conn.prepareStatement(contains("indisvalid"))).thenReturn(lookup);
        when(lookup.executeQuery()).thenReturn(validity);

        swaps = new PostgresFieldIndexSwaps(dataSource, List.of(SWAP));
    }

    @Test
    @DisplayName("builds concurrently, outside a transaction, and drops the old index only after the new one is valid")
    void buildsConcurrentlyThenDropsTheLegacyIndex() throws SQLException {
        // first lookup: the new index does not exist yet; second: it is valid
        when(validity.next()).thenReturn(false, true);
        when(validity.getBoolean(1)).thenReturn(true);

        swaps.run();

        verify(conn).setAutoCommit(true);
        InOrder order = inOrder(stmt);
        order.verify(stmt).execute("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_resources_fdesc_name ON " + SWAP.target());
        order.verify(stmt).execute("DROP INDEX CONCURRENTLY IF EXISTS idx_resources_field_name");
        verify(stmt, never()).execute(startsWith("CREATE INDEX IF NOT EXISTS"));
        verify(stmt, never()).execute("DROP INDEX CONCURRENTLY IF EXISTS idx_resources_fdesc_name");
        assertTrue(swaps.pending().isEmpty());
    }

    @Test
    @DisplayName("keeps the old index when the new one is not valid after its build")
    void keepsTheLegacyIndexWithoutAValidReplacement() throws SQLException {
        when(validity.next()).thenReturn(false, true);
        when(validity.getBoolean(1)).thenReturn(false);

        assertThrows(SQLException.class, swaps::run);

        verify(stmt, never()).execute(contains("idx_resources_field_name"));
        assertEquals(List.of(SWAP), swaps.pending());
    }

    @Test
    @DisplayName("drops and rebuilds a replacement an interrupted build left INVALID")
    void repairsAnInvalidReplacement() throws SQLException {
        when(validity.next()).thenReturn(true, true);
        when(validity.getBoolean(1)).thenReturn(false, true);

        swaps.run();

        InOrder order = inOrder(stmt);
        order.verify(stmt).execute("DROP INDEX CONCURRENTLY IF EXISTS idx_resources_fdesc_name");
        order.verify(stmt).execute(startsWith("CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_resources_fdesc_name"));
        order.verify(stmt).execute("DROP INDEX CONCURRENTLY IF EXISTS idx_resources_field_name");
    }

    @Test
    @DisplayName("does nothing while another instance holds the swap's lock, and reports that as worth a retry")
    void leavesTheWorkToTheInstanceHoldingTheLock() throws SQLException {
        when(lockResult.getBoolean(1)).thenReturn(false);

        SQLException e = assertThrows(SQLException.class, swaps::run);

        assertTrue(PostgresSubstringSearchIndexes.isTransient(e), e.getSQLState());
        verify(stmt, never()).execute(contains("INDEX"));
        assertEquals(List.of(SWAP), swaps.pending());
    }

    @Test
    @DisplayName("a refused build is not retried and leaves the old index in place")
    void refusedBuildKeepsTheLegacyIndex() throws SQLException {
        when(validity.next()).thenReturn(false);
        when(stmt.execute(startsWith("CREATE INDEX CONCURRENTLY"))).thenThrow(new SQLException("permission denied", "42501"));

        assertFalse(swaps.runWithRetries(1, 1, 1));

        verify(stmt, never()).execute(contains("idx_resources_field_name"));
        assertEquals(List.of(SWAP), swaps.pending());
    }
}
