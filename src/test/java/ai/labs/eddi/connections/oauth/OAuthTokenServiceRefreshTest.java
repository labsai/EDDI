/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionResolver;
import ai.labs.eddi.connections.CredentialReferenceResolver;
import ai.labs.eddi.connections.grants.ConnectionGrant;
import ai.labs.eddi.connections.grants.InMemoryConnectionGrantStore;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.model.EncryptedDek;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The refresh race, and the failure semantics around it.
 * <p>
 * These are the tests the plan asks for by name, because the defect they pin is
 * invisible until it happens in production: with a rotating refresh token, two
 * simultaneous refreshes mean the second invalidates the first and a user who
 * did nothing wrong is silently logged out.
 */
class OAuthTokenServiceRefreshTest {

    private static final String TENANT = "default";
    private static final String CONNECTION = "drive";
    private static final String PRINCIPAL = "alice";

    /**
     * The generation the vault seals with here. A grant records this, not the bare
     * tenant id — which named no key at all, so after a rotation the ciphertext
     * could only be opened by whichever generation happened to be newest at read
     * time.
     */
    private static final String ACTIVE_DEK = EncryptedDek.dekId(TENANT, 2);

    /**
     * What a seeded grant was sealed under: an older generation, the ordinary state
     * of a row a rotation sweep has not reached yet.
     */
    private static final String STORED_DEK = EncryptedDek.dekId(TENANT, 1);

    private InMemoryConnectionGrantStore grantStore;
    private OAuthTokenClient tokenClient;
    private ISecretProvider secretProvider;
    private final AtomicInteger tokenRequests = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        grantStore = new InMemoryConnectionGrantStore();
        tokenClient = mock(OAuthTokenClient.class);
        secretProvider = mock(ISecretProvider.class);
        tokenRequests.set(0);

        // A trivially reversible "cipher": the property under test is the refresh
        // protocol, not the crypto, and a real vault here would need a master key.
        // It answers with a generation-qualified dekId because that is what the real
        // vault returns, and the grant is expected to store it verbatim.
        lenient().when(secretProvider.seal(anyString(), anyString()))
                .thenAnswer(i -> new ISecretProvider.SealedValue("sealed:" + i.getArgument(1), "iv", ACTIVE_DEK));
        lenient().when(secretProvider.unseal(anyString(), any()))
                .thenAnswer(i -> i.<ISecretProvider.SealedValue>getArgument(1).ciphertext().substring("sealed:".length()));
    }

    private OAuthTokenService service() {
        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(secretResolver.resolveValue(anyString())).thenReturn("client-secret-value");
        return new OAuthTokenService(grantStore, tokenClient, secretProvider, new CredentialReferenceResolver(secretResolver, globalVariableResolver),
                new SimpleMeterRegistry());
    }

    private static ConnectionConfiguration connection() {
        var connection = new ConnectionConfiguration();
        connection.setName(CONNECTION);
        connection.setTenantId(TENANT);
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.PER_USER);
        connection.setBaseUrlAllowlist(List.of("https://api.example.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://auth.example.com/token");
        oauth.setAuthorizationUrl("https://auth.example.com/authorize");
        oauth.setClientId("client");
        oauth.setClientSecret("${vault:client-secret}");
        connection.setOauth(oauth);
        return connection;
    }

    private void seedExpiredGrant() {
        var grant = new ConnectionGrant();
        grant.setTenantId(TENANT);
        grant.setConnectionName(CONNECTION);
        grant.setPrincipal(PRINCIPAL);
        grant.setEncryptedAccessToken("sealed:old-access");
        grant.setAccessTokenIv("iv");
        grant.setEncryptedRefreshToken("sealed:old-refresh");
        grant.setRefreshTokenIv("iv");
        grant.setDekId(STORED_DEK);
        grant.setExpiresAt(Instant.now().minusSeconds(60));
        grant.setStatus(ConnectionGrant.Status.ACTIVE);
        grantStore.seed(grant);
    }

    /** Counts calls and takes long enough for a second caller to contend. */
    private void slowRefreshReturning(String accessToken) {
        when(tokenClient.refresh(any(), anyString(), anyString())).thenAnswer(invocation -> {
            tokenRequests.incrementAndGet();
            Thread.sleep(300);
            return new TokenResponse(accessToken, "new-refresh", Duration.ofHours(1), List.of());
        });
    }

    @Test
    @DisplayName("two concurrent resolves on one instance produce exactly one token request")
    void singleFlightWithinOneReplica() throws Exception {
        seedExpiredGrant();
        slowRefreshReturning("fresh-access");
        OAuthTokenService service = service();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Future<String> first = pool.submit(() -> {
                start.await();
                return service.accessToken(connection(), PRINCIPAL);
            });
            Future<String> second = pool.submit(() -> {
                start.await();
                return service.accessToken(connection(), PRINCIPAL);
            });
            start.countDown();

            assertEquals("fresh-access", first.get(10, TimeUnit.SECONDS));
            assertEquals("fresh-access", second.get(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, tokenRequests.get(), "a second token request would invalidate the first one's refresh token");
    }

    @Test
    @DisplayName("two SEPARATE instances contending on one row still produce exactly one token request")
    void claimIsTheCrossReplicaGate() throws Exception {
        // The case the in-process map cannot cover, and the reason the claim exists
        // at all: two replicas share the row and nothing else.
        seedExpiredGrant();
        slowRefreshReturning("fresh-access");
        OAuthTokenService replicaOne = service();
        OAuthTokenService replicaTwo = service();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            Future<String> first = pool.submit(() -> {
                start.await();
                return replicaOne.accessToken(connection(), PRINCIPAL);
            });
            Future<String> second = pool.submit(() -> {
                start.await();
                return replicaTwo.accessToken(connection(), PRINCIPAL);
            });
            start.countDown();

            assertNotNull(first.get(10, TimeUnit.SECONDS));
            assertEquals("fresh-access", second.get(10, TimeUnit.SECONDS), "the non-claimant must adopt the claimant's token, not mint its own");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, tokenRequests.get(), "the claim, not the version CAS, is what stops the second token request");
    }

    @Test
    @DisplayName("a live access token is returned without contacting the provider at all")
    void usesUnexpiredToken() throws Exception {
        var grant = new ConnectionGrant();
        grant.setTenantId(TENANT);
        grant.setConnectionName(CONNECTION);
        grant.setPrincipal(PRINCIPAL);
        grant.setEncryptedAccessToken("sealed:still-good");
        grant.setAccessTokenIv("iv");
        grant.setDekId(STORED_DEK);
        grant.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        grant.setStatus(ConnectionGrant.Status.ACTIVE);
        grantStore.seed(grant);

        assertEquals("still-good", service().accessToken(connection(), PRINCIPAL));
        assertEquals(0, tokenRequests.get());

        var sealed = ArgumentCaptor.forClass(ISecretProvider.SealedValue.class);
        verify(secretProvider).unseal(eq(TENANT), sealed.capture());
        assertEquals(STORED_DEK, sealed.getValue().dekId(),
                "a row the last rotation has not swept yet must be opened with the generation it names, not with whichever is newest");
    }

    @Test
    @DisplayName("invalid_grant is terminal: the grant is marked REFRESH_FAILED and the user must reconnect")
    void terminalFailureMarksGrantDead() {
        seedExpiredGrant();
        when(tokenClient.refresh(any(), anyString(), anyString())).thenThrow(
                new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE, "The provider rejected the grant"));

        var error = assertThrows(ConnectionException.class, () -> service().accessToken(connection(), PRINCIPAL));

        assertEquals(ConnectionException.Reason.GRANT_UNUSABLE, error.getReason());
        assertEquals(ConnectionGrant.Status.REFRESH_FAILED, grantStore.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow().getStatus());
    }

    @Test
    @DisplayName("a transport failure is NOT terminal — the grant survives a provider blip")
    void transientFailureLeavesGrantUsable() {
        seedExpiredGrant();
        when(tokenClient.refresh(any(), anyString(), anyString())).thenThrow(
                new ConnectionException(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, "Token endpoint is unreachable"));

        var error = assertThrows(ConnectionException.class, () -> service().accessToken(connection(), PRINCIPAL));

        assertEquals(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, error.getReason());
        assertEquals(ConnectionGrant.Status.ACTIVE, grantStore.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow().getStatus(),
                "conflating a provider outage with a revocation logs every user of the connection out");
    }

    @Test
    @DisplayName("a provider that does not rotate keeps the refresh token it already had")
    void keepsRefreshTokenWhenProviderDoesNotRotate() throws Exception {
        seedExpiredGrant();
        when(tokenClient.refresh(any(), anyString(), anyString()))
                .thenReturn(new TokenResponse("fresh-access", null, Duration.ofHours(1), List.of()));

        assertEquals("fresh-access", service().accessToken(connection(), PRINCIPAL));

        assertEquals("sealed:old-refresh", grantStore.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow().getEncryptedRefreshToken(),
                "overwriting it with null would make the NEXT refresh impossible");
        verify(secretProvider, times(1)).unseal(eq(TENANT), any());
    }

    @Test
    @DisplayName("the carried-forward refresh token is the plaintext in hand, never a second unseal")
    void doesNotUnsealTheRefreshTokenTwice() throws Exception {
        // The second unseal had a null-returning failure path, so one vault blip in
        // the window between the two calls persisted a grant with NO refresh token
        // and the next refresh failed terminally — a reconnect demanded for an error
        // that fixes itself.
        seedExpiredGrant();
        when(tokenClient.refresh(any(), anyString(), anyString()))
                .thenReturn(new TokenResponse("fresh-access", null, Duration.ofHours(1), List.of()));
        // doAnswer/doReturn, not when(...): when() CALLS the mock, and the answer
        // installed in setUp dereferences its argument, so re-stubbing this way would
        // throw inside the stubbing line itself.
        doAnswer(i -> i.<ISecretProvider.SealedValue>getArgument(1).ciphertext().substring("sealed:".length()))
                .doReturn(null)
                .when(secretProvider).unseal(anyString(), any());

        assertEquals("fresh-access", service().accessToken(connection(), PRINCIPAL));

        assertEquals("sealed:old-refresh", grantStore.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow().getEncryptedRefreshToken(),
                "a second unseal would have returned null here and silently dropped the refresh token");
    }

    @Test
    @DisplayName("a refreshed grant records the DEK generation that sealed it")
    void recordsTheSealingDekGeneration() {
        seedExpiredGrant();
        when(tokenClient.refresh(any(), anyString(), anyString()))
                .thenReturn(new TokenResponse("fresh-access", "new-refresh", Duration.ofHours(1), List.of()));

        service().accessToken(connection(), PRINCIPAL);

        assertEquals(ACTIVE_DEK, grantStore.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow().getDekId(),
                "the tenant id named no key at all; the row must say which generation opens its ciphertext");
    }

    @Test
    @DisplayName("a short-lived token is usable at all: the expiry margin never exceeds half its lifetime")
    void halvesTheMarginForAShortLivedToken() {
        // A provider answering expires_in=20 is legal — RFC 6749 sets no floor. With
        // a flat 30-second margin such a token is unusable the instant it is stored,
        // so every call refreshes and the connection hammers the token endpoint.
        seedExpiredGrant();
        when(tokenClient.refresh(any(), anyString(), anyString())).thenAnswer(invocation -> {
            tokenRequests.incrementAndGet();
            return new TokenResponse("short-lived", "new-refresh", Duration.ofSeconds(20), List.of());
        });
        OAuthTokenService service = service();

        assertEquals("short-lived", service.accessToken(connection(), PRINCIPAL));
        assertEquals("short-lived", service.accessToken(connection(), PRINCIPAL));

        assertEquals(1, tokenRequests.get(), "the second call must adopt the token it just stored, not refresh again");
    }

    @Test
    @DisplayName("a REFRESH_FAILED client_credentials grant is re-minted, not left dead")
    void reMintsARejectedServiceGrant() {
        // Re-authenticating IS the client_credentials renewal path: there is no
        // refresh token that could have gone stale and no human to bring back. Left
        // terminal, one rejected mint parks a row no API can clear.
        var connection = connection();
        connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        connection.setBinding(Binding.SERVICE);
        var dead = new ConnectionGrant();
        dead.setTenantId(TENANT);
        dead.setConnectionName(CONNECTION);
        dead.setPrincipal(ConnectionResolver.SERVICE_PRINCIPAL);
        dead.setDekId(STORED_DEK);
        dead.setStatus(ConnectionGrant.Status.REFRESH_FAILED);
        grantStore.seed(dead);
        when(tokenClient.clientCredentials(any(), anyString()))
                .thenReturn(new TokenResponse("re-minted", null, Duration.ofHours(1), List.of()));

        assertEquals("re-minted", service().accessToken(connection, ConnectionResolver.SERVICE_PRINCIPAL));

        var stored = grantStore.find(TENANT, CONNECTION, ConnectionResolver.SERVICE_PRINCIPAL).orElseThrow();
        assertEquals(ConnectionGrant.Status.ACTIVE, stored.getStatus(), "the upsert must replace the dead row, not add a second one beside it");
        assertEquals(1, grantStore.findByPrincipal(TENANT, ConnectionResolver.SERVICE_PRINCIPAL).size());
    }

    @Test
    @DisplayName("a REVOKED client_credentials grant stays refused, because somebody decided it should stop working")
    void doesNotReMintARevokedServiceGrant() {
        var connection = connection();
        connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        connection.setBinding(Binding.SERVICE);
        var revoked = new ConnectionGrant();
        revoked.setTenantId(TENANT);
        revoked.setConnectionName(CONNECTION);
        revoked.setPrincipal(ConnectionResolver.SERVICE_PRINCIPAL);
        revoked.setStatus(ConnectionGrant.Status.REVOKED);
        grantStore.seed(revoked);

        var error = assertThrows(ConnectionException.class,
                () -> service().accessToken(connection, ConnectionResolver.SERVICE_PRINCIPAL));

        assertEquals(ConnectionException.Reason.GRANT_UNUSABLE, error.getReason());
    }

    @Test
    @DisplayName("a client_credentials connection mints its grant on first use, with no human step")
    void mintsServiceGrantOnFirstUse() {
        var connection = connection();
        connection.setAuthType(AuthType.OAUTH2_CLIENT_CREDENTIALS);
        connection.setBinding(Binding.SERVICE);
        when(tokenClient.clientCredentials(any(), anyString()))
                .thenReturn(new TokenResponse("service-access", null, Duration.ofHours(1), List.of()));

        assertEquals("service-access", service().accessToken(connection, ConnectionResolver.SERVICE_PRINCIPAL));
        assertTrue(grantStore.find(TENANT, CONNECTION, ConnectionResolver.SERVICE_PRINCIPAL).isPresent());
    }

    @Test
    @DisplayName("a PER_USER connection with no grant says 'not connected', not 'broken'")
    void reportsNotConnected() {
        var error = assertThrows(ConnectionException.class, () -> service().accessToken(connection(), PRINCIPAL));

        assertEquals(ConnectionException.Reason.NOT_CONNECTED, error.getReason());
        assertTrue(error.getMessage().contains("authorize"), "the message must name the action that fixes it: " + error.getMessage());
    }

    @Test
    @DisplayName("a REVOKED grant is refused without a token request")
    void refusesRevokedGrant() {
        seedExpiredGrant();
        var grant = grantStore.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow();
        grant.setStatus(ConnectionGrant.Status.REVOKED);
        grantStore.seed(grant);

        var error = assertThrows(ConnectionException.class, () -> service().accessToken(connection(), PRINCIPAL));

        assertEquals(ConnectionException.Reason.GRANT_UNUSABLE, error.getReason());
        assertEquals(0, tokenRequests.get());
    }

    @Test
    @DisplayName("the refresh lease outlasts the LONGEST timeout a connection can configure")
    void leaseOutlastsEveryConfigurableTimeout() {
        // Against the ceiling, not the default. Checking the default left the hole
        // open to any connection that set a longer timeoutMs — which is exactly the
        // config an operator writes after a provider has been slow once — and a
        // lease that expires mid-flight brings the double refresh straight back.
        assertTrue(OAuthTokenService.REFRESH_LEASE.compareTo(OAuthTokenClient.MAX_TIMEOUT) > 0);
        assertTrue(OAuthTokenClient.MAX_TIMEOUT.compareTo(OAuthTokenClient.DEFAULT_TIMEOUT) >= 0);
    }

    @Test
    @DisplayName("a connection's timeout is clamped to the ceiling the lease depends on")
    void clampsOverlongConnectionTimeout() {
        var connection = connection();
        connection.setTimeoutMs((int) OAuthTokenClient.MAX_TIMEOUT.plusSeconds(60).toMillis());

        assertEquals(OAuthTokenClient.MAX_TIMEOUT, OAuthTokenClient.effectiveTimeout(connection));

        connection.setTimeoutMs(5_000);
        assertEquals(Duration.ofSeconds(5), OAuthTokenClient.effectiveTimeout(connection));

        connection.setTimeoutMs(null);
        assertEquals(OAuthTokenClient.DEFAULT_TIMEOUT, OAuthTokenClient.effectiveTimeout(connection));
    }

    @Test
    @DisplayName("a grant deleted mid-refresh fails fast as NOT_CONNECTED, not after the deadline")
    void deletedGrantFailsFast() {
        // The row has to still be there when the caller starts, or this never
        // reaches the await path and merely re-tests "there was never a grant". The
        // disconnect lands on the second read: the one the waiter makes while
        // polling for somebody else's refresh.
        grantStore = new InterferingGrantStore(2, store -> store.delete(TENANT, CONNECTION, PRINCIPAL));
        seedExpiredGrant();
        grantStore.claimRefresh(TENANT, CONNECTION, PRINCIPAL, "another-replica", Instant.now().plus(Duration.ofSeconds(60)));

        long start = System.nanoTime();
        var error = assertThrows(ConnectionException.class, () -> service().accessToken(connection(), PRINCIPAL));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(ConnectionException.Reason.NOT_CONNECTED, error.getReason(),
                "waiting cannot bring a deleted grant back, and TOKEN_ENDPOINT_UNAVAILABLE names the wrong cause");
        assertTrue(elapsedMillis < OAuthTokenService.AWAIT_TIMEOUT.toMillis() / 2,
                "must not spin to the deadline: took " + elapsedMillis + "ms");
    }

    @Test
    @DisplayName("a lease given back with no token written is claimed immediately, not waited out")
    void takesOverWhenTheClaimantReleasesWithoutWriting() {
        // The claimant failed transiently, or died. No token is coming, so polling
        // on burns the whole await timeout — a minute of dead air for every waiter
        // after one provider hiccup — and then reports the wrong reason.
        grantStore = new InterferingGrantStore(2,
                store -> store.releaseRefresh(TENANT, CONNECTION, PRINCIPAL, "another-replica"));
        seedExpiredGrant();
        grantStore.claimRefresh(TENANT, CONNECTION, PRINCIPAL, "another-replica", Instant.now().plus(Duration.ofSeconds(60)));
        when(tokenClient.refresh(any(), anyString(), anyString())).thenAnswer(invocation -> {
            tokenRequests.incrementAndGet();
            return new TokenResponse("fresh-access", "new-refresh", Duration.ofHours(1), List.of());
        });

        long start = System.nanoTime();
        assertEquals("fresh-access", service().accessToken(connection(), PRINCIPAL));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(1, tokenRequests.get(), "the waiter must take the lease over and refresh, exactly once");
        assertTrue(elapsedMillis < OAuthTokenService.AWAIT_TIMEOUT.toMillis() / 2,
                "must not wait out the deadline for a refresh nobody is performing: took " + elapsedMillis + "ms");
    }

    /**
     * Changes the row underneath a caller that is already waiting, on the nth read.
     * Deterministic where a background thread would race the poll interval.
     */
    private static final class InterferingGrantStore extends InMemoryConnectionGrantStore {

        private final int onRead;
        private final Consumer<InMemoryConnectionGrantStore> interference;
        private int reads;

        private InterferingGrantStore(int onRead, Consumer<InMemoryConnectionGrantStore> interference) {
            this.onRead = onRead;
            this.interference = interference;
        }

        @Override
        public synchronized Optional<ConnectionGrant> find(String tenantId, String connectionName, String principal) {
            if (++reads == onRead) {
                interference.accept(this);
            }
            return super.find(tenantId, connectionName, principal);
        }
    }
}
