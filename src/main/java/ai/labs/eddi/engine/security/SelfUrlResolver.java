/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.util.Optional;

/**
 * The base URL at which <em>this</em> EDDI process can be reached by something
 * running beside it — most importantly by EDDI itself.
 *
 * <h3>The bug this exists for</h3> The Platform Operator's tools are ordinary
 * httpcalls whose {@code targetServerUrl} points back at this deployment's
 * admin API. The Manager provisioned them with {@code window.location.origin}:
 * the origin the <em>browser</em> used. Those two coincide only when nothing
 * sits between the browser and EDDI. On a staging deployment reached through an
 * SSH tunnel at {@code http://localhost:7080}, in front of a container
 * listening on {@code :7070}, all 22 generated resources were provisioned with
 * {@code http://localhost:7080} — an address that means nothing inside the
 * container. The operator deployed, reported a verified gate, and then failed
 * every single tool call, narrating the connection refusals as "a problem with
 * the platform's internal services". The browser cannot know this address; only
 * the server can. So the server answers it, and the Manager asks (see
 * {@code GET /administration/operator/self-url}).
 *
 * <h3>How it is resolved</h3>
 * <ol>
 * <li>{@code eddi.self.base-url}, when set. Needed where loopback is genuinely
 * wrong — TLS terminated in-process, a sidecar that must be addressed by
 * service name, an in-cluster hostname a mesh requires.</li>
 * <li>Otherwise {@code http://127.0.0.1:${quarkus.http.port}} — the same
 * address
 * {@link ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory} has
 * always used for EDDI's internal loopback hop, and the same one the
 * container's own health check probes. It is correct behind a reverse proxy and
 * on a remapped published port precisely <em>because</em> it ignores both: a
 * process reaches itself without going back out through whatever is in front of
 * it.</li>
 * </ol>
 * The port is read from configuration rather than assumed, so a deployment that
 * moves off 7070 is resolved correctly without anyone setting the override.
 *
 * <h3>Trust</h3> The resolved value is deployment configuration — a property in
 * {@code application.properties} or an environment variable, set by whoever
 * operates this instance. It is never agent configuration, conversation content
 * or a request header, which is what lets {@link CallerIdentityResolver} treat
 * it as "this process" when deciding whether forwarding the caller's token is
 * safe.
 *
 * @author ginccc
 * @since 6.4.0
 */
@ApplicationScoped
public class SelfUrlResolver {

    private static final Logger LOGGER = Logger.getLogger(SelfUrlResolver.class);

    /** Deployment override for the address EDDI can reach itself at. */
    public static final String CONFIG_KEY = "eddi.self.base-url";

    /** {@link #source()} when {@link #CONFIG_KEY} supplied the value. */
    public static final String SOURCE_CONFIGURED = "configured";

    /** {@link #source()} when the value was derived from the HTTP port. */
    public static final String SOURCE_LOOPBACK = "loopback";

    /**
     * {@link #source()} when neither the override nor a fixed HTTP port is
     * available — {@code quarkus.http.port=0} asks for a random port, which
     * configuration cannot name. {@link #baseUrl()} is then {@code null} and
     * {@link #isSelf} answers {@code false} for everything: a confident wrong
     * answer (the old 7070 fallback) is worse than none.
     */
    public static final String SOURCE_UNRESOLVED = "unresolved";

    private final String baseUrl;
    private final String origin;
    private final String source;

    /**
     * Note for tests: {@code @QuarkusTest} binds {@code quarkus.http.test-port}
     * (8081 by default), not {@code quarkus.http.port}, so inside such a test the
     * derived address names a port nothing listens on. {@code RestInterfaceFactory}
     * has the same property. Unit tests construct this directly with the port they
     * mean.
     */
    @Inject
    public SelfUrlResolver(@ConfigProperty(name = CONFIG_KEY) Optional<String> configuredBaseUrl,
            @ConfigProperty(name = "quarkus.http.port", defaultValue = "7070") int httpPort) {
        String loopback = httpPort > 0 ? "http://127.0.0.1:" + httpPort : null;
        String candidate = configuredBaseUrl.map(String::trim).filter(value -> !value.isEmpty()).orElse(null);
        String resolved = loopback;
        String resolvedSource = loopback != null ? SOURCE_LOOPBACK : SOURCE_UNRESOLVED;
        if (candidate != null) {
            String normalized = normalize(candidate);
            if (normalized != null) {
                resolved = normalized;
                resolvedSource = SOURCE_CONFIGURED;
            } else {
                // Falling back rather than failing startup: a malformed override must not
                // take the whole deployment down, and loopback is the answer that was
                // correct before anyone set the property. Logged at ERROR because the
                // operator asked for something specific and is not getting it.
                LOGGER.errorf("%s is not a usable http(s) base URL ('%s') — ignoring it. "
                        + "Set it to a scheme://host[:port] this process can reach itself at.", CONFIG_KEY, candidate);
            }
        }
        if (resolved == null) {
            LOGGER.warnf("quarkus.http.port is %d (random), so this deployment's own address cannot be derived. "
                    + "Set %s for the Platform Operator's tools to have a target.", httpPort, CONFIG_KEY);
        }
        this.baseUrl = resolved;
        this.origin = resolved != null ? OriginMatcher.normalize(URI.create(resolved)) : null;
        this.source = resolvedSource;
    }

    /**
     * The base URL, without a trailing slash — {@code http://127.0.0.1:7070} — or
     * {@code null} when {@link #source()} is {@link #SOURCE_UNRESOLVED}.
     * <p>
     * No trailing slash because callers append a path that starts with one, and
     * {@code ApiCallExecutor} concatenates the two verbatim: a stored
     * {@code targetServerUrl} of {@code http://host:7070/} produces
     * {@code http://host:7070//agentstore/agents}, which some routers answer and
     * others 404.
     */
    public String baseUrl() {
        return baseUrl;
    }

    /**
     * {@link #SOURCE_CONFIGURED}, {@link #SOURCE_LOOPBACK} or
     * {@link #SOURCE_UNRESOLVED} — surfaced so the Manager can say where the
     * address it is about to provision came from, rather than presenting a guess
     * and a deployment decision identically.
     */
    public String source() {
        return source;
    }

    /** Whether {@link #CONFIG_KEY} supplied the value. */
    public boolean configured() {
        return SOURCE_CONFIGURED.equals(source);
    }

    /** The normalized {@code scheme://host:port} of {@link #baseUrl()}. */
    public String origin() {
        return origin;
    }

    /**
     * Whether a target URI addresses this very process.
     * <p>
     * Origin comparison only — path and query are irrelevant to the question "is
     * this me?".
     */
    public boolean isSelf(URI target) {
        return origin != null && OriginMatcher.sameOrigin(origin, target);
    }

    /**
     * Strip a trailing slash and require an {@code http(s)} URL with a host.
     * <p>
     * {@code validateUrlSyntax} rather than the full SSRF check on purpose: the
     * whole point of this value is to name a loopback or private address, which the
     * address check exists to refuse. It is deployment configuration, not anything
     * user- or agent-supplied, which is the rule {@code validateUrlSyntax}
     * documents as its precondition.
     *
     * @return the normalized URL, or {@code null} when it is not usable
     */
    private static String normalize(String candidate) {
        String trimmed = candidate;
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            UrlValidationUtils.validateUrlSyntax(trimmed);
            return trimmed;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
