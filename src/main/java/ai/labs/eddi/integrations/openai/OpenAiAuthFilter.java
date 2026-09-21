/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Authenticates callers of the {@code /v1} OpenAI-compatible surface and
 * resolves the EDDI {@code userId} the conversation runs as.
 * <p>
 * <b>Two modes, selected by {@code eddi.openai-compat.http-policy}:</b>
 * <ul>
 * <li><b>{@code permit}</b> (default) — Quarkus lets {@code /v1/*} through
 * unauthenticated and this filter enforces the shared API key. Necessary
 * because Open WebUI sends an opaque {@code sk-…} secret, which the OIDC
 * mechanism would reject as a malformed JWT long before any application code
 * ran.</li>
 * <li><b>{@code authenticated}</b> — Quarkus OIDC has already validated a real
 * bearer token; this filter only reads the resulting identity.</li>
 * </ul>
 * <p>
 * <b>On trusting {@code X-OpenWebUI-User-Id}:</b> the header is believed only
 * when the caller already proved possession of the API key, i.e. Open WebUI
 * acts as a trusted proxy that has authenticated its own users. This is a
 * deliberate delegation, not an oversight — but it means a leaked key permits
 * impersonating any user. Set {@code trust-user-headers=false} to disable it.
 *
 * @since 6.1.0
 */
@Provider
public class OpenAiAuthFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = Logger.getLogger(OpenAiAuthFilter.class);

    /** Path prefix this filter guards. Everything else passes untouched. */
    private static final String GUARDED_PREFIX = "v1/";

    private static final String BEARER_PREFIX = "Bearer ";

    /** Open WebUI's per-chat identifier — the per-conversation session key. */
    public static final String HEADER_CHAT_ID = "X-OpenWebUI-Chat-Id";

    /** Open WebUI's authenticated user id. */
    public static final String HEADER_USER_ID = "X-OpenWebUI-User-Id";

    /** Request property under which the resolved EDDI userId is published. */
    public static final String PROP_USER_ID = "eddi.openai.userId";

    private final OpenAiCompatConfig config;
    private final SecurityIdentity securityIdentity;

    @Inject
    public OpenAiAuthFilter(OpenAiCompatConfig config, SecurityIdentity securityIdentity) {
        this.config = config;
        this.securityIdentity = securityIdentity;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String path = requestContext.getUriInfo().getPath();
        if (path == null || !isGuarded(path)) {
            return;
        }

        if (!config.isEnabled()) {
            // Let the resource answer with the disabled-endpoint error, so the
            // reason is explicit rather than an authentication failure.
            return;
        }

        if (!config.isOidcMode() && !authenticateWithApiKey(requestContext)) {
            return;
        }

        String userId = resolveUserId(requestContext);
        if (userId == null) {
            abort(requestContext, Response.Status.UNAUTHORIZED.getStatusCode(),
                    "Could not determine the calling user. Send X-OpenWebUI-User-Id "
                            + "(with eddi.openai-compat.trust-user-headers=true), authenticate via OIDC, "
                            + "or set eddi.openai-compat.allow-anonymous=true.");
            return;
        }
        requestContext.setProperty(PROP_USER_ID, userId);
    }

    /**
     * @return {@code true} when the request may proceed; {@code false} when this
     *         method has already aborted it
     */
    private boolean authenticateWithApiKey(ContainerRequestContext requestContext) {
        if (!config.hasApiKey()) {
            // No key configured. OpenAiStartupGuard refuses this combination when
            // authorization is on, so reaching here means an intentionally open
            // deployment (dev mode, or a network-isolated instance).
            return true;
        }

        String presented = bearerToken(requestContext.getHeaderString(HttpHeaders.AUTHORIZATION));
        if (presented == null || !constantTimeEquals(presented, config.getApiKey())) {
            LOGGER.debugf("Rejected /v1 request with %s API key",
                    presented == null ? "missing" : "invalid");
            abort(requestContext, Response.Status.UNAUTHORIZED.getStatusCode(),
                    "Incorrect API key provided.");
            return false;
        }
        return true;
    }

    /**
     * Resolve the EDDI userId, in precedence order: OIDC principal, then the
     * trusted Open WebUI header, then the anonymous default when explicitly
     * allowed. {@code null} means "refuse" — serving an unidentifiable caller would
     * merge every such caller into one shared conversation, and therefore one
     * shared memory.
     */
    private String resolveUserId(ContainerRequestContext requestContext) {
        if (config.isOidcMode()) {
            if (securityIdentity != null && !securityIdentity.isAnonymous()
                    && securityIdentity.getPrincipal() != null) {
                String name = securityIdentity.getPrincipal().getName();
                if (name != null && !name.isBlank()) {
                    return name;
                }
            }
        } else if (config.isTrustUserHeaders()) {
            String header = requestContext.getHeaderString(HEADER_USER_ID);
            if (header != null && !header.isBlank()) {
                return header.trim();
            }
        }

        return config.isAllowAnonymous() ? config.getDefaultUser() : null;
    }

    /**
     * Path matching, tolerant of the leading slash JAX-RS may or may not include.
     * <p>
     * <b>Why returning {@code false} here cannot bypass authentication.</b> CodeQL
     * reports {@code java/user-controlled-bypass} against the early return in
     * {@link #filter}, on the grounds that a user-controlled path decides whether
     * an authentication check runs. It does — but this filter is not the only thing
     * standing in front of those paths, and it is not the first.
     * <p>
     * {@code application.properties} ends with a catch-all,
     * {@code quarkus.http.auth.permission.authenticated.paths=/,/*} at policy
     * {@code authenticated}. Quarkus HTTP authorization runs <em>before</em> JAX-RS
     * request filters, so any path this method declines has already been required
     * to authenticate. The single exception is {@code /v1/*}, which has its own
     * permission entry at policy {@code permit} precisely so the shared API key can
     * be checked <em>here</em> instead of being rejected at the OIDC layer — and
     * {@code permit} is exactly the set this method returns {@code true} for.
     * <p>
     * So the guard does not choose between "authenticated" and "anonymous". It
     * chooses between "the adapter's key check" and "Quarkus' own check", and
     * declining is the safe branch. Narrowing it would only mean applying an
     * OpenAI-specific key check to endpoints that are not the OpenAI adapter.
     */
    private static boolean isGuarded(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return normalized.equals("v1") || normalized.startsWith(GUARDED_PREFIX);
    }

    private static String bearerToken(String authorizationHeader) {
        if (authorizationHeader == null) {
            return null;
        }
        String value = authorizationHeader.trim();
        if (value.length() <= BEARER_PREFIX.length()
                || !value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        return value.substring(BEARER_PREFIX.length()).trim();
    }

    /**
     * Compare secrets without leaking their length relationship through timing.
     * {@code String.equals} short-circuits on the first differing character.
     */
    private static boolean constantTimeEquals(String presented, String expected) {
        return MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }

    private static void abort(ContainerRequestContext requestContext, int status, String message) {
        requestContext.abortWith(Response.status(status)
                .entity(OpenAiApiException.unauthorized(message).toErrorResponse())
                .type(MediaType.APPLICATION_JSON)
                .build());
    }
}
