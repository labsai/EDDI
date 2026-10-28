/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.bootstrap;

import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.impl.VertxHttpClient;
import ai.labs.eddi.engine.tracing.TracePropagation;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.client.impl.ClientPhase;
import io.vertx.ext.web.client.impl.HttpContext;
import io.vertx.ext.web.client.impl.WebClientInternal;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

@ApplicationScoped
public class HttpClientModule {

    @Inject
    Vertx vertx;

    /**
     * The CDI producer for the one and only {@link VertxHttpClient} — i.e. for
     * every outbound httpcalls request in every agent.
     * <p>
     * {@code @Produces} here MUST be {@code jakarta.enterprise.inject.Produces}.
     * This declaration carried the JAX-RS media-type annotation of the same simple
     * name instead, and the bean existed only because ArC's
     * {@code quarkus.arc.auto-producer-methods} default promotes any method with a
     * scope annotation to a producer. Turning that default off, or moving
     * {@code @ApplicationScoped} off this method, would have made
     * {@code VertxHttpClient} unsatisfied with nothing pointing at the wrong import
     * as the cause. The paired {@code @Disposes} below was always the real CDI
     * annotation, so the two halves disagreed.
     */
    @Produces
    @ApplicationScoped
    public VertxHttpClient provideHttpClient(@ConfigProperty(name = "httpClient.maxConnectionPerRoute") Integer maxConnectionPerRoute,
                                             @ConfigProperty(name = "httpClient.maxRedirects") Integer maxRedirects,
                                             @ConfigProperty(name = "httpClient.idleTimeoutInMillis") Integer idleTimeout,
                                             @ConfigProperty(name = "httpClient.connectTimeoutInMillis") Integer connectTimeout,
                                             @ConfigProperty(name = OutboundTargetPolicy.ALLOWED_HOSTS_KEY) Optional<String> allowedHosts) {

        WebClientOptions options = clientOptions(maxConnectionPerRoute, maxRedirects, idleTimeout, connectTimeout);

        // What WebClient.create(vertx, options) does, with the HttpClient kept in hand.
        //
        // Deliberately a plain WebClient and NOT a WebClientSession. A session keeps
        // a single, application-scoped cookie store, and this client is the one and
        // only client behind every agent's httpcalls — so a Set-Cookie returned to
        // one user's call was stored and then replayed on the next user's call to the
        // same host, leaking a session credential across users. A plain WebClient
        // holds no cookie jar, so nothing is carried between calls. httpcalls already
        // strips Set-Cookie from what it records (ApiCallExecutor); this removes the
        // live jar that was the actual replay vector. If a future feature needs
        // cookies, scope a WebClientSession per conversation/principal rather than
        // sharing one here.
        Set<String> allowList = OutboundTargetPolicy.parseAllowedHosts(allowedHosts.orElse(""));
        WebClient webClient = WebClient.wrap(guardedHttpClient(vertx, options, allowList), options);
        ((WebClientInternal) webClient).addInterceptor(refusingConnectedPeers(ssrfProtectionEnabled, allowList));

        return new VertxHttpClient(vertx, webClient, webClient);
    }

    /**
     * Whether {@value OutboundTargetPolicy#CONFIG_KEY} is on. Field-injected so the
     * tests that build this module by hand get the shipped default.
     */
    @ConfigProperty(name = OutboundTargetPolicy.CONFIG_KEY, defaultValue = "false")
    boolean ssrfProtectionEnabled;

    /**
     * A web-client interceptor that checks the address each request is <em>actually
     * connected to</em>, before a byte of the request is written (DNS rebinding).
     * <p>
     * {@code ApiCallExecutor} validates the target by resolving its host through
     * {@link InetAddress}; the client then resolves the host again when it opens a
     * connection. Both lookups normally hit the JVM's positive DNS cache
     * ({@code quarkus.vertx.use-async-dns} is off, so Vert.x resolves through the
     * JDK), but a cache entry expiring between the two lets a rebinding name pass
     * the check as a public address and connect to a private one. In the
     * {@code SEND_REQUEST} phase the connection exists — pooled or fresh, first
     * request or redirect hop — and nothing has been sent yet, so its remote
     * address is the one fact the check can no longer be raced on:
     * <ul>
     * <li>a cloud-metadata or link-local peer is refused always, allow-list or not,
     * as {@link UrlValidationUtils#rejectCloudMetadataTarget} refuses it by
     * name;</li>
     * <li>with protection on, a loopback, private or otherwise internal peer is
     * refused unless the request's host is on
     * {@value OutboundTargetPolicy#ALLOWED_HOSTS_KEY} — the rule
     * {@code ApiCallExecutor} applies to the URL.</li>
     * </ul>
     * A request whose peer cannot be read is refused with protection on (it cannot
     * be vouched for) and let through with it off.
     */
    static Handler<HttpContext<?>> refusingConnectedPeers(boolean ssrfProtectionEnabled, Set<String> allowedHosts) {
        return context -> {
            if (context.phase() == ClientPhase.SEND_REQUEST) {
                HttpClientRequest request = context.clientRequest();
                String refusal = connectedPeerRefusal(connectedAddress(request), request != null ? request.getHost() : null,
                        request != null ? request.getPort() : -1, request != null && isHttps(request), ssrfProtectionEnabled,
                        allowedHosts);
                if (refusal != null) {
                    context.fail(new IllegalArgumentException(refusal));
                    return;
                }
            }
            context.next();
        };
    }

    private static boolean isHttps(HttpClientRequest request) {
        String uri = request.absoluteURI();
        return uri != null && uri.toLowerCase(Locale.ROOT).startsWith("https");
    }

    /**
     * Why a request to {@code host} that connected to {@code peerAddress} must not
     * be sent, or {@code null} when it may.
     *
     * @param peerAddress
     *            the IP literal the connection's remote end has, or {@code null}
     *            when unknown
     */
    static String connectedPeerRefusal(String peerAddress, String host, int port, boolean https, boolean ssrfProtectionEnabled,
                                       Set<String> allowedHosts) {
        if (peerAddress == null || peerAddress.isBlank()) {
            return ssrfProtectionEnabled ? "Request refused: the address the connection reached cannot be read, so it cannot be checked" : null;
        }
        String bare = peerAddress.startsWith("[") && peerAddress.endsWith("]") ? peerAddress.substring(1, peerAddress.length() - 1) : peerAddress;
        // An IPv6 link-local peer carries its zone ("fe80::1%eth0"); the zone says
        // nothing about where the address sits, and would only stop the parse.
        int zone = bare.indexOf('%');
        if (zone > 0) {
            bare = bare.substring(0, zone);
        }
        InetAddress peer;
        try {
            peer = InetAddress.ofLiteral(bare);
        } catch (IllegalArgumentException e) {
            return ssrfProtectionEnabled ? "Request refused: the connection's remote address is not an IP address: " + bare : null;
        }
        if (UrlValidationUtils.isMetadataAddress(peer)) {
            return "Request refused: " + host + " connected to " + bare + ", a cloud instance-metadata or link-local address";
        }
        if (!ssrfProtectionEnabled || isAllowListed(host, port, https, allowedHosts)) {
            return null;
        }
        if (UrlValidationUtils.isPrivateAddress(peer)) {
            return "Request refused: " + host + " connected to " + bare + ", a private/internal address, which "
                    + OutboundTargetPolicy.CONFIG_KEY + "=true refuses (the name resolved differently when it was validated)";
        }
        return null;
    }

    /**
     * The options of the one httpcalls client, from the {@code httpClient.*}
     * settings. Package-private so the tracing policy can be pinned by a test.
     */
    static WebClientOptions clientOptions(int maxConnectionPerRoute, int maxRedirects, int idleTimeout, int connectTimeout) {
        WebClientOptions options = new WebClientOptions();

        // Mapping configuration
        options.setMaxPoolSize(maxConnectionPerRoute);

        options.setMaxRedirects(maxRedirects);

        int idleTimeoutSeconds;
        if (idleTimeout == 0) {
            idleTimeoutSeconds = 0;
        } else {
            idleTimeoutSeconds = (int) Math.ceil(idleTimeout / 1000.0);
        }
        options.setIdleTimeout(idleTimeoutSeconds);

        options.setConnectTimeout(connectTimeout);
        options.setFollowRedirects(true);
        options.setDecompressionSupported(true);
        // Quarkus' Vert.x client tracer stays out of this client. With the default
        // PROPAGATE policy it would open a client span per request and write a fresh
        // traceparent onto EVERY hop the redirect handler issues — after
        // stripCredentialsIfCrossOrigin had removed the trace context from a
        // cross-origin hop, so a redirect to another origin would carry this
        // deployment's trace id anyway. HttpClientWrapper injects the current span's
        // traceparent explicitly on the first request, and the redirect handler
        // copies it to same-origin hops only, so propagation is unchanged; what is
        // given up is the auto-instrumented HTTP client span for httpcalls, whose
        // parent (execute_tool, or the pipeline task for a rule-triggered call)
        // already times the call.
        options.setTracingPolicy(TracingPolicy.IGNORE);
        return options;
    }

    /**
     * The HTTP client behind the web client, with {@link #refusingMetadataHops}
     * installed: the web client follows redirects through this client's redirect
     * handler.
     */
    static HttpClient guardedHttpClient(Vertx vertx, HttpClientOptions options) {
        return guardedHttpClient(vertx, options, Set.of());
    }

    /**
     * As above, with {@code eddi.security.ssrf-protection.allowed-hosts} for the
     * private-hop check.
     */
    static HttpClient guardedHttpClient(Vertx vertx, HttpClientOptions options, Set<String> allowedHosts) {
        HttpClient httpClient = vertx.createHttpClient(options);
        // Three guards wrap the default redirect handler: it must not land on the
        // cloud metadata service, it must not step from a public address into a
        // private one, and it must not replay a credential header to another origin.
        // Order does not matter — two veto the destination, the third scrubs the
        // hop's headers.
        httpClient.redirectHandler(strippingCrossOriginCredentials(
                refusingPrivateHops(vertx, refusingMetadataHops(vertx, httpClient.redirectHandler()), allowedHosts, InetAddress::getAllByName)));
        return httpClient;
    }

    /** Where an address sits, for the private-hop check. */
    enum AddressClass {
        PUBLIC, PRIVATE, LOOPBACK
    }

    /** Resolves a host to its addresses; injectable so tests need no DNS. */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolveAll(String host) throws UnknownHostException;
    }

    /**
     * Wraps the redirect handler so that no hop steps from one address class into a
     * more private one (security review M9.3).
     * <p>
     * With {@code eddi.security.ssrf-protection.enabled} on, httpcalls do not
     * follow redirects at all. With it off (the shipped default) they do, and the
     * metadata veto alone let a {@code 302} from any reachable public server — a
     * third-party open redirect, a host serving user content — send a GET to
     * {@code 127.0.0.1:7070} or an internal service, with the body returned into
     * conversation memory. This is the per-hop private-address check the outbound
     * facade's {@code HOPS_ONLY} policy performs, with one concession to the
     * protection-off contract: a hop into a private or loopback address is allowed
     * when the request that answered with the redirect was itself in that same
     * class, so an internal API redirecting within its own network keeps working.
     * Comparing with the previous hop rather than the first is the stricter
     * reading: a chain only stays private if every step was. A host on
     * {@code eddi.security.ssrf-protection.allowed-hosts} is always allowed.
     * <p>
     * A {@code Location} without a host is refused: the client would otherwise fill
     * in its default host.
     */
    static Function<HttpClientResponse, Future<RequestOptions>> refusingPrivateHops(Vertx vertx,
                                                                                    Function<HttpClientResponse, Future<RequestOptions>> delegate,
                                                                                    Set<String> allowedHosts, HostResolver resolver) {
        return response -> {
            Future<RequestOptions> next = delegate.apply(response);
            if (next == null) {
                return null;
            }
            HttpClientRequest previous = response != null ? response.request() : null;
            String previousAddress = connectedAddress(previous);
            String previousHost = previous != null ? previous.getHost() : null;
            return next.compose(options -> {
                if (options == null) {
                    return Future.succeededFuture();
                }
                return vertx.executeBlocking(() -> requireHopWithinAddressClass(previousAddress, previousHost, options, allowedHosts, resolver),
                        false);
            });
        };
    }

    /**
     * The IP address the request actually connected to, or {@code null} when it
     * cannot be read (no connection yet, a domain socket, a mock).
     */
    private static String connectedAddress(HttpClientRequest request) {
        try {
            HttpConnection connection = request != null ? request.connection() : null;
            SocketAddress remote = connection != null ? connection.remoteAddress() : null;
            return remote != null ? remote.hostAddress() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Returns {@code next} unchanged, or throws when it is host-less or steps into
     * a private or loopback address from a request that was not in that class.
     *
     * @param previousAddress
     *            the IP the redirecting request connected to, if known
     * @param previousHost
     *            that request's host, resolved when the address is unknown
     * @throws IllegalArgumentException
     *             naming the reason
     */
    static RequestOptions requireHopWithinAddressClass(String previousAddress, String previousHost, RequestOptions next,
                                                       Set<String> allowedHosts, HostResolver resolver) {
        String host = next.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Redirect refused: the Location header names no host");
        }
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (isAllowListed(bare, next, allowedHosts)) {
            return next;
        }
        AddressClass hopClass = classify(bare, resolver);
        if (hopClass == null) {
            throw new IllegalArgumentException("Redirect refused: the target host does not resolve: " + bare);
        }
        if (hopClass == AddressClass.PUBLIC) {
            return next;
        }
        AddressClass previousClass = previousAddress != null
                ? classify(previousAddress, resolver)
                : previousHost != null ? classify(previousHost, resolver) : null;
        if (previousClass == hopClass) {
            return next;
        }
        throw new IllegalArgumentException("Redirect refused: a hop from a " + describe(previousClass) + " address to a "
                + hopClass.name().toLowerCase(Locale.ROOT) + " address (" + bare + ") is not followed; list the host in "
                + OutboundTargetPolicy.ALLOWED_HOSTS_KEY + " if it is meant to be reachable");
    }

    private static String describe(AddressClass addressClass) {
        return addressClass == null ? "unknown" : addressClass.name().toLowerCase(Locale.ROOT);
    }

    private static boolean isAllowListed(String bareHost, RequestOptions next, Set<String> allowedHosts) {
        Integer port = next.getPort();
        return isAllowListed(bareHost, port != null ? port : -1, Boolean.TRUE.equals(next.isSsl()), allowedHosts);
    }

    /**
     * Whether {@code host} — bare or in IPv6 brackets — or {@code host:port} (a
     * missing port meaning the scheme's default) is on the allow-list.
     */
    static boolean isAllowListed(String host, int port, boolean https, Set<String> allowedHosts) {
        if (host == null || allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        String lower = host.toLowerCase(Locale.ROOT);
        String bare = lower.startsWith("[") && lower.endsWith("]") ? lower.substring(1, lower.length() - 1) : lower;
        int effective = port > 0 ? port : https ? 443 : 80;
        return allowedHosts.contains(bare) || allowedHosts.contains(bare + ":" + effective)
                || allowedHosts.contains("[" + bare + "]:" + effective);
    }

    /**
     * Loopback if any resolved address is loopback, private if any other is private
     * ({@link UrlValidationUtils#isPrivateAddress}), else public; {@code null} when
     * the host does not resolve.
     */
    static AddressClass classify(String host, HostResolver resolver) {
        InetAddress[] addresses;
        try {
            addresses = resolver.resolveAll(host);
        } catch (UnknownHostException e) {
            return null;
        }
        if (addresses == null || addresses.length == 0) {
            return null;
        }
        AddressClass result = AddressClass.PUBLIC;
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress()) {
                return AddressClass.LOOPBACK;
            }
            if (UrlValidationUtils.isPrivateAddress(address)) {
                result = AddressClass.PRIVATE;
            }
        }
        return result;
    }

    /**
     * Wraps the redirect handler so that credential headers are stripped from any
     * cross-origin hop.
     * <p>
     * Vert.x's default redirect handler copies <em>every</em> request header onto
     * the next hop and removes only {@code Content-Length} — it does not strip
     * {@code Authorization}, {@code Cookie} or anything else. So with
     * {@code eddi.security.ssrf-protection.enabled} off (the shipped default, where
     * redirects are followed), a public URL answering {@code 302 Location:} to
     * another host replayed whatever credential the httpcall carried — a static
     * {@code Bearer}/{@code X-Api-Key}/{@code Cookie} in the config just as much as
     * a resolved
     * {@code ${vault:…}}/{@code ${connection:…}}/{@code ${caller:token}}. This
     * scrubs {@link OutboundHttp#SENSITIVE_HEADERS} on every hop whose origin
     * differs from the request that produced it, so the fix does not depend on
     * knowing where the credential came from.
     */
    static Function<HttpClientResponse, Future<RequestOptions>> strippingCrossOriginCredentials(
                                                                                                Function<HttpClientResponse, Future<RequestOptions>> delegate) {
        return response -> {
            Future<RequestOptions> next = delegate.apply(response);
            if (next == null) {
                return null;
            }
            return next.map(options -> {
                if (options != null) {
                    stripCredentialsIfCrossOrigin(response.request(), options);
                }
                return options;
            });
        };
    }

    /**
     * Removes every {@link OutboundHttp#SENSITIVE_HEADERS} entry (and the
     * {@link TracePropagation#HEADERS trace context}) from {@code next} when it
     * targets a different origin (scheme, host or effective port) than
     * {@code original}. Same-origin hops keep their headers, so an in-service
     * redirect still authenticates.
     */
    static void stripCredentialsIfCrossOrigin(HttpClientRequest original, RequestOptions next) {
        if (original == null || !sameOrigin(original, next)) {
            // Cannot prove same-origin (null original), so fail safe and strip.
            OutboundHttp.SENSITIVE_HEADERS.forEach(next::removeHeader);
            // The W3C trace context goes too, as in OutboundHttp: the caller vouched
            // for one origin, and the trace id names this deployment's trace.
            TracePropagation.HEADERS.forEach(next::removeHeader);
        }
    }

    private static boolean sameOrigin(HttpClientRequest original, RequestOptions next) {
        String originalUri = original.absoluteURI();
        boolean originalHttps = originalUri != null && originalUri.toLowerCase(Locale.ROOT).startsWith("https");
        boolean nextHttps = next.isSsl() != null ? next.isSsl() : originalHttps;
        if (originalHttps != nextHttps) {
            return false;
        }
        String originalHost = original.getHost();
        String nextHost = next.getHost();
        if (originalHost == null || nextHost == null || !originalHost.equalsIgnoreCase(nextHost)) {
            return false;
        }
        int originalPort = effectivePort(original.getPort(), originalHttps);
        Integer nextPortValue = next.getPort();
        int nextPort = effectivePort(nextPortValue != null ? nextPortValue : -1, nextHttps);
        return originalPort == nextPort;
    }

    private static int effectivePort(int port, boolean https) {
        if (port > 0) {
            return port;
        }
        return https ? 443 : 80;
    }

    /**
     * Wraps the client's redirect handler so that no hop lands on the cloud
     * instance-metadata service.
     * <p>
     * {@code ApiCallExecutor} refuses a metadata target before sending, whatever
     * {@code eddi.security.ssrf-protection.enabled} says. With protection off this
     * client still follows redirects, though, so a public URL answering
     * {@code 302 Location: http://169.254.169.254/…} reached it anyway. The veto
     * sits on the client rather than on the call so that every hop of every request
     * is covered. The wrapped handler still decides whether and where to follow;
     * this only refuses the destination. The check may resolve a hostname, which
     * blocks, so it runs on a worker thread and never on the event loop.
     */
    static Function<HttpClientResponse, Future<RequestOptions>> refusingMetadataHops(Vertx vertx,
                                                                                     Function<HttpClientResponse, Future<RequestOptions>> delegate) {
        return response -> {
            Future<RequestOptions> next = delegate.apply(response);
            if (next == null) {
                return null;
            }
            return next.compose(options -> {
                if (options == null) {
                    return Future.succeededFuture();
                }
                return vertx.executeBlocking(() -> requireNotMetadataHop(options), false);
            });
        };
    }

    /**
     * Returns the redirect target unchanged, or throws when its host is a metadata
     * or link-local address.
     *
     * A hop without a host is refused rather than waved through: that is not a
     * destination this check can see, so it is not one it can vouch for. A host
     * {@code java.net.URI} cannot read (an underscore, percent-encoding, a trailing
     * dot after an IP literal) is refused by
     * {@link UrlValidationUtils#rejectCloudMetadataTarget} for the same reason —
     * the client would still resolve and connect to it (security review 2026-10-17,
     * H2).
     *
     * @throws IllegalArgumentException
     *             when the hop targets the cloud instance-metadata service, or its
     *             host cannot be read
     */
    static RequestOptions requireNotMetadataHop(RequestOptions next) {
        String host = next.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Redirect refused: the hop names no host, so it cannot be checked against the cloud "
                    + "instance-metadata block.");
        }
        boolean bareIpv6 = host.indexOf(':') >= 0 && !host.startsWith("[");
        UrlValidationUtils.rejectCloudMetadataTarget("http://" + (bareIpv6 ? "[" + host + "]" : host) + "/");
        return next;
    }

    public void close(@Disposes VertxHttpClient client) {
        if (client.getWebClient() != null) {
            client.getWebClient().close();
        }
        // The web client and the underlying client are the same instance now (no
        // session wrapper), so close it once.
        if (client.getUnderlyingClient() != null && client.getUnderlyingClient() != client.getWebClient()) {
            client.getUnderlyingClient().close();
        }
    }
}
