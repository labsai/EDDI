/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The PostgreSQL half of the OAuth state store.
 * <p>
 * Two things are pinned here that no integration test would notice failing: the
 * exact predicate of the {@code UPDATE … RETURNING} — which is the only thing
 * making a state single-use — and that the lazy schema step never turns a
 * missing DDL grant into a broken account link.
 * {@link MongoOAuthStateStoreTest} pins the same guarantees on the other
 * backend.
 */
class PostgresOAuthStateStoreUnitTest {

    private static final String STATE_TOKEN = "s-9f2c4a1b7e";
    private static final String TENANT = "tenant-1";
    private static final String CONNECTION = "google-mail";
    private static final String PRINCIPAL = "user@example.com";
    private static final String VERIFIER = "verifier-abc";
    private static final String REDIRECT_URI = "https://eddi.example.com/connections/callback";
    private static final String RETURN_TO = "/manage/connections";
    private static final String NONCE_HASH = "nonce-sha256";

    private static final Instant CREATED_AT = Instant.parse("2026-08-22T10:00:00Z");
    private static final Instant EXPIRES_AT = CREATED_AT.plus(10, ChronoUnit.MINUTES);
    private static final Instant CONSUMED_AT = CREATED_AT.plus(30, ChronoUnit.SECONDS);

    /**
     * A minute of slack: enough that a slow CI box never fails, far too little for
     * a stale or hard-coded instant to pass.
     */
    private static final long CLOCK_TOLERANCE_MILLIS = 60_000L;

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

    private PostgresOAuthStateStore store;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);
        lenient().when(dataSourceInstance.get()).thenReturn(dataSource);
        lenient().when(dataSource.getConnection()).thenReturn(connection);
        lenient().when(connection.createStatement()).thenReturn(statement);
        lenient().when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);

        store = new PostgresOAuthStateStore(dataSourceInstance);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mocks != null) {
            mocks.close();
        }
    }

    // ==================== schema ====================

    @Test
    @DisplayName("schema — the table, the retrofitted nonce column and the expiry index are created in that order")
    void createsSchemaOnFirstUse() throws Exception {
        store.deleteExpired();

        ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
        verify(statement, times(3)).execute(ddl.capture());
        List<String> executed = ddl.getAllValues();

        assertTrue(executed.get(0).contains("CREATE TABLE IF NOT EXISTS connection_oauth_states"), executed.get(0));
        // The ALTER follows the CREATE because a deployment that predates browser
        // binding already has the table; without this step every account link would
        // fail on an insert naming a column that does not exist.
        assertTrue(executed.get(1).contains("ADD COLUMN IF NOT EXISTS nonce_hash"), executed.get(1));
        assertTrue(executed.get(2).contains("CREATE INDEX IF NOT EXISTS idx_oauth_state_expires"), executed.get(2));

        // The primary key is what stops two rows sharing a state token, which would
        // let the same token be claimed twice and defeat single use outright.
        assertTrue(executed.get(0).contains("state VARCHAR(128) PRIMARY KEY"), executed.get(0));
        assertTrue(executed.get(0).contains("consumed_at TIMESTAMP"), "the redemption stamp is the single-use marker: " + executed.get(0));
        assertTrue(executed.get(0).contains("expires_at TIMESTAMP NOT NULL"),
                "a nullable expiry would be a state that never times out: " + executed.get(0));
    }

    @Test
    @DisplayName("schema — a successful initialisation is not repeated on later calls")
    void schemaInitialisesOnce() throws Exception {
        store.deleteExpired();
        store.deleteExpired();
        store.deleteExpired();

        // Three DDL round trips per swept hour would be pure waste; the volatile flag
        // is what keeps the cost to one per JVM.
        verify(connection, times(1)).createStatement();
        verify(statement, times(3)).execute(anyString());
    }

    @Test
    @DisplayName("schema — a failed initialisation is retried on the next call rather than latched off")
    void schemaRetriesAfterFailure() throws Exception {
        // The flag is only set inside the try, so a transient failure — a lock on the
        // table during a rolling deploy, say — leaves the next call free to retry
        // instead of running forever against a table that was never created.
        doThrow(new SQLException("could not obtain lock on relation")).doReturn(true).when(statement).execute(anyString());

        store.deleteExpired();
        store.deleteExpired();
        store.deleteExpired();

        verify(connection, times(2)).createStatement();
        verify(statement, times(4)).execute(anyString());
    }

    @Test
    @DisplayName("schema — an initialisation failure is logged, not thrown, and the operation still runs")
    void schemaFailureDoesNotAbortTheCall() throws Exception {
        // On a deployment whose table already exists and whose app user holds no DDL
        // grant, refusing to continue would take account linking down over a step that
        // had nothing left to do.
        doThrow(new SQLException("permission denied for schema public")).when(connection).createStatement();
        when(preparedStatement.executeUpdate()).thenReturn(2);

        assertEquals(2, store.deleteExpired());
        verify(preparedStatement).executeUpdate();
    }

    // ==================== create ====================

    @Test
    @DisplayName("create — every column of the in-flight flow is bound, in order, and consumed_at is left NULL")
    void createBindsEveryColumn() throws Exception {
        store.create(newState());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertTrue(sql.getValue().contains("INSERT INTO connection_oauth_states"), sql.getValue());
        // claim() narrows on `consumed_at IS NULL`. Naming the column on insert — even
        // to write NULL explicitly — is the kind of change that silently breaks that
        // predicate, so the omission is pinned here rather than left to review.
        assertFalse(sql.getValue().contains("consumed_at"), "a freshly issued state is unredeemed: " + sql.getValue());

        verify(preparedStatement).setString(1, STATE_TOKEN);
        verify(preparedStatement).setString(2, TENANT);
        verify(preparedStatement).setString(3, CONNECTION);
        verify(preparedStatement).setString(4, PRINCIPAL);
        verify(preparedStatement).setString(5, VERIFIER);
        verify(preparedStatement).setString(6, REDIRECT_URI);
        verify(preparedStatement).setString(7, RETURN_TO);
        verify(preparedStatement).setString(8, NONCE_HASH);
        verify(preparedStatement).setTimestamp(9, Timestamp.from(CREATED_AT));
        verify(preparedStatement).setTimestamp(10, Timestamp.from(EXPIRES_AT));
        verify(preparedStatement).executeUpdate();

        InOrder order = inOrder(connection);
        order.verify(connection).createStatement();
        order.verify(connection).prepareStatement(anyString());
    }

    @Test
    @DisplayName("create — a write failure is wrapped, so no flow starts believing its state was stored")
    void createWrapsSqlException() throws Exception {
        // The browser is redirected to the provider straight after this returns. A
        // swallowed failure would surface much later as an unexplained invalid state.
        SQLException cause = new SQLException("duplicate key value violates unique constraint");
        when(preparedStatement.executeUpdate()).thenThrow(cause);
        OAuthState state = newState();

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> store.create(state));

        assertEquals("Failed to store an OAuth state", thrown.getMessage());
        assertSame(cause, thrown.getCause());
    }

    // ==================== claim ====================

    @Test
    @DisplayName("claim — one UPDATE … RETURNING carries the whole compare-and-swap")
    void claimBuildsTheCompareAndSwap() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);

        store.claim(STATE_TOKEN);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        String claimSql = sql.getValue();

        assertTrue(claimSql.startsWith("UPDATE connection_oauth_states"), claimSql);
        assertTrue(claimSql.contains("consumed_at IS NULL"), "without this clause a redeemed state could be redeemed again: " + claimSql);
        assertTrue(claimSql.contains("expires_at > ?"), "expiry is enforced in the predicate, not by a later read: " + claimSql);
        assertTrue(claimSql.contains("RETURNING"), "the row must come back from the same statement that claimed it: " + claimSql);
        // A SELECT followed by an UPDATE is the bug this shape exists to prevent: two
        // concurrent callbacks would both read consumed_at NULL and both proceed.
        verify(connection, times(1)).prepareStatement(anyString());

        ArgumentCaptor<Timestamp> consumedStamp = ArgumentCaptor.forClass(Timestamp.class);
        ArgumentCaptor<Timestamp> expiryCutoff = ArgumentCaptor.forClass(Timestamp.class);
        verify(preparedStatement).setTimestamp(eq(1), consumedStamp.capture());
        verify(preparedStatement).setString(2, STATE_TOKEN);
        verify(preparedStatement).setTimestamp(eq(3), expiryCutoff.capture());

        // One reading drives both halves. Two would let a row be stamped consumed at
        // an instant it was not yet eligible to be claimed at.
        assertEquals(consumedStamp.getValue(), expiryCutoff.getValue(), "the redemption stamp and the expiry cutoff are one clock reading");
        assertTrue(Math.abs(consumedStamp.getValue().getTime() - System.currentTimeMillis()) < CLOCK_TOLERANCE_MILLIS,
                "the cutoff is bound from this JVM — the clock that wrote expires_at — never from the database's CURRENT_TIMESTAMP");
    }

    @Test
    @DisplayName("claim — the winner gets the whole row back, mapped column for column")
    void claimReturnsTheRow() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        stubClaimedRow();

        OAuthState claimed = store.claim(STATE_TOKEN).orElseThrow();

        assertEquals(STATE_TOKEN, claimed.getState());
        assertEquals(TENANT, claimed.getTenantId());
        assertEquals(CONNECTION, claimed.getConnectionName());
        // Tenant, connection and principal come off the row, never off the callback's
        // query string — that is the entire reason the row exists.
        assertEquals(PRINCIPAL, claimed.getPrincipal());
        assertEquals(VERIFIER, claimed.getCodeVerifier());
        assertEquals(REDIRECT_URI, claimed.getRedirectUri());
        assertEquals(RETURN_TO, claimed.getReturnTo());
        assertEquals(NONCE_HASH, claimed.getNonceHash());
        assertEquals(CREATED_AT, claimed.getCreatedAt());
        assertEquals(EXPIRES_AT, claimed.getExpiresAt());
        assertEquals(CONSUMED_AT, claimed.getConsumedAt());
        verify(resultSet).close();
    }

    @Test
    @DisplayName("claim — absent, already redeemed and expired are one indistinguishable outcome")
    void claimLosesWhenNoRowMatches() throws Exception {
        // All three fold into the same zero-row result by construction. Telling them
        // apart would hand an attacker an oracle for guessing live state tokens.
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        assertTrue(store.claim(STATE_TOKEN).isEmpty());
        verify(resultSet).close();
    }

    @Test
    @DisplayName("claim — a second attempt on the same state loses, because the first consumed it")
    void claimIsSingleUse() throws Exception {
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true, false);
        stubClaimedRow();

        assertTrue(store.claim(STATE_TOKEN).isPresent(), "the first caller wins");
        assertTrue(store.claim(STATE_TOKEN).isEmpty(), "the second caller must not also redeem the authorization code");
    }

    @Test
    @DisplayName("claim — a null or blank state never reaches the UPDATE")
    void claimRejectsEmptyState() throws Exception {
        assertTrue(store.claim(null).isEmpty());
        assertTrue(store.claim("   ").isEmpty());

        verify(connection, never()).prepareStatement(anyString());
        // createSchema() runs ahead of the guard, so even a malformed callback pays
        // for the DDL check once. Pinned because it is a real, observable cost of the
        // current ordering, not because it is desirable.
        verify(connection, atLeastOnce()).createStatement();
    }

    @Test
    @DisplayName("claim — a row with null timestamps maps to null instants instead of throwing")
    void claimToleratesNullTimestamps() throws Exception {
        // A row written by a release that predated one of these columns must fail the
        // flow on the missing binding, not on a mapper NPE that reads as a 500.
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("state")).thenReturn(STATE_TOKEN);
        when(resultSet.getTimestamp(anyString())).thenReturn(null);

        OAuthState claimed = store.claim(STATE_TOKEN).orElseThrow();

        assertEquals(STATE_TOKEN, claimed.getState());
        assertNull(claimed.getCreatedAt());
        assertNull(claimed.getExpiresAt());
        assertNull(claimed.getConsumedAt());
        assertNull(claimed.getNonceHash(), "an unbound row reads as unbound, which the caller rejects on its own terms");
    }

    @Test
    @DisplayName("claim — a query failure is wrapped, never reported as a lost claim")
    void claimWrapsSqlException() throws Exception {
        // Returning empty here would make an outage indistinguishable from a replay,
        // so a database blip would be reported to every user as an invalid state.
        SQLException cause = new SQLException("connection reset by peer");
        when(preparedStatement.executeQuery()).thenThrow(cause);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> store.claim(STATE_TOKEN));

        assertEquals("Failed to claim an OAuth state", thrown.getMessage());
        assertSame(cause, thrown.getCause());
    }

    // ==================== deleteExpired ====================

    @Test
    @DisplayName("deleteExpired — deletes only rows already past their expiry, and reports how many")
    void deleteExpiredRemovesPastRows() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(5);

        assertEquals(5, store.deleteExpired());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        // Pinned in full: a predicate that widened, or a DELETE that lost its WHERE,
        // would take every live in-flight flow with it and read as a mass logout.
        assertEquals("DELETE FROM connection_oauth_states WHERE expires_at < ?", sql.getValue());

        ArgumentCaptor<Timestamp> cutoff = ArgumentCaptor.forClass(Timestamp.class);
        verify(preparedStatement).setTimestamp(eq(1), cutoff.capture());
        assertTrue(Math.abs(cutoff.getValue().getTime() - System.currentTimeMillis()) < CLOCK_TOLERANCE_MILLIS,
                "the sweep uses the same JVM clock claim() does, so the two agree on what is still live");
    }

    @Test
    @DisplayName("deleteExpired — a sweep that deletes nothing reports zero")
    void deleteExpiredReportsZero() throws Exception {
        when(preparedStatement.executeUpdate()).thenReturn(0);

        assertEquals(0, store.deleteExpired());
    }

    @Test
    @DisplayName("deleteExpired — a failed sweep reports nothing deleted instead of throwing")
    void deleteExpiredSwallowsSqlException() throws Exception {
        // Deliberately unlike create() and claim(), which wrap and throw. This is
        // retention, not enforcement: claim() re-checks expiry itself, so a row that
        // outlives the sweep is already unusable and the next run simply retries.
        when(preparedStatement.executeUpdate()).thenThrow(new SQLException("deadlock detected"));

        assertEquals(0, store.deleteExpired());
    }

    // ==================== Helpers ====================

    private void stubClaimedRow() throws SQLException {
        when(resultSet.getString("state")).thenReturn(STATE_TOKEN);
        when(resultSet.getString("tenant_id")).thenReturn(TENANT);
        when(resultSet.getString("connection_name")).thenReturn(CONNECTION);
        when(resultSet.getString("principal")).thenReturn(PRINCIPAL);
        when(resultSet.getString("code_verifier")).thenReturn(VERIFIER);
        when(resultSet.getString("redirect_uri")).thenReturn(REDIRECT_URI);
        when(resultSet.getString("return_to")).thenReturn(RETURN_TO);
        when(resultSet.getString("nonce_hash")).thenReturn(NONCE_HASH);
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.from(CREATED_AT));
        when(resultSet.getTimestamp("expires_at")).thenReturn(Timestamp.from(EXPIRES_AT));
        when(resultSet.getTimestamp("consumed_at")).thenReturn(Timestamp.from(CONSUMED_AT));
    }

    private static OAuthState newState() {
        var state = new OAuthState();
        state.setState(STATE_TOKEN);
        state.setTenantId(TENANT);
        state.setConnectionName(CONNECTION);
        state.setPrincipal(PRINCIPAL);
        state.setCodeVerifier(VERIFIER);
        state.setRedirectUri(REDIRECT_URI);
        state.setReturnTo(RETURN_TO);
        state.setNonceHash(NONCE_HASH);
        state.setCreatedAt(CREATED_AT);
        state.setExpiresAt(EXPIRES_AT);
        return state;
    }
}
