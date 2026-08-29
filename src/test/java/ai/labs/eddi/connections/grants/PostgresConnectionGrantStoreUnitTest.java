/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.grants;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.AbstractList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link PostgresConnectionGrantStore} against a mocked JDBC stack.
 * <p>
 * The interesting behaviour of this store is not the SQL text, it is what the
 * store does with the row count that comes back: {@code claimRefresh},
 * {@code completeRefresh} and {@code updateSealedTokens} each turn
 * {@code executeUpdate()} into a boolean, and that boolean is the whole
 * cross-replica refresh design. Losing it — returning true unconditionally, or
 * widening {@code == 1} to {@code >= 0} — silently reintroduces the double
 * refresh that logs users out, and nothing else in the suite would notice.
 * Mocking JDBC lets both sides of every one of those decisions be exercised
 * without a database.
 */
class PostgresConnectionGrantStoreUnitTest {

    private static final String TENANT = "acme";
    private static final String CONNECTION = "jira";
    private static final String PRINCIPAL = "alice";
    private static final String CLAIMANT = "replica-a";

    private static final Instant EXPIRES_AT = Instant.parse("2026-01-02T03:04:05Z");
    private static final Instant CREATED_AT = Instant.parse("2025-11-01T10:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2025-12-01T11:00:00Z");
    private static final Instant LAST_REFRESH_AT = Instant.parse("2025-12-24T12:00:00Z");
    private static final Instant LEASE_EXPIRES_AT = Instant.parse("2026-01-02T03:00:00Z");

    @Mock
    private Instance<DataSource> dataSourceInstance;
    @Mock
    private Instance<DataSource> unresolvedDataSourceInstance;
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

    private PostgresConnectionGrantStore store;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        store = new PostgresConnectionGrantStore(dataSourceInstance);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    private static ConnectionGrant grant() {
        var grant = new ConnectionGrant();
        grant.setTenantId(TENANT);
        grant.setConnectionName(CONNECTION);
        grant.setPrincipal(PRINCIPAL);
        grant.setEncryptedAccessToken("cipher-access");
        grant.setAccessTokenIv("iv-access");
        grant.setEncryptedRefreshToken("cipher-refresh");
        grant.setRefreshTokenIv("iv-refresh");
        grant.setDekId("dek-7");
        grant.setExpiresAt(EXPIRES_AT);
        grant.setLastRefreshAt(LAST_REFRESH_AT);
        grant.setScopes(List.of("read", "write"));
        grant.setStatus(ConnectionGrant.Status.ACTIVE);
        return grant;
    }

    /**
     * A list Jackson cannot walk, standing in for anything that makes
     * {@code writeValueAsString} fail at runtime.
     */
    private static List<String> unserialisableScopes() {
        return new AbstractList<>() {
            @Override
            public String get(int index) {
                throw new IllegalStateException("scope list cannot be read");
            }

            @Override
            public int size() {
                return 1;
            }
        };
    }

    private void stubFullRow() throws SQLException {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("id")).thenReturn("grant-1");
        when(resultSet.getString("tenant_id")).thenReturn(TENANT);
        when(resultSet.getString("connection_name")).thenReturn(CONNECTION);
        when(resultSet.getString("principal")).thenReturn(PRINCIPAL);
        when(resultSet.getString("encrypted_access_token")).thenReturn("cipher-access");
        when(resultSet.getString("access_token_iv")).thenReturn("iv-access");
        when(resultSet.getString("encrypted_refresh_token")).thenReturn("cipher-refresh");
        when(resultSet.getString("refresh_token_iv")).thenReturn("iv-refresh");
        when(resultSet.getString("dek_id")).thenReturn("dek-7");
        when(resultSet.getString("scopes")).thenReturn("[\"read\",\"write\"]");
        when(resultSet.getString("status")).thenReturn("EXPIRED");
        when(resultSet.getString("refresh_in_progress")).thenReturn(CLAIMANT);
        when(resultSet.getLong("version")).thenReturn(11L);
        when(resultSet.getTimestamp("expires_at")).thenReturn(Timestamp.from(EXPIRES_AT));
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(CREATED_AT));
        when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.from(UPDATED_AT));
        when(resultSet.getTimestamp("last_refresh_at")).thenReturn(Timestamp.from(LAST_REFRESH_AT));
        when(resultSet.getTimestamp("refresh_lease_expires_at")).thenReturn(Timestamp.from(LEASE_EXPIRES_AT));
    }

    private void stubTwoRows() throws SQLException {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, true, false);
        when(resultSet.getString("id")).thenReturn("grant-1", "grant-2");
        when(resultSet.getString("connection_name")).thenReturn("jira", "slack");
        when(resultSet.getString("status")).thenReturn("ACTIVE", "REVOKED");
        when(resultSet.getLong("version")).thenReturn(3L, 9L);
    }

    private String capturedSql() throws SQLException {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        return sql.getValue();
    }

    private static void assertWraps(String expectedMessage, SQLException cause, Executable call) {
        var thrown = assertThrows(IllegalStateException.class, call);
        assertEquals(expectedMessage, thrown.getMessage());
        assertSame(cause, thrown.getCause(), "dropping the SQLException leaves an operator with a failure and no reason for it");
    }

    @Test
    @DisplayName("constructing the store touches no datasource, so a mongo deployment still boots")
    void doesNotResolveTheDataSourceAtConstruction() {
        new PostgresConnectionGrantStore(unresolvedDataSourceInstance);

        verifyNoInteractions(unresolvedDataSourceInstance);
    }

    @Test
    @DisplayName("the schema is created on the first call and skipped on every call after it")
    void createsTheSchemaOnceOnly() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        store.delete(TENANT, CONNECTION, PRINCIPAL);
        store.delete(TENANT, CONNECTION, PRINCIPAL);

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(statement, times(2)).execute(ddl.capture());
        assertTrue(ddl.getAllValues().get(0).contains("CREATE TABLE IF NOT EXISTS connection_grants"));
        assertTrue(ddl.getAllValues().get(1).contains("CREATE INDEX IF NOT EXISTS idx_cg_tenant_principal"));
    }

    @Test
    @DisplayName("a failed schema creation is logged, not thrown, and is retried on the next call")
    void schemaFailureDoesNotAbortTheOperation() throws Exception {
        when(statement.execute(anyString())).thenThrow(new SQLException("permission denied for schema public"));
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertTrue(store.delete(TENANT, CONNECTION, PRINCIPAL),
                "a login without DDL rights still has the table; refusing every read and write there would be a self-inflicted outage");
        assertTrue(store.delete(TENANT, CONNECTION, PRINCIPAL));

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(statement, times(2)).execute(ddl.capture());
        assertTrue(ddl.getAllValues().stream().allMatch(sql -> sql.contains("CREATE TABLE")),
                "both attempts must start at the CREATE TABLE, proving a failed create was not recorded as done");
    }

    @Test
    @DisplayName("find maps every column of a populated row")
    void findMapsAFullyPopulatedRow() throws Exception {
        stubFullRow();

        Optional<ConnectionGrant> found = store.find(TENANT, CONNECTION, PRINCIPAL);

        assertTrue(found.isPresent());
        var grant = found.get();
        assertEquals("grant-1", grant.getId());
        assertEquals(TENANT, grant.getTenantId());
        assertEquals(CONNECTION, grant.getConnectionName());
        assertEquals(PRINCIPAL, grant.getPrincipal());
        assertEquals("cipher-access", grant.getEncryptedAccessToken());
        assertEquals("iv-access", grant.getAccessTokenIv());
        assertEquals("cipher-refresh", grant.getEncryptedRefreshToken());
        assertEquals("iv-refresh", grant.getRefreshTokenIv());
        assertEquals("dek-7", grant.getDekId(), "without the dekId the ciphertext cannot be opened with the right generation");
        assertEquals(EXPIRES_AT, grant.getExpiresAt());
        assertEquals(List.of("read", "write"), grant.getScopes());
        assertEquals(ConnectionGrant.Status.EXPIRED, grant.getStatus());
        assertEquals(CREATED_AT, grant.getCreatedAt());
        assertEquals(UPDATED_AT, grant.getUpdatedAt());
        assertEquals(LAST_REFRESH_AT, grant.getLastRefreshAt());
        assertEquals(11L, grant.getVersion(), "the version is what a later CAS is spent against");
        assertEquals(CLAIMANT, grant.getRefreshInProgress());
        assertEquals(LEASE_EXPIRES_AT, grant.getRefreshLeaseExpiresAt());
    }

    @Test
    @DisplayName("find binds the tenant, connection and principal that key a grant")
    void findBindsTheFullKey() throws Exception {
        stubFullRow();

        store.find(TENANT, CONNECTION, PRINCIPAL);

        verify(preparedStatement).setString(1, TENANT);
        verify(preparedStatement).setString(2, CONNECTION);
        verify(preparedStatement).setString(3, PRINCIPAL);
        assertTrue(capturedSql().contains("WHERE tenant_id = ? AND connection_name = ? AND principal = ?"),
                "a predicate missing the tenant would hand one tenant another tenant's tokens");
        verify(resultSet).close();
    }

    @Test
    @DisplayName("find returns empty when the principal never connected")
    void findReturnsEmptyWhenNoRowMatches() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertTrue(store.find(TENANT, CONNECTION, PRINCIPAL).isEmpty());
        verify(resultSet).close();
        verify(preparedStatement).close();
    }

    @Test
    @DisplayName("find treats a null status column as active and leaves absent timestamps null")
    void findDefaultsAnAbsentStatusToActive() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("id")).thenReturn("grant-1");

        var grant = store.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow();

        assertEquals(ConnectionGrant.Status.ACTIVE, grant.getStatus(), "a row written before the status column existed must still resolve");
        assertNull(grant.getExpiresAt());
        assertNull(grant.getCreatedAt());
        assertNull(grant.getUpdatedAt());
        assertNull(grant.getLastRefreshAt());
        assertNull(grant.getRefreshLeaseExpiresAt());
        assertNull(grant.getScopes());
    }

    @Test
    @DisplayName("find yields null scopes when the column holds only whitespace")
    void findReturnsNullScopesForABlankColumn() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("scopes")).thenReturn("   ");

        assertNull(store.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow().getScopes());
    }

    @Test
    @DisplayName("find yields null scopes rather than failing when the stored json is malformed")
    void findReturnsNullScopesForMalformedJson() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("scopes")).thenReturn("{not json");
        when(resultSet.getString("encrypted_access_token")).thenReturn("cipher-access");

        var grant = store.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow();

        assertNull(grant.getScopes());
        assertEquals("cipher-access", grant.getEncryptedAccessToken(), "an unreadable scope list must not cost the caller its usable token");
    }

    @Test
    @DisplayName("find wraps a database failure with its cause intact")
    void findWrapsSqlException() throws Exception {
        var boom = new SQLException("connection reset");
        when(preparedStatement.executeQuery()).thenThrow(boom);

        assertWraps("Failed to read connection grant", boom, () -> store.find(TENANT, CONNECTION, PRINCIPAL));
    }

    @Test
    @DisplayName("upsert binds every column of the grant it was handed")
    void upsertBindsEveryColumn() throws Exception {
        var grant = grant();
        grant.setStatus(ConnectionGrant.Status.EXPIRED);

        store.upsert(grant);

        verify(preparedStatement).setString(1, TENANT);
        verify(preparedStatement).setString(2, CONNECTION);
        verify(preparedStatement).setString(3, PRINCIPAL);
        verify(preparedStatement).setString(4, "cipher-access");
        verify(preparedStatement).setString(5, "iv-access");
        verify(preparedStatement).setString(6, "cipher-refresh");
        verify(preparedStatement).setString(7, "iv-refresh");
        verify(preparedStatement).setString(8, "dek-7");
        verify(preparedStatement).setTimestamp(9, Timestamp.from(EXPIRES_AT));
        verify(preparedStatement).setString(10, "[\"read\",\"write\"]");
        verify(preparedStatement).setString(11, "EXPIRED");
        verify(preparedStatement).setTimestamp(12, Timestamp.from(LAST_REFRESH_AT));
        verify(preparedStatement).executeUpdate();
        assertTrue(capturedSql().contains("ON CONFLICT (tenant_id, connection_name, principal) DO UPDATE SET"),
                "without the conflict clause a reconnect inserts a duplicate grant instead of replacing the old tokens");
    }

    @Test
    @DisplayName("upsert falls back to active when the grant carries no status")
    void upsertDefaultsANullStatusToActive() throws Exception {
        var grant = grant();
        grant.setStatus(null);

        store.upsert(grant);

        verify(preparedStatement).setString(11, "ACTIVE");
    }

    @Test
    @DisplayName("upsert writes sql nulls for an absent expiry, refresh time and scope list")
    void upsertWritesNullsForAbsentOptionalValues() throws Exception {
        var grant = grant();
        grant.setExpiresAt(null);
        grant.setLastRefreshAt(null);
        grant.setScopes(null);

        store.upsert(grant);

        verify(preparedStatement).setTimestamp(9, null);
        verify(preparedStatement).setString(10, null);
        verify(preparedStatement).setTimestamp(12, null);
    }

    @Test
    @DisplayName("upsert stores a null scope list when the scopes cannot be serialised")
    void upsertStoresNullScopesWhenSerialisationFails() throws Exception {
        var grant = grant();
        grant.setScopes(unserialisableScopes());

        store.upsert(grant);

        verify(preparedStatement).setString(10, null);
        verify(preparedStatement).setString(4, "cipher-access");
        verify(preparedStatement).executeUpdate();
    }

    @Test
    @DisplayName("upsert wraps a database failure with its cause intact")
    void upsertWrapsSqlException() throws Exception {
        var boom = new SQLException("deadlock detected");
        when(preparedStatement.executeUpdate()).thenThrow(boom);

        assertWraps("Failed to write connection grant", boom, () -> store.upsert(grant()));
    }

    @Test
    @DisplayName("claimRefresh reports success when the conditional update took the lease")
    void claimRefreshReturnsTrueWhenTheLeaseWasTaken() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertTrue(store.claimRefresh(TENANT, CONNECTION, PRINCIPAL, CLAIMANT, LEASE_EXPIRES_AT));

        verify(preparedStatement).setString(1, CLAIMANT);
        verify(preparedStatement).setTimestamp(2, Timestamp.from(LEASE_EXPIRES_AT));
        verify(preparedStatement).setString(3, TENANT);
        verify(preparedStatement).setString(4, CONNECTION);
        verify(preparedStatement).setString(5, PRINCIPAL);
        assertTrue(capturedSql().contains("refresh_in_progress IS NULL OR refresh_lease_expires_at < CURRENT_TIMESTAMP"),
                "the free-lease predicate is what makes the claim atomic; a read-then-write lets two replicas both win");
    }

    @Test
    @DisplayName("claimRefresh reports failure when another replica already holds the lease")
    void claimRefreshReturnsFalseWhenTheLeaseIsHeld() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertFalse(store.claimRefresh(TENANT, CONNECTION, PRINCIPAL, CLAIMANT, LEASE_EXPIRES_AT),
                "reporting a lost race as won sends a second token request and rotates the winner's refresh token away");
    }

    @Test
    @DisplayName("claimRefresh refuses a lease with no expiry rather than writing one nobody can reclaim")
    void claimRefreshRefusesANullLeaseExpiry() throws Exception {
        // A missing expiry is not a shorter lease, it is a permanent one: the claim
        // predicate asks whether the lease has expired, and `NULL <
        // CURRENT_TIMESTAMP` is NULL rather than true, so the row could never be
        // claimed again and refresh for that grant would be wedged for good.
        assertThrows(IllegalArgumentException.class, () -> store.claimRefresh(TENANT, CONNECTION, PRINCIPAL, CLAIMANT, null));

        verify(preparedStatement, never()).executeUpdate();
    }

    @Test
    @DisplayName("claimRefresh wraps a database failure with its cause intact")
    void claimRefreshWrapsSqlException() throws Exception {
        var boom = new SQLException("lock timeout");
        when(preparedStatement.executeUpdate()).thenThrow(boom);

        assertWraps("Failed to claim a refresh lease", boom, () -> store.claimRefresh(TENANT, CONNECTION, PRINCIPAL, CLAIMANT, LEASE_EXPIRES_AT));
    }

    @Test
    @DisplayName("completeRefresh stores a grant with no status as ACTIVE rather than throwing")
    void completeRefreshDefaultsAMissingStatus() throws Exception {
        // upsert defaulted a null status and this path dereferenced it, so one
        // grant was storable through one write path and fatal through the other
        // — and this is the path that runs after a successful token refresh,
        // where throwing discards the token the provider just issued.
        when(preparedStatement.executeUpdate()).thenReturn(1);
        var grant = grant();
        grant.setStatus(null);

        assertTrue(store.completeRefresh(grant, 7L));

        verify(preparedStatement).setString(8, "ACTIVE");
    }

    @Test
    @DisplayName("completeRefresh reports success when the version guard still held")
    void completeRefreshReturnsTrueWhenTheGuardHeld() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertTrue(store.completeRefresh(grant(), 7L));

        verify(preparedStatement).setString(1, "cipher-access");
        verify(preparedStatement).setString(2, "iv-access");
        verify(preparedStatement).setString(3, "cipher-refresh");
        verify(preparedStatement).setString(4, "iv-refresh");
        verify(preparedStatement).setString(5, "dek-7");
        verify(preparedStatement).setTimestamp(6, Timestamp.from(EXPIRES_AT));
        verify(preparedStatement).setString(7, "[\"read\",\"write\"]");
        verify(preparedStatement).setString(8, "ACTIVE");
        verify(preparedStatement).setString(9, TENANT);
        verify(preparedStatement).setString(10, CONNECTION);
        verify(preparedStatement).setString(11, PRINCIPAL);
        verify(preparedStatement).setLong(12, 7L);

        String sql = capturedSql();
        assertTrue(sql.contains("refresh_in_progress = NULL"), "finishing a refresh without releasing the lease strands it until the lease expires");
        assertTrue(sql.contains("version = version + 1"), "a refresh that leaves the version alone lets a stale writer win afterwards");
    }

    @Test
    @DisplayName("completeRefresh reports failure when the version moved on under it")
    void completeRefreshReturnsFalseWhenTheGuardWasLost() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertFalse(store.completeRefresh(grant(), 7L),
                "a lost guard means somebody else already finished; claiming otherwise would report a token that never landed");
    }

    @Test
    @DisplayName("completeRefresh wraps a database failure with its cause intact")
    void completeRefreshWrapsSqlException() throws Exception {
        var boom = new SQLException("could not serialize access");
        when(preparedStatement.executeUpdate()).thenThrow(boom);

        assertWraps("Failed to complete a refresh", boom, () -> store.completeRefresh(grant(), 7L));
    }

    @Test
    @DisplayName("releaseRefresh clears only a lease the caller itself holds")
    void releaseRefreshClearsOnlyItsOwnClaim() throws Exception {
        store.releaseRefresh(TENANT, CONNECTION, PRINCIPAL, CLAIMANT);

        verify(preparedStatement).setString(1, TENANT);
        verify(preparedStatement).setString(2, CONNECTION);
        verify(preparedStatement).setString(3, PRINCIPAL);
        verify(preparedStatement).setString(4, CLAIMANT);
        verify(preparedStatement).executeUpdate();
        assertTrue(capturedSql().contains("AND refresh_in_progress = ?"),
                "without the claimant predicate a failed replica would free a lease a healthy one is still using");
    }

    @Test
    @DisplayName("releaseRefresh swallows a database failure instead of failing the caller")
    void releaseRefreshSwallowsSqlException() throws Exception {
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("connection reset"));

        // A lease that cannot be released simply waits out its expiry, which is a
        // delay; propagating here would turn that delay into a failed refresh.
        assertDoesNotThrow(() -> store.releaseRefresh(TENANT, CONNECTION, PRINCIPAL, CLAIMANT));
        verify(preparedStatement).setString(4, CLAIMANT);
    }

    @Test
    @DisplayName("delete reports true when a grant was actually removed")
    void deleteReturnsTrueWhenARowWasRemoved() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertTrue(store.delete(TENANT, CONNECTION, PRINCIPAL));

        verify(preparedStatement).setString(1, TENANT);
        verify(preparedStatement).setString(2, CONNECTION);
        verify(preparedStatement).setString(3, PRINCIPAL);
        assertTrue(capturedSql().contains("DELETE FROM connection_grants WHERE tenant_id = ? AND connection_name = ? AND principal = ?"),
                "a delete that drops the principal from the predicate disconnects every user of the connection, not the one who asked");
    }

    @Test
    @DisplayName("delete reports false when there was nothing to disconnect")
    void deleteReturnsFalseWhenNothingMatched() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertFalse(store.delete(TENANT, CONNECTION, PRINCIPAL));
    }

    @Test
    @DisplayName("delete wraps a database failure with its cause intact")
    void deleteWrapsSqlException() throws Exception {
        var boom = new SQLException("relation does not exist");
        when(preparedStatement.executeUpdate()).thenThrow(boom);

        assertWraps("Failed to delete a connection grant", boom, () -> store.delete(TENANT, CONNECTION, PRINCIPAL));
    }

    @Test
    @DisplayName("deleteByConnection returns how many grants the connection took with it")
    void deleteByConnectionReturnsTheRowCount() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(3);

        assertEquals(3, store.deleteByConnection(TENANT, CONNECTION));

        verify(preparedStatement).setString(1, TENANT);
        verify(preparedStatement).setString(2, CONNECTION);
        verify(preparedStatement, times(2)).setString(anyInt(), anyString());
        assertTrue(capturedSql().contains("WHERE tenant_id = ? AND connection_name = ?"),
                "a delete keyed on the connection alone would wipe every tenant's grants for that name");
    }

    @Test
    @DisplayName("deleteByConnection returns zero when the connection held no grants")
    void deleteByConnectionReturnsZeroWhenNothingMatched() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertEquals(0, store.deleteByConnection(TENANT, CONNECTION));
    }

    @Test
    @DisplayName("deleteByConnection wraps a database failure with its cause intact")
    void deleteByConnectionWrapsSqlException() throws Exception {
        var boom = new SQLException("statement timeout");
        when(preparedStatement.executeUpdate()).thenThrow(boom);

        assertWraps("Failed to delete connection grants", boom, () -> store.deleteByConnection(TENANT, CONNECTION));
    }

    @Test
    @DisplayName("findByPrincipal returns one grant per row")
    void findByPrincipalReturnsEveryRow() throws Exception {
        stubTwoRows();

        List<ConnectionGrant> grants = store.findByPrincipal(TENANT, PRINCIPAL);

        assertEquals(2, grants.size());
        assertEquals("grant-1", grants.get(0).getId());
        assertEquals("jira", grants.get(0).getConnectionName());
        assertEquals(ConnectionGrant.Status.ACTIVE, grants.get(0).getStatus());
        assertEquals(3L, grants.get(0).getVersion());
        assertEquals("grant-2", grants.get(1).getId());
        assertEquals("slack", grants.get(1).getConnectionName());
        assertEquals(ConnectionGrant.Status.REVOKED, grants.get(1).getStatus(),
                "collapsing rows onto the first one would show a revoked account as still linked");
        assertEquals(9L, grants.get(1).getVersion());

        verify(preparedStatement).setString(1, TENANT);
        verify(preparedStatement).setString(2, PRINCIPAL);
        verify(resultSet).close();
    }

    @Test
    @DisplayName("findByPrincipal returns an empty list when the principal has linked nothing")
    void findByPrincipalReturnsEmptyList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertTrue(store.findByPrincipal(TENANT, PRINCIPAL).isEmpty());
        verify(resultSet).close();
    }

    @Test
    @DisplayName("findByPrincipal wraps a database failure with its cause intact")
    void findByPrincipalWrapsSqlException() throws Exception {
        var boom = new SQLException("connection reset");
        when(preparedStatement.executeQuery()).thenThrow(boom);

        assertWraps("Failed to list connection grants", boom, () -> store.findByPrincipal(TENANT, PRINCIPAL));
    }

    @Test
    @DisplayName("findByTenant returns every grant in the tenant, keyed on the tenant alone")
    void findByTenantReturnsEveryRow() throws Exception {
        stubTwoRows();

        List<ConnectionGrant> grants = store.findByTenant(TENANT);

        assertEquals(2, grants.size());
        assertEquals("grant-1", grants.get(0).getId());
        assertEquals("grant-2", grants.get(1).getId());
        assertEquals(3L, grants.get(0).getVersion(), "the rotation sweep spends this version on its re-seal guard");
        assertEquals(9L, grants.get(1).getVersion());

        verify(preparedStatement).setString(1, TENANT);
        verify(preparedStatement, times(1)).setString(anyInt(), anyString());
    }

    @Test
    @DisplayName("findByTenant returns an empty list for a tenant with no grants")
    void findByTenantReturnsEmptyList() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertTrue(store.findByTenant(TENANT).isEmpty());
        verify(resultSet).close();
    }

    @Test
    @DisplayName("findByTenant wraps a database failure with a message of its own")
    void findByTenantWrapsSqlException() throws Exception {
        var boom = new SQLException("out of memory");
        when(preparedStatement.executeQuery()).thenThrow(boom);

        // Deliberately not the same wording as findByPrincipal: the tenant-wide read is
        // the rotation sweep, and telling the two apart in a log matters.
        assertWraps("Failed to list connection grants for tenant", boom, () -> store.findByTenant(TENANT));
    }

    @Test
    @DisplayName("updateSealedTokens reports success and rewrites only the ciphertext columns")
    void updateSealedTokensReturnsTrueWhenTheGuardHeld() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(1);

        assertTrue(store.updateSealedTokens(grant(), 4L));

        verify(preparedStatement).setString(1, "cipher-access");
        verify(preparedStatement).setString(2, "iv-access");
        verify(preparedStatement).setString(3, "cipher-refresh");
        verify(preparedStatement).setString(4, "iv-refresh");
        verify(preparedStatement).setString(5, "dek-7");
        verify(preparedStatement).setString(6, TENANT);
        verify(preparedStatement).setString(7, CONNECTION);
        verify(preparedStatement).setString(8, PRINCIPAL);
        verify(preparedStatement).setLong(9, 4L);

        String sql = capturedSql();
        assertTrue(sql.contains("AND version = ?"), "without the version predicate a re-seal overwrites a token a refresh has just written");
        assertFalse(sql.contains("version = version + 1"), "bumping the version would fail the CAS of a refresh that has nothing wrong with it");
        assertFalse(sql.contains("refresh_in_progress"), "touching a lease it does not own would reopen the double-refresh window");
        assertFalse(sql.contains("updated_at"), "a rotation is not a grant update and must not look like one");
        assertTrue(sql.startsWith("UPDATE"), "a conditional write must never be an upsert, or a revoked grant is resurrected");
    }

    @Test
    @DisplayName("updateSealedTokens reports failure when the version moved on under it")
    void updateSealedTokensReturnsFalseWhenTheGuardWasLost() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertFalse(store.updateSealedTokens(grant(), 4L),
                "a refresh that landed in between already sealed with the current dek, so the work is done rather than owed");
    }

    @Test
    @DisplayName("updateSealedTokens wraps a database failure with its cause intact")
    void updateSealedTokensWrapsSqlException() throws Exception {
        var boom = new SQLException("disk full");
        when(preparedStatement.executeUpdate()).thenThrow(boom);

        assertWraps("Failed to write re-sealed connection grant tokens", boom, () -> store.updateSealedTokens(grant(), 4L));
    }

    @Test
    @DisplayName("countByStatus returns the counted rows for the requested status")
    void countByStatusReturnsTheCount() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getLong(1)).thenReturn(12L);

        assertEquals(12L, store.countByStatus(TENANT, ConnectionGrant.Status.REVOKED));

        verify(preparedStatement).setString(1, TENANT);
        verify(preparedStatement).setString(2, "REVOKED");
        verify(resultSet).close();
    }

    @Test
    @DisplayName("countByStatus returns zero when the count query yields no row at all")
    void countByStatusReturnsZeroWithoutARow() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertEquals(0L, store.countByStatus(TENANT, ConnectionGrant.Status.REFRESH_FAILED),
                "the gauge must read zero rather than blow up when the table is empty");
    }

    @Test
    @DisplayName("countByStatus wraps a database failure with its cause intact")
    void countByStatusWrapsSqlException() throws Exception {
        var boom = new SQLException("connection reset");
        when(preparedStatement.executeQuery()).thenThrow(boom);

        assertWraps("Failed to count connection grants", boom, () -> store.countByStatus(TENANT, ConnectionGrant.Status.ACTIVE));
    }
}
