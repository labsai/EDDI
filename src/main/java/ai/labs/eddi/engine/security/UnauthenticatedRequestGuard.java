/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Keeps a browser from turning an unauthenticated EDDI into an open API.
 * <p>
 * With authentication off ({@code authorization.enabled=false} — the quick
 * start's {@code EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true}) every endpoint, the
 * MCP server and the secrets vault answer anyone who can reach the port.
 * Binding to loopback stops the network, not the browser on the same machine:
 * <ul>
 * <li><b>DNS rebinding.</b> A page on {@code evil.example} whose name is
 * re-pointed at {@code 127.0.0.1} becomes same-origin with EDDI and can read
 * and write the whole API. Its requests carry {@code Host: evil.example}.</li>
 * <li><b>Blind cross-site writes.</b> Without rebinding, a page can still send
 * CORS "simple" requests — a form-encoded or body-less {@code POST} — that
 * execute even though the page cannot read the answer.</li>
 * </ul>
 * So, only while authentication is off, this filter
 * <ol>
 * <li>answers <b>421</b> to a request whose {@code Host} is not one EDDI is
 * known by: {@code localhost} (and {@code *.localhost}), any IP literal — a
 * rebinding attack needs a DNS name the attacker controls, so an address can
 * never be the product of one, and kubelet probes, Prometheus scrapes and
 * {@code eddi.self.base-url} address EDDI that way — the container-host names
 * {@link #CONTAINER_HOST_NAMES} (how a container reaches an EDDI running on the
 * host; Docker's and Podman's own resolvers answer them, and {@code .internal}
 * is reserved and never delegated in public DNS), or an entry of
 * {@code eddi.security.unauthenticated.allowed-hosts} ({@code host} or
 * {@code host:port}, comma-separated; a bare host matches any port; {@code *}
 * turns the check off);</li>
 * <li>answers <b>403</b> to a
 * {@code POST}/{@code PUT}/{@code PATCH}/{@code DELETE} that a browser marks as
 * cross-site — an {@code Origin} that is neither this request's own origin nor
 * in {@code quarkus.http.cors.origins} ({@code null} included), or, without an
 * {@code Origin}, {@code Sec-Fetch-Site:
 * cross-site}/{@code same-site}. A request with neither header comes from a
 * non-browser client (curl, an SDK) and passes.</li>
 * </ol>
 * {@code /q/health} stays exempt from both, so liveness and readiness probes
 * work whatever host they use. The raw {@code Host} header is read on purpose,
 * not the forwarded host: a rebound page is same-origin and may set
 * {@code X-Forwarded-Host} itself.
 * <p>
 * In dev and test mode Quarkus' own {@code HostValidationFilter} also runs: it
 * is switched on because the server binds {@code localhost}, accepts only
 * {@code localhost}, {@code 127.0.0.1} and {@code [::1]}, and answers anything
 * else — a {@code *.localhost} name, a trailing dot, any host on
 * {@code /q/health} — with an empty <b>400</b>. That is Quarkus, not this
 * filter ({@code quarkus.http.host-validation.allowed-hosts} widens it); a
 * container image binds {@code 0.0.0.0} and does not enable it.
 * <p>
 * Nothing is registered while authentication is on — every endpoint then needs
 * a token, which a cross-site page does not have. Registered as a Vert.x route
 * filter, like {@link HttpMethodGuard}, so it also covers {@code /mcp}, the
 * {@code /q} routes and static assets.
 */
@ApplicationScoped
public class UnauthenticatedRequestGuard {

    private static final Logger LOGGER = Logger.getLogger(UnauthenticatedRequestGuard.class);

    /** Below {@link HttpMethodGuard}, above {@link RequestBodyLimitGuard}. */
    static final int PRIORITY = 19_500;

    static final String ALLOWED_HOSTS_PROPERTY = "eddi.security.unauthenticated.allowed-hosts";

    /**
     * Names a container uses to reach a service on its host — Docker Desktop's
     * {@code host.docker.internal} and {@code gateway.docker.internal}, Podman's
     * {@code host.containers.internal}. They are answered by the container
     * runtime's resolver (or {@code extra_hosts}), and {@code .internal} is a
     * reserved, never-delegated top-level domain, so a rebinding page cannot own
     * one.
     */
    static final Set<String> CONTAINER_HOST_NAMES = Set.of("host.docker.internal", "gateway.docker.internal",
            "host.containers.internal");

    /** Methods that may not change state and pass the cross-site check. */
    static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    /**
     * At most this many distinct refused hosts are logged, so a scan cannot flood
     * the log.
     */
    static final int MAX_LOGGED_HOSTS = 32;

    private static final Pattern HOST_NAME = Pattern.compile("[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)*");

    private static final Pattern IPV4 = Pattern
            .compile("(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}");

    private static final Pattern IPV6 = Pattern.compile("[0-9a-f:.]+");

    /** What the filter decided about one request. */
    enum Decision {
        ALLOW, MISDIRECTED_HOST, CROSS_SITE
    }

    /**
     * A parsed {@code host[:port]}: the host lower-cased, a single trailing dot
     * removed, an IPv6 literal without its brackets; {@code port} is {@code -1}
     * when none was given.
     */
    record HostPort(String host, int port, boolean ipLiteral) {
    }

    /**
     * The policy, built once from configuration.
     *
     * @param anyHost
     *            {@code *} in the allowed hosts: the Host check is off
     * @param allowedHosts
     *            the configured hosts besides the built-in ones
     * @param anyOrigin
     *            {@code *} in {@code quarkus.http.cors.origins}
     * @param allowedOrigins
     *            CORS origins, normalized by {@link OriginMatcher}
     * @param originPatterns
     *            CORS origins written as {@code /regex/}
     */
    record Policy(boolean anyHost, List<HostPort> allowedHosts, boolean anyOrigin, Set<String> allowedOrigins,
            List<Pattern> originPatterns) {

        static Policy of(List<String> hosts, List<String> corsOrigins) {
            boolean anyHost = false;
            List<HostPort> allowed = new ArrayList<>();
            for (String entry : hosts) {
                String trimmed = entry == null ? "" : entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.equals("*")) {
                    anyHost = true;
                    continue;
                }
                HostPort parsed = parseHost(trimmed);
                if (parsed == null) {
                    LOGGER.warnf("[SECURITY] Ignoring %s entry that is not a host or host:port: %s", ALLOWED_HOSTS_PROPERTY,
                            LogSafe.of(trimmed));
                    continue;
                }
                allowed.add(parsed);
            }
            boolean anyOrigin = false;
            Set<String> origins = new LinkedHashSet<>();
            List<Pattern> patterns = new ArrayList<>();
            for (String entry : corsOrigins) {
                String trimmed = entry == null ? "" : entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.equals("*")) {
                    anyOrigin = true;
                } else if (trimmed.length() > 2 && trimmed.startsWith("/") && trimmed.endsWith("/")) {
                    try {
                        patterns.add(Pattern.compile(trimmed.substring(1, trimmed.length() - 1)));
                    } catch (PatternSyntaxException e) {
                        LOGGER.warnf("[SECURITY] Ignoring quarkus.http.cors.origins pattern that does not compile: %s",
                                LogSafe.of(trimmed));
                    }
                } else {
                    String normalized = normalizeOrigin(trimmed);
                    if (normalized != null) {
                        origins.add(normalized);
                    }
                }
            }
            return new Policy(anyHost, List.copyOf(allowed), anyOrigin, Set.copyOf(origins), List.copyOf(patterns));
        }
    }

    @ConfigProperty(name = "authorization.enabled", defaultValue = "false")
    boolean authorizationEnabled;

    @ConfigProperty(name = ALLOWED_HOSTS_PROPERTY)
    Optional<List<String>> allowedHosts = Optional.empty();

    @ConfigProperty(name = "quarkus.http.cors.origins")
    Optional<List<String>> corsOrigins = Optional.empty();

    private volatile Policy policy;

    private final Set<String> loggedHosts = ConcurrentHashMap.newKeySet();

    void register(@Observes Filters filters) {
        if (authorizationEnabled) {
            return;
        }
        policy = Policy.of(allowedHosts.orElse(List.of()), corsOrigins.orElse(List.of()));
        filters.register(this::handle, PRIORITY);
        LOGGER.warn(describe(policy));
    }

    /** The one boot-time line that says what the filter enforces. */
    static String describe(Policy policy) {
        StringBuilder hosts = new StringBuilder(
                "localhost, *.localhost, IP addresses, host.docker.internal, gateway.docker.internal, host.containers.internal");
        for (HostPort host : policy.allowedHosts()) {
            hosts.append(", ").append(format(host));
        }
        String hostRule = policy.anyHost()
                ? "the Host check is OFF (" + ALLOWED_HOSTS_PROPERTY + " contains *), so a DNS-rebinding page can reach the API"
                : "requests addressed to any Host other than " + hosts + " are refused with 421 (" + ALLOWED_HOSTS_PROPERTY + ")";
        String originRule = policy.anyOrigin()
                ? "quarkus.http.cors.origins is *, so cross-site POST/PUT/PATCH/DELETE from any page are accepted"
                : "cross-site POST/PUT/PATCH/DELETE are refused with 403 unless their Origin is in quarkus.http.cors.origins";
        return "[SECURITY] Authentication is off (authorization.enabled=false): " + hostRule + "; " + originRule
                + ". /q/health is exempt. See docs/security.md, \"Running without authentication\".";
    }

    void handle(RoutingContext context) {
        HttpServerRequest request = context.request();
        String path = context.normalizedPath();
        String host = request.getHeader(HttpHeaders.HOST);
        if (host == null && request.authority() != null) {
            host = request.authority().toString();
        }
        Decision decision = decide(policy, request.method().name(), path, host, request.getHeader(HttpHeaders.ORIGIN),
                request.getHeader("Sec-Fetch-Site"));
        switch (decision) {
            case ALLOW -> context.next();
            case MISDIRECTED_HOST -> {
                logRefusedHost(host);
                refuse(context, 421, "This EDDI instance runs without authentication and answers only requests addressed to"
                        + " localhost, an IP address, a container-host name (host.docker.internal and similar) or a host"
                        + " listed in " + ALLOWED_HOSTS_PROPERTY + ".");
            }
            case CROSS_SITE -> refuse(context, 403, "Cross-site request refused: this EDDI instance runs without"
                    + " authentication and accepts state-changing requests only from its own origin and the origins in"
                    + " quarkus.http.cors.origins.");
        }
    }

    private void logRefusedHost(String host) {
        HostPort parsed = host == null ? null : parseHost(host);
        String key = parsed == null ? "<malformed>" : format(parsed);
        if (loggedHosts.size() < MAX_LOGGED_HOSTS && loggedHosts.add(key)) {
            LOGGER.warnf("[SECURITY] Refused a request addressed to Host %s (421): authentication is off, so only localhost,"
                    + " IP addresses, container-host names and %s are answered. Add the host there if it is a legitimate name for this"
                    + " instance. (Logged once per host.)", key, ALLOWED_HOSTS_PROPERTY);
        }
    }

    private static void refuse(RoutingContext context, int status, String message) {
        context.response()
                .setStatusCode(status)
                .putHeader(HttpHeaders.CONTENT_TYPE, "text/plain; charset=utf-8")
                .putHeader("Cache-Control", "no-store")
                .end(message);
    }

    /**
     * The whole policy as a pure function, so every branch is testable without a
     * server.
     */
    static Decision decide(Policy policy, String method, String path, String host, String origin, String secFetchSite) {
        if (isHealthPath(path)) {
            return Decision.ALLOW;
        }
        HostPort requestHost = null;
        if (host != null) {
            requestHost = parseHost(host);
            if (requestHost == null) {
                return Decision.MISDIRECTED_HOST;
            }
            if (!policy.anyHost() && !hostAllowed(policy, requestHost)) {
                return Decision.MISDIRECTED_HOST;
            }
        }
        // No Host at all: HTTP/1.0 without one. Browsers always send it, so this
        // is not the rebinding vector; the Origin check below still applies.
        if (method != null && SAFE_METHODS.contains(method.toUpperCase(Locale.ROOT))) {
            return Decision.ALLOW;
        }
        if (origin != null) {
            return originAllowed(policy, origin, requestHost) ? Decision.ALLOW : Decision.CROSS_SITE;
        }
        if (secFetchSite != null) {
            String site = secFetchSite.trim().toLowerCase(Locale.ROOT);
            if (site.equals("cross-site") || site.equals("same-site")) {
                return Decision.CROSS_SITE;
            }
        }
        return Decision.ALLOW;
    }

    static boolean isHealthPath(String path) {
        return path != null && (path.equals("/q/health") || path.startsWith("/q/health/"));
    }

    static boolean hostAllowed(Policy policy, HostPort host) {
        if (host.ipLiteral() || host.host().equals("localhost") || host.host().endsWith(".localhost")
                || CONTAINER_HOST_NAMES.contains(host.host())) {
            return true;
        }
        for (HostPort allowed : policy.allowedHosts()) {
            if (allowed.host().equals(host.host()) && (allowed.port() == -1 || allowed.port() == host.port())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a browser {@code Origin} may make a state-changing request: the
     * request's own origin (scheme ignored — behind a TLS-terminating proxy the
     * request is http while the page is https), or a configured CORS origin.
     */
    static boolean originAllowed(Policy policy, String origin, HostPort requestHost) {
        String trimmed = origin.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("null")) {
            return false;
        }
        if (policy.anyOrigin()) {
            return true;
        }
        for (Pattern pattern : policy.originPatterns()) {
            if (pattern.matcher(trimmed).matches()) {
                return true;
            }
        }
        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            return false;
        }
        // A browser's Origin is scheme://host[:port] and nothing else. Anything more
        // is not one a browser sent, and must not be normalized into a match.
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getRawPath() != null && !uri.getRawPath().isEmpty())) {
            return false;
        }
        String normalized = OriginMatcher.normalize(uri);
        if (normalized == null) {
            return false;
        }
        if (policy.allowedOrigins().contains(normalized)) {
            return true;
        }
        if (requestHost == null || uri.getHost() == null) {
            return false;
        }
        HostPort originHost = parseHost(uri.getRawAuthority());
        if (originHost == null) {
            return false;
        }
        int originPort = originHost.port() != -1 ? originHost.port() : defaultPort(uri.getScheme());
        int requestPort = requestHost.port() != -1 ? requestHost.port() : defaultPort(uri.getScheme());
        return originHost.host().equals(requestHost.host()) && originPort == requestPort;
    }

    private static String normalizeOrigin(String origin) {
        try {
            return OriginMatcher.normalize(new URI(origin));
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    /**
     * Parses a {@code Host} header or an allow-list entry. Returns {@code null} for
     * anything that is not a syntactically valid host name or IP literal with an
     * optional port — a malformed Host is refused, never guessed at.
     */
    static HostPort parseHost(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.isEmpty() || value.indexOf('@') >= 0 || value.indexOf('/') >= 0) {
            return null;
        }
        String host;
        String portPart = null;
        if (value.startsWith("[")) {
            int close = value.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = value.substring(1, close);
            String rest = value.substring(close + 1);
            if (!rest.isEmpty()) {
                if (!rest.startsWith(":")) {
                    return null;
                }
                portPart = rest.substring(1);
            }
            if (host.isEmpty() || host.indexOf(':') < 0 || !IPV6.matcher(host).matches()) {
                return null;
            }
            int port = parsePort(portPart);
            return port == -2 ? null : new HostPort(host, port, true);
        }
        int colon = value.lastIndexOf(':');
        if (colon >= 0) {
            if (value.indexOf(':') != colon) {
                return null; // an unbracketed IPv6 literal is not a valid Host
            }
            host = value.substring(0, colon);
            portPart = value.substring(colon + 1);
        } else {
            host = value;
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1); // the FQDN form names the same host
        }
        if (host.isEmpty() || !HOST_NAME.matcher(host).matches()) {
            return null;
        }
        int port = parsePort(portPart);
        if (port == -2) {
            return null;
        }
        return new HostPort(host, port, IPV4.matcher(host).matches());
    }

    /** {@code -1} for no port, {@code -2} for an invalid one. */
    private static int parsePort(String portPart) {
        if (portPart == null) {
            return -1;
        }
        if (portPart.isEmpty() || portPart.length() > 5 || !portPart.chars().allMatch(Character::isDigit)) {
            return -2;
        }
        int port = Integer.parseInt(portPart);
        return port >= 1 && port <= 65535 ? port : -2;
    }

    static String format(HostPort host) {
        String name = host.ipLiteral() && host.host().indexOf(':') >= 0 ? "[" + host.host() + "]" : host.host();
        return host.port() == -1 ? name : name + ":" + host.port();
    }

    /** Keeps a configuration value printable on one log line. */
    private static final class LogSafe {
        private LogSafe() {
        }

        static String of(String value) {
            String cleaned = value.replaceAll("[\\p{Cntrl}]", "?");
            return cleaned.length() > 200 ? cleaned.substring(0, 200) + "…" : cleaned;
        }
    }
}
