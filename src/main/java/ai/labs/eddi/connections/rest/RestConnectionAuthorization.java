/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.rest;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.ConnectionsConfig;
import ai.labs.eddi.connections.grants.ConnectionGrant;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.connections.model.ConnectionReference;
import ai.labs.eddi.connections.oauth.CredentialEndpointAllowlist;
import ai.labs.eddi.connections.oauth.IOAuthStateStore;
import ai.labs.eddi.connections.oauth.OAuthState;
import ai.labs.eddi.connections.oauth.OAuthTokenClient;
import ai.labs.eddi.connections.oauth.OAuthTokenService;
import ai.labs.eddi.connections.oauth.TokenResponse;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.secrets.SecretResolver;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-user grant lifecycle.
 *
 * <h3>The callback is the interesting part</h3> It is a {@code permit} path
 * because it has to be, and it is guarded by exactly one thing: a single-use,
 * server-stored, short-TTL {@code state} that binds the tenant, the connection
 * and the principal. Three consequences follow, and all three are enforced
 * below:
 * <ul>
 * <li>the claim is the FIRST thing that happens, as one conditional write;</li>
 * <li>identity comes from the claimed row, never from a query parameter;</li>
 * <li>the browser is told nothing that distinguishes "unknown state" from
 * "expired" from "already used" — that distinction is a state-guessing
 * oracle.</li>
 * </ul>
 */
@ApplicationScoped
public class RestConnectionAuthorization implements IRestConnectionAuthorization {

    private static final Logger LOGGER = Logger.getLogger(RestConnectionAuthorization.class);

    /** How long a user has to complete the provider's consent screen. */
    static final Duration STATE_TTL = Duration.ofMinutes(10);

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConnectionRegistry connectionRegistry;
    private final IOAuthStateStore stateStore;
    private final IConnectionGrantStore grantStore;
    private final OAuthTokenClient tokenClient;
    private final OAuthTokenService tokenService;
    private final CredentialEndpointAllowlist endpointAllowlist;
    private final ConnectionsConfig connectionsConfig;
    private final SecurityIdentity securityIdentity;
    private final SecretResolver secretResolver;
    private final GlobalVariableResolver globalVariableResolver;
    private final MeterRegistry meterRegistry;

    @Inject
    public RestConnectionAuthorization(ConnectionRegistry connectionRegistry, IOAuthStateStore stateStore, IConnectionGrantStore grantStore,
            OAuthTokenClient tokenClient, OAuthTokenService tokenService, CredentialEndpointAllowlist endpointAllowlist,
            ConnectionsConfig connectionsConfig, SecurityIdentity securityIdentity, SecretResolver secretResolver,
            GlobalVariableResolver globalVariableResolver, MeterRegistry meterRegistry) {
        this.connectionRegistry = connectionRegistry;
        this.stateStore = stateStore;
        this.grantStore = grantStore;
        this.tokenClient = tokenClient;
        this.tokenService = tokenService;
        this.endpointAllowlist = endpointAllowlist;
        this.connectionsConfig = connectionsConfig;
        this.securityIdentity = securityIdentity;
        this.secretResolver = secretResolver;
        this.globalVariableResolver = globalVariableResolver;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Map<String, String> authorize(String name, String returnTo) {
        requireEnabled();
        String principal = requirePrincipal();
        ConnectionConfiguration connection = requireConnection(name);

        if (connection.getAuthType() != AuthType.OAUTH2_AUTHORIZATION_CODE) {
            throw new BadRequestException("Connection '" + name + "' is " + connection.getAuthType()
                    + ", which needs no per-user authorization. Only OAUTH2_AUTHORIZATION_CODE connections are linked by a user.");
        }
        endpointAllowlist.require(connection.getOauth().getAuthorizationUrl(), "oauth.authorizationUrl");
        endpointAllowlist.require(connection.getOauth().getTokenUrl(), "oauth.tokenUrl");

        String codeVerifier = randomUrlSafe(64);
        var state = new OAuthState();
        state.setState(randomUrlSafe(32));
        state.setTenantId(tenantOf(connection));
        state.setConnectionName(connection.getName());
        state.setPrincipal(principal);
        state.setCodeVerifier(codeVerifier);
        state.setRedirectUri(connectionsConfig.redirectUri());
        state.setReturnTo(connectionsConfig.isAllowedReturnTo(returnTo) ? returnTo : connectionsConfig.defaultReturnTo());
        state.setCreatedAt(Instant.now());
        state.setExpiresAt(Instant.now().plus(STATE_TTL));
        stateStore.create(state);

        count("connection.oauth.authorize.count", "outcome", "issued", connection);
        return Map.of("authorizationUrl", buildAuthorizationUrl(connection, state, codeVerifier));
    }

    private String buildAuthorizationUrl(ConnectionConfiguration connection, OAuthState state, String codeVerifier) {
        var oauth = connection.getOauth();
        var params = new LinkedHashMap<String, String>();
        params.put("response_type", "code");
        params.put("client_id", oauth.getClientId());
        params.put("redirect_uri", state.getRedirectUri());
        params.put("state", state.getState());
        params.put("code_challenge", s256(codeVerifier));
        params.put("code_challenge_method", "S256");
        if (oauth.getScopes() != null && !oauth.getScopes().isEmpty()) {
            params.put("scope", String.join(" ", oauth.getScopes()));
        }
        if (oauth.getExtraAuthParams() != null) {
            // Validated at write time to carry no credential-shaped names; put last so
            // it cannot overwrite response_type, redirect_uri, state or the PKCE
            // challenge, each of which is load-bearing.
            oauth.getExtraAuthParams().forEach((key, value) -> {
                if (!params.containsKey(key)) {
                    params.put(key, value);
                }
            });
        }
        var url = new StringBuilder(oauth.getAuthorizationUrl());
        url.append(oauth.getAuthorizationUrl().contains("?") ? '&' : '?');
        boolean first = true;
        for (var entry : params.entrySet()) {
            if (!first) {
                url.append('&');
            }
            url.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
            first = false;
        }
        return url.toString();
    }

    @Override
    public Response callback(String code, String state, String error, String errorDescription) {
        if (!connectionsConfig.isEnabled()) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }

        // FIRST: claim. Not "validate, then mark consumed" — that is a
        // read-then-write, and two concurrent callbacks would both observe the row
        // unconsumed and both redeem the authorization code.
        var claimed = stateStore.claim(state);
        if (claimed.isEmpty()) {
            // Unknown, expired and already-used are answered identically. Telling them
            // apart is a state-guessing oracle, and none of the three is actionable by
            // the user beyond "start again".
            count("connection.oauth.callback.count", "outcome", "bad_state", null);
            LOGGER.warn("An OAuth callback arrived with a state that is unknown, expired or already used");
            return redirect(connectionsConfig.defaultReturnTo(), "error", "invalid_state");
        }
        OAuthState oauthState = claimed.get();

        if (error != null && !error.isBlank()) {
            // The user declined, or the provider refused. The provider's own
            // description is NOT echoed onward: it is attacker-influenceable text
            // heading for a browser.
            count("connection.oauth.callback.count", "outcome", "provider_error", null);
            LOGGER.warnf("The provider refused an authorization for connection '%s' (%s)", oauthState.getConnectionName(), sanitize(error));
            return redirect(oauthState.getReturnTo(), "error", "authorization_declined");
        }
        if (code == null || code.isBlank()) {
            count("connection.oauth.callback.count", "outcome", "bad_state", null);
            return redirect(oauthState.getReturnTo(), "error", "missing_code");
        }

        ConnectionConfiguration connection;
        try {
            connection = connectionRegistry.require(new ConnectionReference(oauthState.getTenantId(), oauthState.getConnectionName()));
        } catch (ConnectionException e) {
            count("connection.oauth.callback.count", "outcome", "exchange_failed", null);
            return redirect(oauthState.getReturnTo(), "error", "connection_removed");
        }

        try {
            TokenResponse token = tokenClient.authorizationCode(connection, resolveClientSecret(connection), code, oauthState.getCodeVerifier(),
                    oauthState.getRedirectUri());
            // The principal comes from the CLAIMED ROW. Reading it from a query
            // parameter would let anyone who obtains a state install a grant under
            // somebody else's name.
            tokenService.persistNew(connection, oauthState.getTenantId(), oauthState.getPrincipal(), token, token.refreshToken());
            count("connection.oauth.callback.count", "outcome", "success", connection);
            return redirect(oauthState.getReturnTo(), "connected", connection.getName());
        } catch (ConnectionException e) {
            count("connection.oauth.callback.count", "outcome", "exchange_failed", connection);
            LOGGER.warnf("Token exchange failed for connection '%s': %s", connection.getName(), e.getReason());
            return redirect(oauthState.getReturnTo(), "error", "exchange_failed");
        }
    }

    @Override
    public List<Map<String, Object>> listMine() {
        requireEnabled();
        String principal = requirePrincipal();
        var results = new ArrayList<Map<String, Object>>();
        // The same tenant resolution disconnect uses. Hardcoding "default" here made
        // a grant under any other tenant invisible on the linked-accounts page while
        // the agent resolved it happily and disconnect deleted it — three views of
        // one grant that did not agree.
        for (ConnectionGrant grant : grantStore.findByPrincipal(callerTenant(), principal)) {
            // Explicitly enumerated, not serialized from the entity. A field list is
            // a decision; serializing an object that happens to hold ciphertext is an
            // accident waiting for someone to add a getter.
            var view = new LinkedHashMap<String, Object>();
            view.put("connection", grant.getConnectionName());
            view.put("status", grant.getStatus().name());
            view.put("expiresAt", grant.getExpiresAt() == null ? null : grant.getExpiresAt().toString());
            view.put("scopes", grant.getScopes());
            view.put("connectedAt", grant.getCreatedAt() == null ? null : grant.getCreatedAt().toString());
            results.add(view);
        }
        return results;
    }

    @Override
    public Response disconnect(String name) {
        requireEnabled();
        String principal = requirePrincipal();
        ConnectionConfiguration connection = requireConnection(name);
        boolean deleted = grantStore.delete(tenantOf(connection), connection.getName(), principal);
        // Deliberately the same answer either way: whether a given user had linked a
        // given connection is not something a caller needs to learn by probing.
        LOGGER.infof("Disconnect for connection '%s' (existing grant: %s)", connection.getName(), deleted);
        return Response.noContent().build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void requireEnabled() {
        if (!connectionsConfig.isEnabled()) {
            throw new ServiceUnavailableException("Connections are disabled. Set eddi.connections.enabled=true to use them.");
        }
    }

    /**
     * The verified caller, or a refusal.
     * <p>
     * {@code @Authenticated} is a no-op when {@code authorization.enabled=false},
     * so this is checked in code as well. The startup guard refuses that
     * combination outright; between them a grant can never be minted for a
     * self-asserted principal.
     */
    private String requirePrincipal() {
        if (securityIdentity == null || securityIdentity.isAnonymous() || securityIdentity.getPrincipal() == null) {
            throw new ForbiddenException("Linking an account requires an authenticated user. With authorization disabled there is no verified "
                    + "identity, and a grant minted for a self-asserted principal belongs to whoever asked for it.");
        }
        String name = securityIdentity.getPrincipal().getName();
        if (name == null || name.isBlank()) {
            throw new ForbiddenException("The authenticated identity has no principal name.");
        }
        return name;
    }

    private ConnectionConfiguration requireConnection(String name) {
        try {
            return connectionRegistry.require(new ConnectionReference(ConnectionReference.DEFAULT_TENANT, name));
        } catch (ConnectionException e) {
            throw new NotFoundException("No connection named '" + name + "'.");
        }
    }

    private String resolveClientSecret(ConnectionConfiguration connection) {
        String resolved = globalVariableResolver.resolveValue(connection.getOauth().getClientSecret());
        resolved = secretResolver.resolveValue(resolved);
        if (resolved == null || resolved.isBlank() || resolved.contains("${vault:")) {
            throw new ConnectionException(ConnectionException.Reason.INVALID_CONFIGURATION,
                    "The client secret for connection '" + connection.getName() + "' did not resolve.");
        }
        return resolved;
    }

    /**
     * Redirects the browser, tolerating a state row that carries no destination.
     * <p>
     * {@code authorize} always stores one, but a row written by an earlier version
     * or by a direct database write would not — and by the time the callback gets
     * here it has already claimed the state and may have stored the grant, so
     * throwing would leave the user with a 500 and no way to retry (their state is
     * consumed). A fragment is dropped rather than appended past, since a query
     * appended after a fragment is not a query.
     */
    private Response redirect(String returnTo, String key, String value) {
        String destination = (returnTo == null || returnTo.isBlank()) ? connectionsConfig.defaultReturnTo() : returnTo;
        int fragment = destination.indexOf('#');
        if (fragment >= 0) {
            destination = destination.substring(0, fragment);
        }
        String separator = destination.contains("?") ? "&" : "?";
        return Response.seeOther(URI.create(destination + separator + encode(key) + "=" + encode(value))).build();
    }

    private void count(String metric, String tagName, String tagValue, ConnectionConfiguration connection) {
        if (meterRegistry == null) {
            return;
        }
        // The connection NAME is never a tag — author-supplied and unbounded.
        if (connection != null && connection.getAuthType() != null) {
            meterRegistry.counter(metric, tagName, tagValue, "authType", connection.getAuthType().name()).increment();
        } else {
            meterRegistry.counter(metric, tagName, tagValue).increment();
        }
    }

    /**
     * The tenant a listing is scoped to.
     * <p>
     * A single method rather than a literal at each call site, so the multi-tenancy
     * work has one place to change. Until {@code TenantContext} lands this is the
     * default tenant, which is also what every connection document defaults to.
     */
    private static String callerTenant() {
        return ConnectionReference.DEFAULT_TENANT;
    }

    private static String tenantOf(ConnectionConfiguration connection) {
        return connection.getTenantId() == null || connection.getTenantId().isBlank()
                ? ConnectionReference.DEFAULT_TENANT
                : connection.getTenantId();
    }

    /** RFC 7636 S256: base64url(sha256(verifier)), unpadded. */
    private static String s256(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the platform. If it is genuinely absent, PKCE
            // cannot be honoured and the flow must not silently continue without it.
            throw new IllegalStateException("SHA-256 is unavailable, so PKCE cannot be applied", e);
        }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String encode(String value) {
        return value == null ? "" : URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Strips CR/LF so a provider-supplied error cannot forge log lines. */
    private static String sanitize(String value) {
        return value == null ? "null" : value.replaceAll("[\\r\\n]", "_");
    }
}
