/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.oauth;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.connections.AccessTokenSupplier;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.grants.ConnectionGrant;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;

/**
 * Produces a live access token for a connection and principal, refreshing when
 * needed.
 *
 * <h3>The refresh race, and why the ordering is the design</h3> Two
 * conversations hitting an expired grant at once both call the token endpoint.
 * With rotating refresh tokens — Google, Atlassian — the second call
 * invalidates the first, and a user who did nothing wrong is silently logged
 * out.
 * <p>
 * The fix is a claim taken <b>before</b> the network call:
 * <ol>
 * <li><b>Claim</b> — one atomic conditional update on the grant row. This is
 * the cross-replica gate.</li>
 * <li><b>Claimant refreshes.</b> Non-claimants poll the row for the new token
 * until the lease expires, then retry the claim rather than refreshing
 * blind.</li>
 * <li><b>Write</b>, guarded by a version CAS, clearing the lease.</li>
 * </ol>
 * An earlier design used the CAS alone. A CAS is checked at <em>write</em>
 * time, by which point both replicas have already called the endpoint and the
 * provider has already rotated one token away — the CAS then dutifully
 * serialises two writes, one carrying a token that is already dead.
 * <p>
 * The in-process {@code ConcurrentHashMap} of in-flight futures collapses
 * concurrent callers on one replica before they contend on the row. It is an
 * optimisation on top of the claim, never a substitute: it cannot see the other
 * four pods.
 * <p>
 * The lease TTL must exceed the token-endpoint timeout, or a slow provider
 * frees the lease while the claimant is still in flight and reintroduces the
 * double refresh. That relationship is asserted at construction rather than
 * left to a comment.
 */
@ApplicationScoped
public class OAuthTokenService implements AccessTokenSupplier {

    private static final Logger LOGGER = Logger.getLogger(OAuthTokenService.class);

    /**
     * Treat a token as expired this long before it really is, so a provider whose
     * clock differs from ours by a second does not produce a 401 that looks random.
     */
    static final Duration EXPIRY_MARGIN = Duration.ofSeconds(30);

    /** How long a claimant owns the refresh. Must exceed the endpoint timeout. */
    static final Duration REFRESH_LEASE = Duration.ofSeconds(60);

    /** How long a non-claimant waits for the claimant's result before retrying. */
    static final Duration AWAIT_TIMEOUT = REFRESH_LEASE;

    /** Poll interval while awaiting another replica's refresh. */
    static final Duration AWAIT_POLL_INTERVAL = Duration.ofMillis(250);

    private final IConnectionGrantStore grantStore;
    private final OAuthTokenClient tokenClient;
    private final ISecretProvider secretProvider;
    private final SecretResolver secretResolver;
    private final GlobalVariableResolver globalVariableResolver;
    private final MeterRegistry meterRegistry;

    /** Per-JVM single-flight, keyed by grant. */
    private final ConcurrentHashMap<String, CompletableFuture<String>> inFlight = new ConcurrentHashMap<>();

    /**
     * Identifies this replica's claims, so a release cannot clear somebody else's.
     */
    private final String claimantId = UUID.randomUUID().toString();

    @Inject
    public OAuthTokenService(IConnectionGrantStore grantStore, OAuthTokenClient tokenClient, ISecretProvider secretProvider,
            SecretResolver secretResolver, GlobalVariableResolver globalVariableResolver, MeterRegistry meterRegistry) {
        this.grantStore = grantStore;
        this.tokenClient = tokenClient;
        this.secretProvider = secretProvider;
        this.secretResolver = secretResolver;
        this.globalVariableResolver = globalVariableResolver;
        this.meterRegistry = meterRegistry;
        if (REFRESH_LEASE.compareTo(OAuthTokenClient.DEFAULT_TIMEOUT) <= 0) {
            throw new IllegalStateException("The refresh lease must outlast the token-endpoint timeout, or a slow provider frees the lease "
                    + "while the claimant is still in flight and two replicas refresh the same grant.");
        }
    }

    @Override
    public String accessToken(ConnectionConfiguration connection, String principal) {
        String tenantId = tenantOf(connection);
        Optional<ConnectionGrant> existing = grantStore.find(tenantId, connection.getName(), principal);

        if (existing.isEmpty()) {
            if (connection.getAuthType() == AuthType.OAUTH2_CLIENT_CREDENTIALS) {
                // A service account needs no human step: the first call mints it.
                return mintServiceGrant(connection, tenantId, principal);
            }
            throw new ConnectionException(ConnectionException.Reason.NOT_CONNECTED, "Connection '" + connection.getName()
                    + "' has no grant for this user yet. Connect the account first (POST /connections/" + connection.getName() + "/authorize).");
        }

        ConnectionGrant grant = existing.get();
        if (grant.getStatus() == ConnectionGrant.Status.REVOKED || grant.getStatus() == ConnectionGrant.Status.REFRESH_FAILED) {
            throw new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE, "The grant for connection '" + connection.getName()
                    + "' is " + grant.getStatus() + ". The user must reconnect the account.");
        }
        if (grant.isAccessTokenUsable(Instant.now(), EXPIRY_MARGIN)) {
            return unseal(tenantId, grant.getEncryptedAccessToken(), grant.getAccessTokenIv(), connection);
        }
        return refreshSingleFlight(connection, tenantId, principal, grant);
    }

    /**
     * Collapses concurrent callers on this replica, then defers to the
     * cross-replica claim.
     * <p>
     * The refresh runs on the CALLING thread, not on a pool. {@code supplyAsync}
     * would put it on the common ForkJoinPool, where the non-claimant's polling
     * wait — a genuinely blocking operation — occupies a thread sized for CPU work
     * and shared with every parallel stream in the process.
     * <p>
     * Exceptions are unwrapped for joiners. A {@link CompletableFuture} wraps
     * whatever it was completed with in a {@link CompletionException}, and the
     * whole point of {@link ConnectionException.Reason} is that callers switch on
     * it — a wrapped exception silently turns "reconnect required" into an
     * unclassified failure.
     */
    private String refreshSingleFlight(ConnectionConfiguration connection, String tenantId, String principal, ConnectionGrant grant) {
        String key = tenantId + "|" + connection.getName() + "|" + principal;
        var flight = new CompletableFuture<String>();
        CompletableFuture<String> existing = inFlight.putIfAbsent(key, flight);
        if (existing != null) {
            return join(existing);
        }
        try {
            String token = refreshOrAwait(connection, tenantId, principal, grant);
            flight.complete(token);
            return token;
        } catch (RuntimeException e) {
            // Completed exceptionally so joiners fail the same way rather than
            // waiting out the timeout on a future nobody will ever complete.
            flight.completeExceptionally(e);
            throw e;
        } finally {
            // Removed by the creator, so the next expiry starts a fresh flight.
            // Leaving a completed future in the map would serve one stale token
            // forever.
            inFlight.remove(key, flight);
        }
    }

    /** Joins a flight another caller owns, unwrapping its completion exception. */
    private static String join(CompletableFuture<String> flight) {
        try {
            return flight.join();
        } catch (CompletionException e) {
            throw e.getCause() instanceof RuntimeException runtime
                    ? runtime
                    : new ConnectionException(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, "Token refresh failed", e.getCause());
        }
    }

    private String refreshOrAwait(ConnectionConfiguration connection, String tenantId, String principal, ConnectionGrant grant) {
        Instant deadline = Instant.now().plus(AWAIT_TIMEOUT);
        while (true) {
            if (grantStore.claimRefresh(tenantId, connection.getName(), principal, claimantId, Instant.now().plus(REFRESH_LEASE))) {
                count("connection.token.refresh.claim.count", "outcome", "claimed");
                return refreshAsClaimant(connection, tenantId, principal);
            }
            // Somebody else is refreshing. Poll for their result rather than making a
            // second token request: with rotating refresh tokens, the second request
            // is what kills the first one's token.
            Optional<String> adopted = awaitAnotherRefresh(connection, tenantId, principal, deadline);
            if (adopted.isPresent()) {
                count("connection.token.refresh.claim.count", "outcome", "awaited");
                return adopted.get();
            }
            if (Instant.now().isAfter(deadline)) {
                // The lease outlived its holder — a crashed replica, or one that hung.
                // Retry the claim rather than refreshing blind, so exactly one caller
                // proceeds even now.
                count("connection.token.refresh.claim.count", "outcome", "lease_expired");
                if (grantStore.claimRefresh(tenantId, connection.getName(), principal, claimantId, Instant.now().plus(REFRESH_LEASE))) {
                    return refreshAsClaimant(connection, tenantId, principal);
                }
                throw new ConnectionException(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE, "Timed out waiting for a token refresh on "
                        + "connection '" + connection.getName() + "'. Another node holds the refresh lease.");
            }
        }
    }

    /** Polls for a token another replica wrote. */
    private Optional<String> awaitAnotherRefresh(ConnectionConfiguration connection, String tenantId, String principal, Instant deadline) {
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(AWAIT_POLL_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConnectionException(ConnectionException.Reason.TOKEN_ENDPOINT_UNAVAILABLE,
                        "Interrupted while waiting for a token refresh on connection '" + connection.getName() + "'.", e);
            }
            Optional<ConnectionGrant> reread = grantStore.find(tenantId, connection.getName(), principal);
            if (reread.isEmpty()) {
                return Optional.empty();
            }
            ConnectionGrant current = reread.get();
            if (current.getStatus() == ConnectionGrant.Status.REFRESH_FAILED || current.getStatus() == ConnectionGrant.Status.REVOKED) {
                throw new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE, "The grant for connection '" + connection.getName()
                        + "' became unusable during a refresh. The user must reconnect the account.");
            }
            if (current.getRefreshInProgress() == null && current.isAccessTokenUsable(Instant.now(), EXPIRY_MARGIN)) {
                return Optional.of(unseal(tenantId, current.getEncryptedAccessToken(), current.getAccessTokenIv(), connection));
            }
        }
        return Optional.empty();
    }

    private String refreshAsClaimant(ConnectionConfiguration connection, String tenantId, String principal) {
        // Re-read INSIDE the claim: between the caller's read and the claim landing,
        // another node may have completed a refresh, in which case there is nothing
        // to do and the newest refresh token is the one to use.
        ConnectionGrant grant = grantStore.find(tenantId, connection.getName(), principal).orElseThrow(() -> new ConnectionException(
                ConnectionException.Reason.NOT_CONNECTED, "The grant for connection '" + connection.getName() + "' disappeared mid-refresh."));
        try {
            if (grant.isAccessTokenUsable(Instant.now(), EXPIRY_MARGIN)) {
                return unseal(tenantId, grant.getEncryptedAccessToken(), grant.getAccessTokenIv(), connection);
            }
            TokenResponse token = requestNewToken(connection, tenantId, grant);
            String refreshToken = token.refreshToken() != null
                    ? token.refreshToken()
                    // A provider that does not rotate returns no refresh_token; keeping
                    // the previous one is required, not optional — overwriting it with
                    // null makes the NEXT refresh impossible.
                    : unsealOrNull(tenantId, grant.getEncryptedRefreshToken(), grant.getRefreshTokenIv());
            persist(connection, tenantId, principal, token, refreshToken, grant.getVersion());
            count("connection.token.refresh.count", "outcome", "success");
            return token.accessToken();
        } catch (ConnectionException e) {
            handleRefreshFailure(connection, tenantId, principal, grant, e);
            throw e;
        } finally {
            // Best-effort; completeRefresh already cleared it on the success path, and
            // this is scoped to our own claimantId so it cannot clear a successor's.
            grantStore.releaseRefresh(tenantId, connection.getName(), principal, claimantId);
        }
    }

    private TokenResponse requestNewToken(ConnectionConfiguration connection, String tenantId, ConnectionGrant grant) {
        String clientSecret = resolveClientSecret(connection);
        if (connection.getAuthType() == AuthType.OAUTH2_CLIENT_CREDENTIALS) {
            // A service account has no refresh token by design — it re-authenticates.
            return tokenClient.clientCredentials(connection, clientSecret);
        }
        String refreshToken = unsealOrNull(tenantId, grant.getEncryptedRefreshToken(), grant.getRefreshTokenIv());
        if (refreshToken == null) {
            throw new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE, "The grant for connection '" + connection.getName()
                    + "' has no refresh token, so its expired access token cannot be renewed. The user must reconnect.");
        }
        return tokenClient.refresh(connection, clientSecret, refreshToken);
    }

    /**
     * Marks a grant dead only for a terminal error.
     * <p>
     * The distinction is the whole reason {@link ConnectionException.Reason}
     * exists: {@code GRANT_UNUSABLE} means the provider rejected the grant and the
     * user must reconnect; {@code TOKEN_ENDPOINT_UNAVAILABLE} means the provider
     * had a bad minute and the grant is untouched. Conflating them logs every user
     * of a connection out during a provider outage, and they come back to
     * "reconnect required" for something that fixed itself.
     */
    private void handleRefreshFailure(ConnectionConfiguration connection, String tenantId, String principal, ConnectionGrant grant,
                                      ConnectionException failure) {
        if (failure.getReason() != ConnectionException.Reason.GRANT_UNUSABLE) {
            count("connection.token.refresh.count", "outcome", "transient");
            LOGGER.warnf("Refresh for connection '%s' failed transiently; the grant is unchanged", connection.getName());
            return;
        }
        count("connection.token.refresh.count", "outcome", "invalid_grant");
        grant.setStatus(ConnectionGrant.Status.REFRESH_FAILED);
        grantStore.completeRefresh(grant, grant.getVersion());
        LOGGER.warnf("Refresh for connection '%s' was rejected by the provider; the grant is marked REFRESH_FAILED", connection.getName());
    }

    /** Mints and stores a {@code client_credentials} grant on first use. */
    private String mintServiceGrant(ConnectionConfiguration connection, String tenantId, String principal) {
        TokenResponse token = tokenClient.clientCredentials(connection, resolveClientSecret(connection));
        persistNew(connection, tenantId, principal, token, token.refreshToken());
        count("connection.token.refresh.count", "outcome", "minted");
        return token.accessToken();
    }

    /**
     * Stores a brand-new grant. Used by the {@code client_credentials} path and by
     * the authorization-code callback.
     */
    public void persistNew(ConnectionConfiguration connection, String tenantId, String principal, TokenResponse token, String refreshToken) {
        ConnectionGrant grant = buildGrant(connection, tenantId, principal, token, refreshToken);
        grantStore.upsert(grant);
    }

    private void persist(ConnectionConfiguration connection, String tenantId, String principal, TokenResponse token, String refreshToken,
                         long expectedVersion) {
        ConnectionGrant grant = buildGrant(connection, tenantId, principal, token, refreshToken);
        if (!grantStore.completeRefresh(grant, expectedVersion)) {
            // Another writer landed first. Not an error: their token is at least as
            // fresh as ours, and the caller gets the one we just obtained, which the
            // provider issued and has not rejected.
            LOGGER.debugf("Refresh CAS lost for connection '%s'; another writer was ahead", connection.getName());
        }
    }

    private ConnectionGrant buildGrant(ConnectionConfiguration connection, String tenantId, String principal, TokenResponse token,
                                       String refreshToken) {
        var grant = new ConnectionGrant();
        grant.setTenantId(tenantId);
        grant.setConnectionName(connection.getName());
        grant.setPrincipal(principal);
        ISecretProvider.SealedValue access = seal(tenantId, token.accessToken(), connection);
        grant.setEncryptedAccessToken(access.ciphertext());
        grant.setAccessTokenIv(access.iv());
        if (refreshToken != null) {
            ISecretProvider.SealedValue refresh = seal(tenantId, refreshToken, connection);
            grant.setEncryptedRefreshToken(refresh.ciphertext());
            grant.setRefreshTokenIv(refresh.iv());
        }
        grant.setDekId(tenantId);
        grant.setExpiresAt(Instant.now().plus(token.expiresIn()));
        grant.setScopes(token.scopes() == null || token.scopes().isEmpty() ? connection.getOauth().getScopes() : token.scopes());
        grant.setStatus(ConnectionGrant.Status.ACTIVE);
        grant.setLastRefreshAt(Instant.now());
        return grant;
    }

    private String resolveClientSecret(ConnectionConfiguration connection) {
        String resolved = globalVariableResolver.resolveValue(connection.getOauth().getClientSecret());
        resolved = secretResolver.resolveValue(resolved);
        if (resolved == null || resolved.isBlank() || resolved.contains("${vault:") || resolved.contains("${eddivault:")) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, "The client secret for connection '"
                    + connection.getName() + "' did not resolve. The vault key is missing, or the vault is inactive.");
        }
        return resolved;
    }

    private ISecretProvider.SealedValue seal(String tenantId, String plaintext, ConnectionConfiguration connection) {
        try {
            return secretProvider.seal(tenantId, plaintext);
        } catch (ISecretProvider.SecretProviderException e) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION, "Cannot store the grant for connection '"
                    + connection.getName() + "': the vault is unavailable. Tokens are never stored in plaintext.", e);
        }
    }

    private String unseal(String tenantId, String ciphertext, String iv, ConnectionConfiguration connection) {
        String plaintext = unsealOrNull(tenantId, ciphertext, iv);
        if (plaintext == null) {
            throw new ConnectionException(ConnectionException.Reason.GRANT_UNUSABLE,
                    "The stored token for connection '" + connection.getName() + "' could not be decrypted. The user must reconnect.");
        }
        return plaintext;
    }

    private String unsealOrNull(String tenantId, String ciphertext, String iv) {
        if (ciphertext == null) {
            return null;
        }
        try {
            return secretProvider.unseal(tenantId, new ISecretProvider.SealedValue(ciphertext, iv));
        } catch (ISecretProvider.SecretProviderException e) {
            LOGGER.warnf("Failed to unseal a stored grant token for tenant '%s'", tenantId);
            return null;
        }
    }

    private static String tenantOf(ConnectionConfiguration connection) {
        return connection.getTenantId() == null || connection.getTenantId().isBlank() ? "default" : connection.getTenantId();
    }

    /** Bounded categoricals only — see {@code ConnectionResolver#record}. */
    private void count(String metric, String tagName, String tagValue) {
        if (meterRegistry != null) {
            meterRegistry.counter(metric, tagName, tagValue).increment();
        }
    }
}
