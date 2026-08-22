/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.rest;

import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.connections.ConnectionException;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.CredentialReferenceResolver;
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
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.NewCookie;
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
 * because it has to be, and it is guarded by two things that must both hold: a
 * single-use, server-stored, short-TTL {@code state} that binds the tenant, the
 * connection and the principal, and a nonce cookie proving the callback reached
 * the browser that started the flow.
 * <p>
 * Both are needed. The state binds a principal, but the ATTACKER chooses that
 * principal: they start a flow under their own account, keep the state, and
 * send the victim the provider's consent link built around it. The victim
 * consents with their own account and the tokens are filed under the attacker's
 * principal — every field in the row exactly as intended, and the attacker
 * reading the victim's mail on their next turn. The cookie is what the attacker
 * cannot put in the victim's browser.
 * <p>
 * Four consequences follow, and all four are enforced below:
 * <ul>
 * <li>the claim is the FIRST thing that happens, as one conditional write;</li>
 * <li>identity comes from the claimed row, never from a query parameter;</li>
 * <li>the browser is told nothing that distinguishes "unknown state" from
 * "expired" from "already used" — that distinction is a state-guessing
 * oracle;</li>
 * <li>a missing or mismatched binding cookie is answered exactly as an invalid
 * state is, for the same reason.</li>
 * </ul>
 */
@ApplicationScoped
public class RestConnectionAuthorization implements IRestConnectionAuthorization {

    private static final Logger LOGGER = Logger.getLogger(RestConnectionAuthorization.class);

    /** How long a user has to complete the provider's consent screen. */
    static final Duration STATE_TTL = Duration.ofMinutes(10);

    /**
     * Cookie name prefix for the browser-binding nonce. The state token is appended
     * so concurrent flows in two tabs do not clobber one another.
     */
    static final String COOKIE_PREFIX = "eddi_oauth_nonce_";

    /** Scoped as narrowly as the flow allows: nothing else needs to see it. */
    private static final String COOKIE_PATH = "/connections";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConnectionRegistry connectionRegistry;
    private final IOAuthStateStore stateStore;
    private final IConnectionGrantStore grantStore;
    private final OAuthTokenClient tokenClient;
    private final OAuthTokenService tokenService;
    private final CredentialEndpointAllowlist endpointAllowlist;
    private final ConnectionsConfig connectionsConfig;
    private final SecurityIdentity securityIdentity;
    private final CredentialReferenceResolver credentialReferenceResolver;
    private final MeterRegistry meterRegistry;

    @Inject
    public RestConnectionAuthorization(ConnectionRegistry connectionRegistry, IOAuthStateStore stateStore, IConnectionGrantStore grantStore,
            OAuthTokenClient tokenClient, OAuthTokenService tokenService, CredentialEndpointAllowlist endpointAllowlist,
            ConnectionsConfig connectionsConfig, SecurityIdentity securityIdentity, CredentialReferenceResolver credentialReferenceResolver,
            MeterRegistry meterRegistry) {
        this.connectionRegistry = connectionRegistry;
        this.stateStore = stateStore;
        this.grantStore = grantStore;
        this.tokenClient = tokenClient;
        this.tokenService = tokenService;
        this.endpointAllowlist = endpointAllowlist;
        this.connectionsConfig = connectionsConfig;
        this.securityIdentity = securityIdentity;
        this.credentialReferenceResolver = credentialReferenceResolver;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Response authorize(String name, String returnTo) {
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
        String nonce = randomUrlSafe(32);
        var state = new OAuthState();
        state.setState(randomUrlSafe(32));
        state.setTenantId(ConnectionConfiguration.effectiveTenant(connection));
        state.setConnectionName(connection.getName());
        state.setPrincipal(principal);
        state.setCodeVerifier(codeVerifier);
        state.setRedirectUri(connectionsConfig.redirectUri());
        state.setReturnTo(connectionsConfig.isAllowedReturnTo(returnTo) ? returnTo : connectionsConfig.defaultReturnTo());
        state.setNonceHash(sha256(nonce));
        state.setCreatedAt(Instant.now());
        state.setExpiresAt(Instant.now().plus(STATE_TTL));
        stateStore.create(state);

        count("connection.oauth.authorize.count", "outcome", "issued", connection);
        return Response.ok(Map.of("authorizationUrl", buildAuthorizationUrl(connection, state, codeVerifier)))
                .cookie(bindingCookie(state.getState(), nonce, (int) STATE_TTL.toSeconds())).build();
    }

    /**
     * The cookie that ties this flow to this browser.
     * <p>
     * Named per state so two tabs linking two connections do not overwrite each
     * other — a single shared name would silently break whichever flow the user
     * started first, and "try again in one tab at a time" is not a thing anyone
     * would ever work out.
     * <p>
     * {@code SameSite=Lax} rather than {@code Strict}: the callback arrives as a
     * top-level GET navigation from the provider's origin, which Lax allows and
     * Strict does not — Strict here would refuse every legitimate link.
     * {@code Secure} follows the deployment's own base URL, so a plain-HTTP
     * development instance still works while a real one never sends the nonce in
     * clear.
     * <p>
     * Issuing and expiring share this one builder: the attributes a browser matches
     * a cookie on have to agree, and {@code Secure} in particular is derived rather
     * than literal, so two chains would be two chances to drift.
     */
    private NewCookie bindingCookie(String state, String value, int maxAge) {
        return new NewCookie.Builder(nonceCookieName(state)).value(value).path(COOKIE_PATH).httpOnly(true)
                .secure(connectionsConfig.redirectUri().regionMatches(true, 0, "https:", 0, 6)).maxAge(maxAge)
                .sameSite(NewCookie.SameSite.LAX).build();
    }

    /** Expires the binding cookie once its flow is over, win or lose. */
    private NewCookie expiredBindingCookie(String state) {
        return bindingCookie(state, "", 0);
    }

    private static String nonceCookieName(String state) {
        return COOKIE_PREFIX + state;
    }

    private String buildAuthorizationUrl(ConnectionConfiguration connection, OAuthState state, String codeVerifier) {
        var oauth = connection.getOauth();
        var params = new LinkedHashMap<String, String>();
        params.put("response_type", "code");
        params.put("client_id", oauth.getClientId());
        params.put("redirect_uri", state.getRedirectUri());
        params.put("state", state.getState());
        params.put("code_challenge", sha256(codeVerifier));
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
    public Response callback(String code, String state, String error, String errorDescription, HttpHeaders headers) {
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

        // SECOND: prove the callback reached the browser that started the flow.
        // After the claim, deliberately — the state is single-use whichever way this
        // check goes, so a failed binding cannot be retried with the same state.
        if (!bindingMatches(oauthState, headers, state)) {
            count("connection.oauth.callback.count", "outcome", "binding_mismatch", null);
            LOGGER.warnf("An OAuth callback for connection '%s' arrived without the browser binding that started the flow. The state was "
                    + "valid, so this is either a link followed in a different browser or an attempt to file somebody else's tokens "
                    + "under another principal.", sanitize(oauthState.getConnectionName()));
            return redirect(oauthState.getReturnTo(), "error", "invalid_state", expiredBindingCookie(state));
        }

        if (error != null && !error.isBlank()) {
            // The user declined, or the provider refused. The provider's own
            // description is NOT echoed onward: it is attacker-influenceable text
            // heading for a browser.
            count("connection.oauth.callback.count", "outcome", "provider_error", null);
            LOGGER.warnf("The provider refused an authorization for connection '%s' (%s)", oauthState.getConnectionName(), sanitize(error));
            return redirect(oauthState.getReturnTo(), "error", "authorization_declined", expiredBindingCookie(state));
        }
        if (code == null || code.isBlank()) {
            count("connection.oauth.callback.count", "outcome", "bad_state", null);
            return redirect(oauthState.getReturnTo(), "error", "missing_code", expiredBindingCookie(state));
        }

        ConnectionConfiguration connection;
        try {
            connection = connectionRegistry.require(new ConnectionReference(oauthState.getTenantId(), oauthState.getConnectionName()));
        } catch (ConnectionException e) {
            count("connection.oauth.callback.count", "outcome", "exchange_failed", null);
            return redirect(oauthState.getReturnTo(), "error", "connection_removed", expiredBindingCookie(state));
        }

        try {
            TokenResponse token = tokenClient.authorizationCode(connection, resolveClientSecret(connection), code, oauthState.getCodeVerifier(),
                    oauthState.getRedirectUri());
            // The principal comes from the CLAIMED ROW. Reading it from a query
            // parameter would let anyone who obtains a state install a grant under
            // somebody else's name.
            tokenService.persistNew(connection, oauthState.getTenantId(), oauthState.getPrincipal(), token, token.refreshToken());
            count("connection.oauth.callback.count", "outcome", "success", connection);
            return redirect(oauthState.getReturnTo(), "connected", connection.getName(), expiredBindingCookie(state));
        } catch (ConnectionException e) {
            count("connection.oauth.callback.count", "outcome", "exchange_failed", connection);
            LOGGER.warnf("Token exchange failed for connection '%s': %s", connection.getName(), e.getReason());
            return redirect(oauthState.getReturnTo(), "error", "exchange_failed", expiredBindingCookie(state));
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
        if (name == null || name.isBlank()) {
            throw new BadRequestException("A connection name is required.");
        }
        // By NAME, without requiring the connection to still exist. Grants are filed
        // under the name, and the case that matters most is exactly the one where the
        // connection is gone: an admin deletes it while automatic cleanup is failing,
        // and the user is left holding a live refresh token with no way to revoke it
        // because unlinking insisted on a 404 first. Unlinking must never be harder
        // than linking was.
        boolean deleted = grantStore.delete(callerTenant(), name, principal);
        // Deliberately the same answer either way: whether a given user had linked a
        // given connection is not something a caller needs to learn by probing.
        LOGGER.infof("Disconnect for connection '%s' (existing grant: %s)", sanitize(name), deleted);
        return Response.noContent().build();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Refuses every route on this resource when the feature is off.
     * <p>
     * A 404, matching what {@link #callback} answers in the same state: one
     * disabled feature that gives two different answers is a puzzle for whoever is
     * turning it on. It is also the status whose body actually arrives —
     * {@code ClientErrorExceptionMapper} copies a message into the response only
     * for 4xx, so the 503 this used to throw delivered an empty body and left the
     * one sentence naming the setting to fix in the server log.
     */
    private void requireEnabled() {
        if (!connectionsConfig.isEnabled()) {
            throw new NotFoundException("Connections are disabled. Set eddi.connections.enabled=true to use them.");
        }
    }

    /**
     * The verified caller, or a refusal.
     * <p>
     * {@code @Authenticated} is a no-op when {@code authorization.enabled=false},
     * so this is checked in code as well. The startup guard only logs about that
     * combination; enforcement is this method, together with the 400
     * {@code RestConnectionStore} returns when a {@code PER_USER} connection is
     * written to a deployment with authorization disabled. Between them a grant can
     * never be minted for a self-asserted principal.
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
        return credentialReferenceResolver.resolveRequired(connection.getOauth().getClientSecret(), connection.getName(), "client secret");
    }

    /**
     * Redirects the browser, tolerating a state row that carries no destination —
     * or one whose destination cannot be turned into a URI at all.
     * <p>
     * {@code authorize} stores only a destination {@code isAllowedReturnTo}
     * accepted, but a row written by an earlier version or by a direct database
     * write carries whatever it carries — and by the time the callback gets here it
     * has already claimed the state and may have stored the grant, so throwing
     * would leave the user with a 500 and no way to retry (their state is
     * consumed). Every failure therefore degrades to the default page instead.
     */
    private Response redirect(String returnTo, String key, String value, NewCookie... cookies) {
        var response = Response.seeOther(destination(returnTo, key, value));
        for (NewCookie cookie : cookies) {
            response.cookie(cookie);
        }
        return response.build();
    }

    private URI destination(String returnTo, String key, String value) {
        String requested = (returnTo == null || returnTo.isBlank()) ? connectionsConfig.defaultReturnTo() : returnTo;
        URI target = buildDestination(requested, key, value);
        if (target == null) {
            LOGGER.warn("A stored returnTo could not be turned into a redirect target; sending the browser to the default page instead");
            target = buildDestination(connectionsConfig.defaultReturnTo(), key, value);
        }
        // A misconfigured public base URL would take the default page down with it,
        // and there is still a browser waiting. Relative is fine: the Manager sends
        // relative returnTo values already, and JAX-RS resolves them.
        return target == null ? URI.create("/") : target;
    }

    /**
     * The destination with the outcome appended, or {@code null} if it will not
     * parse. A fragment is dropped rather than appended past, since a query
     * appended after a fragment is not a query.
     */
    private static URI buildDestination(String destination, String key, String value) {
        if (destination == null || destination.isBlank()) {
            return null;
        }
        String base = destination;
        int fragment = base.indexOf('#');
        if (fragment >= 0) {
            base = base.substring(0, fragment);
        }
        String separator = base.contains("?") ? "&" : "?";
        try {
            return URI.create(base + separator + encode(key) + "=" + encode(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Whether the callback carries the nonce this flow was started with.
     * <p>
     * A row written before this check existed has no {@code nonceHash}, and is
     * refused rather than grandfathered: such a row lives at most
     * {@link #STATE_TTL} past an upgrade, so accepting it buys ten minutes of
     * convenience in exchange for leaving the hole open on exactly the deployments
     * that just patched it. The user retries and the retry is bound.
     */
    private boolean bindingMatches(OAuthState oauthState, HttpHeaders headers, String state) {
        if (oauthState.getNonceHash() == null || oauthState.getNonceHash().isBlank()) {
            return false;
        }
        if (headers == null || state == null || state.isBlank()) {
            return false;
        }
        var cookie = headers.getCookies() == null ? null : headers.getCookies().get(nonceCookieName(state));
        if (cookie == null || cookie.getValue() == null || cookie.getValue().isBlank()) {
            return false;
        }
        // Constant time. The comparison is over a hash rather than the nonce, but a
        // byte-at-a-time equals on a value an attacker can resend is a timing oracle
        // for the stored hash, and there is no reason to leave one lying around.
        return MessageDigest.isEqual(sha256(cookie.getValue()).getBytes(StandardCharsets.US_ASCII),
                oauthState.getNonceHash().getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * base64url(sha256(value)), unpadded.
     * <p>
     * Both the PKCE challenge (RFC 7636 {@code S256}) and the stored form of the
     * browser-binding nonce are exactly this, so they share one implementation.
     * SHA-256 is mandated by the platform; if it is genuinely absent neither
     * guarantee can be honoured, and the flow must not quietly continue without
     * them.
     */
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable, so PKCE cannot be applied and the OAuth flow cannot be bound to a browser",
                    e);
        }
    }

    /**
     * Records one outcome.
     * <p>
     * The tag KEYS are fixed whether or not a connection is in hand, because a
     * meter name registered with two different tag shapes is a registration failure
     * and Quarkus builds the Prometheus registry with
     * {@code throwExceptionOnRegistrationFailure}. The callback emits this same
     * meter both with a connection and without one, so the two shapes would have
     * collided in production and not in any test that exercised one path.
     * <p>
     * The whole body is guarded for the same reason the redirect is: by the time
     * the success counter is reached the grant is already stored, and no
     * instrumentation failure may turn a completed link into a 500 the user cannot
     * retry — their state is consumed.
     */
    private void count(String metric, String tagName, String tagValue, ConnectionConfiguration connection) {
        if (meterRegistry == null) {
            return;
        }
        try {
            // The connection NAME is never a tag — author-supplied and unbounded.
            String authType = connection != null && connection.getAuthType() != null ? connection.getAuthType().name() : "unknown";
            meterRegistry.counter(metric, tagName, tagValue, "authType", authType).increment();
        } catch (RuntimeException e) {
            LOGGER.debugf(e, "Could not record metric '%s'", metric);
        }
    }

    /**
     * The tenant a listing is scoped to.
     * <p>
     * A single method rather than a literal at each call site, so the multi-tenancy
     * work has one place to change. Until {@code TenantContext} lands this is the
     * default tenant, which is also what every connection document defaults to.
     * <p>
     * It does not move alone. {@link #requireConnection} looks the connection up
     * under the same default and must take its tenant from the same source;
     * {@code ConnectionConfiguration.tenantId} has to be populated from the tenant
     * context rather than from the stored document; and
     * {@code RestConnectionStore.requireDefaultTenant()}, which today refuses any
     * other tenant at the write boundary precisely because this method cannot see
     * one, comes out at that point. Changing any one of the four on its own files a
     * grant under a tenant another of them cannot find.
     */
    private static String callerTenant() {
        return ConnectionReference.DEFAULT_TENANT;
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
