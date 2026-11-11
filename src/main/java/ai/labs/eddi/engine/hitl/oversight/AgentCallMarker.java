/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight;

import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.security.CallerIdentity;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.OriginMatcher;
import ai.labs.eddi.engine.security.SelfUrlResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The engine-owned header an httpcall carries when it is aimed at <em>this
 * deployment</em>, so the server can refuse an oversight write or a HITL
 * decision an agent's own tool call makes — even one that forwards the caller's
 * token ({@code ${caller:token}}).
 * <p>
 * Only requests to this deployment are marked: no EDDI-specific header reaches
 * a third-party API, and every other httpcall keeps its wire format. A target
 * is "this deployment" when it is on {@link SelfUrlResolver}'s origin
 * ({@code eddi.self.base-url}, or the derived listener address), on a loopback
 * address, on a host listed in {@value #SELF_HOSTS_KEY} (the public or
 * in-cluster names the deployment is also reached by), or on the origin the
 * turn's caller addressed — the public hostname a browser used, which is
 * exactly where {@code ${caller:token}} is released. A request that carries a
 * released caller token is marked whatever its target, through
 * {@link #apply(AgentCallMarker, IRequest, URI, boolean)}: the token is only
 * ever released to this deployment, so such a request is one by definition. On
 * those requests the header is set last, after the configured headers, so a
 * configuration cannot remove or replace it.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class AgentCallMarker {

    /** The header name. */
    public static final String HEADER = "X-EDDI-Agent-Call";
    /** The header value. */
    public static final String VALUE = "true";
    /**
     * Comma-separated {@code host} or {@code host:port} this deployment is also
     * reached by; a bare host matches any port.
     */
    public static final String SELF_HOSTS_KEY = "eddi.hitl.oversight.self-hosts";

    private final Instance<SelfUrlResolver> selfUrlResolver;
    private final Instance<CallerIdentityContext> callerIdentityContext;
    private final Set<String> selfHosts;

    @Inject
    public AgentCallMarker(Instance<SelfUrlResolver> selfUrlResolver, Instance<CallerIdentityContext> callerIdentityContext,
            @ConfigProperty(name = SELF_HOSTS_KEY) Optional<String> selfHosts) {
        this.selfUrlResolver = selfUrlResolver;
        this.callerIdentityContext = callerIdentityContext;
        this.selfHosts = parse(selfHosts.orElse(""));
    }

    /** Test seam: a self origin, no caller context. */
    public AgentCallMarker(Instance<SelfUrlResolver> selfUrlResolver, Optional<String> selfHosts) {
        this(selfUrlResolver, null, selfHosts);
    }

    /** Test seam: no self origin, explicit extra hosts. */
    public AgentCallMarker(Set<String> selfHosts) {
        this(null, null, Optional.of(String.join(",", selfHosts)));
    }

    /**
     * Marks {@code request} when {@code target} is this deployment. Call it after
     * the configured headers are written.
     */
    public void apply(IRequest request, URI target) {
        if (request != null && targetsSelf(target)) {
            request.setHttpHeader(HEADER, VALUE);
        }
    }

    /**
     * {@link #apply} through a marker that may be absent (a directly constructed
     * executor): without one, only loopback targets are marked.
     */
    public static void apply(AgentCallMarker marker, IRequest request, URI target) {
        apply(marker, request, target, false);
    }

    /**
     * {@link #apply(AgentCallMarker, IRequest, URI)}, marking unconditionally when
     * {@code callerTokenReleased}: a request carrying the caller's own token is
     * aimed at this deployment by construction, whatever name it uses for it.
     */
    public static void apply(AgentCallMarker marker, IRequest request, URI target, boolean callerTokenReleased) {
        if (callerTokenReleased && request != null) {
            request.setHttpHeader(HEADER, VALUE);
            return;
        }
        (marker != null ? marker : LOOPBACK_ONLY).apply(request, target);
    }

    private static final AgentCallMarker LOOPBACK_ONLY = new AgentCallMarker(Set.of());

    /** Whether a request target is this deployment. */
    public boolean targetsSelf(URI target) {
        if (target == null || target.getHost() == null) {
            return false;
        }
        String host = bare(target.getHost());
        if (isLoopback(host)) {
            return true;
        }
        if (selfHosts.contains(host) || target.getPort() >= 0 && selfHosts.contains(host + ":" + target.getPort())
                || target.getPort() < 0 && selfHosts.contains(host + ":" + defaultPort(target))) {
            return true;
        }
        if (onCallerOrigin(target)) {
            return true;
        }
        try {
            return selfUrlResolver != null && selfUrlResolver.isResolvable() && selfUrlResolver.get().isSelf(target);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Whether {@code target} is the origin the current turn's caller addressed —
     * this deployment as the caller reached it, typically its public hostname,
     * which neither loopback nor {@code eddi.self.base-url} need name.
     */
    private boolean onCallerOrigin(URI target) {
        try {
            if (callerIdentityContext == null || !callerIdentityContext.isResolvable()) {
                return false;
            }
            CallerIdentity identity = callerIdentityContext.get().current();
            return identity != null && identity.origin() != null && OriginMatcher.sameOrigin(identity.origin(), target);
        } catch (RuntimeException e) {
            return false;
        }
    }

    static boolean isLoopback(String host) {
        if ("localhost".equals(host) || host.endsWith(".localhost")) {
            return true;
        }
        if (!looksLikeIpLiteral(host)) {
            // Never resolve a name here: a DNS lookup per httpcall, and a name an
            // attacker controls could point anywhere.
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static boolean looksLikeIpLiteral(String host) {
        return host.contains(":") || host.chars().allMatch(c -> c == '.' || Character.isDigit(c));
    }

    private static int defaultPort(URI target) {
        return "https".equalsIgnoreCase(target.getScheme()) ? 443 : 80;
    }

    private static String bare(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        return h.startsWith("[") && h.endsWith("]") ? h.substring(1, h.length() - 1) : h;
    }

    private static Set<String> parse(String value) {
        Set<String> hosts = new HashSet<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                hosts.add(trimmed);
            }
        }
        return Set.copyOf(hosts);
    }
}
