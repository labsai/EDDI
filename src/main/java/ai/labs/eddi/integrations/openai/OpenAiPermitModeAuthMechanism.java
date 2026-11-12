/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import io.quarkus.security.identity.IdentityProviderManager;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.AnonymousAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.ChallengeData;
import io.quarkus.vertx.http.runtime.security.HttpAuthenticationMechanism;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Keeps Quarkus OIDC away from {@code /v1} while the OpenAI-compatible adapter
 * authenticates its callers itself
 * ({@code eddi.openai-compat.http-policy=permit}), so the shared API key
 * reaches {@link OpenAiAuthFilter}.
 * <p>
 * <b>Why the {@code permit} permission alone was not enough.</b> Quarkus
 * authenticates <em>proactively</em> (no {@code quarkus.http.auth.proactive} is
 * set): {@code HttpSecurityRecorder.AuthenticationHandler} runs every
 * {@link HttpAuthenticationMechanism} before the path's permission policy is
 * consulted, and an {@code AuthenticationFailedException} from any of them is
 * answered with the mechanism's challenge — a {@code 401} with
 * {@code WWW-Authenticate} — whatever the policy. The OIDC bearer mechanism
 * treats {@code Authorization: Bearer sk-…} as a token, fails to verify it and
 * throws, so with {@code quarkus.oidc.tenant-enabled=true} every shared-key
 * request was refused before {@link OpenAiAuthFilter} ran. Only requests with
 * no {@code Authorization} header got through, because the OIDC mechanism
 * returns no identity for them instead of failing.
 * <p>
 * <b>What this does.</b> {@code HttpAuthenticator} tries the mechanisms in
 * descending {@link #getPriority() priority} and, with inclusive authentication
 * off (the default), stops at the first one that returns an identity. This
 * mechanism ranks just above OIDC ({@code quarkus.oidc.priority}, default 1001)
 * and, for a {@code /v1} request in permit mode, returns the anonymous identity
 * — the same one Quarkus would assign to a request that carried no credential
 * at all. OIDC is therefore never asked about the opaque key, the
 * {@code permit} policy admits the anonymous caller, and the adapter's own key
 * check decides: the right key is served, a wrong one (or a JWT) gets the
 * adapter's {@code 401 invalid_api_key}. It verifies nothing and grants
 * nothing: an anonymous identity is what any caller gets by omitting the
 * header.
 * <p>
 * <b>Fail closed.</b> For every other request it returns no identity and the
 * remaining mechanisms run exactly as before. It stands down entirely unless
 * all of these hold, so a misconfiguration leaves OIDC in charge rather than
 * opening anything:
 * <ul>
 * <li>the adapter is enabled;</li>
 * <li>{@code eddi.openai-compat.http-policy} is {@code permit} (the filter
 * checks the key) <em>and</em> the policy Quarkus actually applies to
 * {@code /v1/*} ({@code quarkus.http.auth.permission.openai-compat.policy}) is
 * {@code permit} — someone overriding only the latter must not end up with
 * neither check;</li>
 * <li>inclusive authentication is off: with
 * {@code quarkus.http.auth.inclusive=true} Quarkus also runs OIDC after this
 * mechanism, so it could not help and would only make requests without a header
 * fail ({@link OpenAiStartupGuard} warns about that combination);</li>
 * <li>the method is one the {@code openai-compat} permission lists, and both
 * forms of the path Quarkus matches permissions against — the router's
 * {@link RoutingContext#normalizedPath()} and its
 * {@link HttpSecurityUtils#normalizePath fully normalized} form — are
 * {@code /v1} or below it. A spelling only one of them places under {@code /v1}
 * (a matrix parameter, an encoded dot segment) is left to OIDC.</li>
 * </ul>
 * With {@code http-policy=authenticated} the mechanism is inactive and OIDC
 * validates the bearer token as before.
 * <p>
 * <b>Why not an OIDC tenant.</b> A {@code TenantConfigResolver} or a named
 * disabled tenant for {@code /v1} would also stop the verification, but in
 * quarkus-oidc either one changes how the <em>default</em> tenant starts:
 * {@code TenantContextFactory} silently disables a default tenant with no
 * {@code auth-server-url} once a {@code TenantConfigResolver} bean or a named
 * tenant exists, where today that is a startup failure. A named tenant is also
 * selected by any request path segment equal to its id. This mechanism touches
 * neither.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class OpenAiPermitModeAuthMechanism implements HttpAuthenticationMechanism {

    /** The only path, and everything below it, this mechanism acts on. */
    static final String GUARDED_PATH = "/v1";

    /** Quarkus' built-in policy that admits any caller, anonymous included. */
    static final String PERMIT_POLICY = "permit";

    private final boolean active;
    private final Set<String> methods;
    private final int priority;

    /**
     * Decides once, at startup, whether the mechanism is active: the adapter is
     * enabled, both the adapter's {@code http-policy} and the HTTP permission
     * Quarkus applies to {@code /v1/*} are {@code permit}, and inclusive
     * authentication is off. Any other combination leaves it inactive, so OIDC
     * keeps {@code /v1}.
     *
     * @param permissionMethods
     *            the methods the {@code openai-compat} permission lists; none means
     *            every method, as it does to Quarkus
     * @param oidcPriority
     *            OIDC's mechanism priority; this mechanism ranks one above it
     */
    @Inject
    public OpenAiPermitModeAuthMechanism(OpenAiCompatConfig config,
            @ConfigProperty(name = "quarkus.http.auth.permission.openai-compat.policy") Optional<String> permissionPolicy,
            @ConfigProperty(name = "quarkus.http.auth.permission.openai-compat.methods") Optional<List<String>> permissionMethods,
            @ConfigProperty(name = "quarkus.http.auth.inclusive", defaultValue = "false") boolean inclusiveAuthentication,
            @ConfigProperty(name = "quarkus.oidc.priority", defaultValue = "1001") int oidcPriority) {
        this.active = config.isEnabled()
                && PERMIT_POLICY.equals(config.getHttpPolicy())
                && PERMIT_POLICY.equals(permissionPolicy.map(String::trim).orElse(null))
                && !inclusiveAuthentication;
        // No methods on a permission means "every method" to Quarkus; mirror that.
        this.methods = permissionMethods.orElse(List.of()).stream()
                .map(String::trim)
                .filter(m -> !m.isEmpty())
                .map(m -> m.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        this.priority = oidcPriority == Integer.MAX_VALUE ? Integer.MAX_VALUE : oidcPriority + 1;
    }

    /**
     * Whether this mechanism takes {@code /v1} away from OIDC at all, i.e. the
     * adapter is enabled in permit mode.
     */
    boolean isActive() {
        return active;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(RoutingContext context, IdentityProviderManager identityProviderManager) {
        if (!appliesTo(context)) {
            return Uni.createFrom().nullItem();
        }
        // Exactly what HttpSecurityRecorder assigns when no mechanism produced an
        // identity — returned here so the lower-priority OIDC mechanism is not asked.
        return identityProviderManager.authenticate(
                HttpSecurityUtils.setRoutingContextAttribute(new AnonymousAuthenticationRequest(), context));
    }

    @Override
    public Uni<ChallengeData> getChallenge(RoutingContext context) {
        // Never challenges: the adapter answers its own 401s in the OpenAI envelope,
        // and on every other path the next mechanism's challenge applies unchanged.
        return Uni.createFrom().nullItem();
    }

    @Override
    public int getPriority() {
        return priority;
    }

    /**
     * Whether this request is one the mechanism takes away from OIDC. Every
     * condition must hold, and any doubt answers {@code false}, which leaves the
     * request to OIDC exactly as before: the mechanism is active, the method is one
     * the {@code openai-compat} permission covers, and the path is {@code /v1} or
     * below it in <em>both</em> forms Quarkus matches permissions against — the
     * router's normalized path and its fully normalized form. A spelling only one
     * of them places under {@code /v1} (a matrix parameter, an encoded dot segment)
     * is not taken.
     */
    boolean appliesTo(RoutingContext context) {
        if (!active || context == null) {
            return false;
        }
        HttpServerRequest request = context.request();
        if (!methods.isEmpty() && (request == null || request.method() == null
                || !methods.contains(request.method().name().toUpperCase(Locale.ROOT)))) {
            return false;
        }
        String routerPath = context.normalizedPath();
        if (routerPath == null || !isGuardedPath(routerPath)) {
            return false;
        }
        return isGuardedPath(HttpSecurityUtils.normalizePath(routerPath));
    }

    /**
     * {@code /v1} itself or a path below it; {@code /v1x} and {@code /v10} are not.
     */
    static boolean isGuardedPath(String path) {
        return path != null
                && (path.equals(GUARDED_PATH) || path.startsWith(GUARDED_PATH + "/"));
    }
}
