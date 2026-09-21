/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.names;

import ai.labs.eddi.configs.connections.names.IConnectionNameClaimStore.NameClaim;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PostgresConnectionNameClaimStore} against a mocked JDBC stack: the DDL
 * that carries the unique constraint, the conflict mapping, and the SQL shape
 * of every compare-and-set.
 */
class PostgresConnectionNameClaimStoreUnitTest {

    private static final String TENANT = "default";
    private static final String NAME = "jira";

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

    private PostgresConnectionNameClaimStore store;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        store = new PostgresConnectionNameClaimStore(dataSourceInstance);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    /**
     * Every connection and statement the store obtained was closed.
     * <p>
     * The stubs in {@link #setUp} hand out mocks; nothing is acquired there. The
     * contract worth pinning is the production side: that each
     * {@code getConnection()}, {@code createStatement()} and
     * {@code prepareStatement()} is matched by a {@code close()}, on the failure
     * path as well as the success path.
     */
    private void assertEveryConnectionAndStatementClosed() throws SQLException {
        int connections = invocations(dataSource, "getConnection");
        assertTrue(connections > 0, "the scenario must actually have opened a connection");
        verify(connection, times(connections)).close();
        verify(statement, times(invocations(connection, "createStatement"))).close();
        verify(preparedStatement, times(invocations(connection, "prepareStatement"))).close();
    }

    private static int invocations(Object mock, String method) {
        return (int) mockingDetails(mock).getInvocations().stream().filter(invocation -> invocation.getMethod().getName().equals(method))
                .count();
    }

    private String capturedSql() throws SQLException {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        return sql.getValue();
    }

    @Test
    @DisplayName("the table carries a unique constraint on (tenant_id, connection_name) and a TIMESTAMPTZ claim time")
    void createsTheTableWithTheUniqueConstraint() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.claim(TENANT, NAME, "token-1");

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(statement).execute(ddl.capture());
        assertTrue(ddl.getValue().contains("CREATE TABLE IF NOT EXISTS connection_name_claims"), ddl.getValue());
        assertTrue(ddl.getValue().contains("UNIQUE (tenant_id, connection_name)"), "the constraint is the whole guarantee: " + ddl.getValue());
        assertTrue(ddl.getValue().contains("claimed_at TIMESTAMPTZ"),
                "compared with CURRENT_TIMESTAMP, so it must be the same kind of timestamp: " + ddl.getValue());
        assertEveryConnectionAndStatementClosed();
    }

    @Test
    @DisplayName("a failed schema step still closes its statement and connection")
    void schemaFailureClosesItsResources() throws Exception {
        when(statement.execute(anyString())).thenThrow(new SQLException("permission denied for schema public"));
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertTrue(store.claim(TENANT, NAME, "token-1"));

        verify(statement).close();
        assertEveryConnectionAndStatementClosed();
    }

    @Test
    @DisplayName("claim inserts with ON CONFLICT DO NOTHING and reports one row as won")
    void claimInsertsOnConflictDoNothing() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertTrue(store.claim(TENANT, NAME, "token-1"));

        String sql = capturedSql();
        assertTrue(sql.contains("ON CONFLICT (tenant_id, connection_name) DO NOTHING"), sql);
        assertTrue(sql.contains("CURRENT_TIMESTAMP"), "claimed_at comes from the database clock: " + sql);
        verify(preparedStatement).setString(1, TENANT);
        verify(preparedStatement).setString(2, NAME);
        verify(preparedStatement).setString(3, "token-1");
    }

    @Test
    @DisplayName("claim reports zero rows — a held name — as lost")
    void claimReportsAConflictAsLost() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertFalse(store.claim(TENANT, NAME, "token-1"));
    }

    @Test
    @DisplayName("claim maps a unique violation to a lost claim rather than a store failure")
    void claimMapsAUniqueViolationToLost() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("duplicate key value violates unique constraint", "23505"));

        assertFalse(store.claim(TENANT, NAME, "token-1"));
        assertEveryConnectionAndStatementClosed();
    }

    @Test
    @DisplayName("claim wraps any other database failure with its cause")
    void claimWrapsOtherFailures() throws Exception {
        var boom = new SQLException("connection reset", "08006");
        when(preparedStatement.executeUpdate()).thenThrow(boom);

        var thrown = assertThrows(IllegalStateException.class, () -> store.claim(TENANT, NAME, "token-1"));
        assertSame(boom, thrown.getCause());
        verify(preparedStatement).close();
        assertEveryConnectionAndStatementClosed();
    }

    @Test
    @DisplayName("find maps the holder, and an absent row is empty")
    void findMapsTheClaim() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("tenant_id")).thenReturn(TENANT);
        when(resultSet.getString("connection_name")).thenReturn(NAME);
        when(resultSet.getString("claim_token")).thenReturn("t");
        when(resultSet.getString("connection_id")).thenReturn("c1");

        assertEquals(new NameClaim(TENANT, NAME, "t", "c1"), store.find(TENANT, NAME).orElseThrow());
        assertTrue(store.find(TENANT, NAME).isEmpty());

        // Both reads, the hit and the miss, release their ResultSet.
        verify(resultSet, times(2)).close();
        assertEveryConnectionAndStatementClosed();
    }

    @Test
    @DisplayName("find closes the ResultSet, statement and connection when mapping a row fails")
    void findClosesEverythingWhenMappingFails() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        var boom = new SQLException("column tenant_id does not exist");
        when(resultSet.getString("tenant_id")).thenThrow(boom);

        var thrown = assertThrows(IllegalStateException.class, () -> store.find(TENANT, NAME));

        assertSame(boom, thrown.getCause());
        verify(resultSet).close();
        verify(preparedStatement).close();
        assertEveryConnectionAndStatementClosed();
    }

    @Test
    @DisplayName("takeOver of an unrecorded claim compares age with CURRENT_TIMESTAMP and binds the bound as milliseconds")
    void takeOverOfAnUnrecordedClaim() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertTrue(store.takeOver(new NameClaim(TENANT, NAME, "old-token", null), "new-token", Duration.ofMinutes(2)));

        String sql = capturedSql();
        assertTrue(sql.contains("claimed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 millisecond')"), sql);
        assertTrue(sql.contains("claim_token = ? AND connection_id IS NULL"), "the compare half of the compare-and-set: " + sql);
        assertTrue(sql.contains("claimed_at = CURRENT_TIMESTAMP"), sql);
        verify(preparedStatement).setString(1, "new-token");
        verify(preparedStatement).setString(4, "old-token");
        verify(preparedStatement).setLong(5, 120_000L);
    }

    @Test
    @DisplayName("takeOver of a recorded claim compares the connection id, not the age")
    void takeOverOfARecordedClaim() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertFalse(store.takeOver(new NameClaim(TENANT, NAME, "old-token", "c1"), "new-token", Duration.ofMinutes(2)));

        String sql = capturedSql();
        assertTrue(sql.contains("claim_token = ? AND connection_id = ?"), sql);
        assertFalse(sql.contains("INTERVAL"), sql);
        verify(preparedStatement).setString(5, "c1");
        verify(preparedStatement, never()).setLong(anyInt(), anyLong());
    }

    @Test
    @DisplayName("recordConnection is conditional on the token")
    void recordConnectionComparesTheToken() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertFalse(store.recordConnection(TENANT, NAME, "token-1", "c1"));

        String sql = capturedSql();
        assertTrue(sql.contains("SET connection_id = ?") && sql.contains("AND claim_token = ?"), sql);
        verify(preparedStatement).setString(1, "c1");
        verify(preparedStatement).setString(4, "token-1");
    }

    @Test
    @DisplayName("release deletes by token and releaseConnection by connection id")
    void releasesAreScoped() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertTrue(store.release(TENANT, NAME, "token-1"));
        assertTrue(store.releaseConnection(TENANT, NAME, "c1"));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection, times(2)).prepareStatement(sql.capture());
        assertTrue(sql.getAllValues().get(0).endsWith("AND claim_token = ?"), sql.getAllValues().get(0));
        assertTrue(sql.getAllValues().get(1).endsWith("AND connection_id = ?"), sql.getAllValues().get(1));
        verify(preparedStatement).setString(3, "token-1");
        verify(preparedStatement).setString(3, "c1");
        assertEveryConnectionAndStatementClosed();
    }
}
