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
import ai.labs.eddi.connections.grants.ConnectionGrant;
import ai.labs.eddi.connections.grants.InMemoryConnectionGrantStore;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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
        lenient().when(secretProvider.seal(anyString(), anyString()))
                .thenAnswer(i -> new ISecretProvider.SealedValue("sealed:" + i.getArgument(1), "iv"));
        lenient().when(secretProvider.unseal(anyString(), any()))
                .thenAnswer(i -> i.<ISecretProvider.SealedValue>getArgument(1).ciphertext().substring("sealed:".length()));
    }

    private OAuthTokenService service() {
        SecretResolver secretResolver = mock(SecretResolver.class);
        GlobalVariableResolver globalVariableResolver = mock(GlobalVariableResolver.class);
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(i -> i.getArgument(0));
        lenient().when(secretResolver.resolveValue(anyString())).thenReturn("client-secret-value");
        return new OAuthTokenService(grantStore, tokenClient, secretProvider, secretResolver, globalVariableResolver, new SimpleMeterRegistry());
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
    void usesUnexpiredToken() {
        var grant = new ConnectionGrant();
        grant.setTenantId(TENANT);
        grant.setConnectionName(CONNECTION);
        grant.setPrincipal(PRINCIPAL);
        grant.setEncryptedAccessToken("sealed:still-good");
        grant.setAccessTokenIv("iv");
        grant.setExpiresAt(Instant.now().plus(Duration.ofHours(1)));
        grant.setStatus(ConnectionGrant.Status.ACTIVE);
        grantStore.seed(grant);

        assertEquals("still-good", service().accessToken(connection(), PRINCIPAL));
        assertEquals(0, tokenRequests.get());
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
    void keepsRefreshTokenWhenProviderDoesNotRotate() {
        seedExpiredGrant();
        when(tokenClient.refresh(any(), anyString(), anyString()))
                .thenReturn(new TokenResponse("fresh-access", null, Duration.ofHours(1), List.of()));

        assertEquals("fresh-access", service().accessToken(connection(), PRINCIPAL));

        assertEquals("sealed:old-refresh", grantStore.find(TENANT, CONNECTION, PRINCIPAL).orElseThrow().getEncryptedRefreshToken(),
                "overwriting it with null would make the NEXT refresh impossible");
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
    @DisplayName("the refresh lease must outlast the token-endpoint timeout")
    void leaseOutlastsEndpointTimeout() {
        // Asserted rather than left to a comment: if the lease is shorter, a slow
        // provider frees it while the claimant is still in flight and the double
        // refresh this whole protocol prevents comes straight back.
        assertTrue(OAuthTokenService.REFRESH_LEASE.compareTo(OAuthTokenClient.DEFAULT_TIMEOUT) > 0);
    }
}
