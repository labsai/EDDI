/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import ai.labs.eddi.engine.httpclient.outbound.OutboundSettings.ProfileSettings;
import ai.labs.eddi.engine.httpclient.outbound.OutboundSettings.ProxySettings;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.PoolOptions;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.net.ProxyOptions;
import io.vertx.core.net.ProxyType;
import io.vertx.core.net.SocketAddress;
import io.vertx.core.tracing.TracingPolicy;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * The Vert.x engine, on the core {@link HttpClient} of the Quarkus-managed
 * {@link Vertx} — one client per profile (two for {@code auto}: TLS and
 * cleartext), created on first use.
 * <p>
 * What it has that the JDK engine has not:
 * <ul>
 * <li><b>A pre-send peer check.</b> Once the connection is assigned — pooled or
 * fresh — and before a byte of the request is written, the hop's
 * {@link OutboundEngine.PeerCheck} judges the address the connection actually
 * reached, so a name that rebinds between {@link OutboundHttp}'s check and the
 * connect is refused before the request goes out, not after its response
 * arrived. The connection is reset, never reused.</li>
 * <li><b>A per-host pool limit</b>
 * ({@code eddi.http.outbound.<profile>.max-connections-per-host}). A request
 * beyond it waits for a connection, inside the hop's deadline.</li>
 * </ul>
 * Like every engine it enforces no policy: it follows no redirect, decompresses
 * nothing (a caller that wants compression sets {@code Accept-Encoding} and
 * decodes; {@link BodyMode} caps then count the bytes on the wire), and leaves
 * trace headers to the facade ({@link TracingPolicy#IGNORE}). The request
 * target is built from the already-validated {@link URI}'s parts — host, port,
 * scheme, raw path and query — and never from the URL string, so Vert.x's
 * {@code java.net.URL} parse cannot reach a different host than the guard saw.
 * <p>
 * Callers block, as on the JDK engine; the event loop only completes futures
 * and feeds the {@link VertxBodyStream}. Never call {@link #send} on an event
 * loop (the facade refuses to).
 */
final class VertxOutboundEngine implements OutboundEngine {

    private static final Logger LOGGER = Logger.getLogger(VertxOutboundEngine.class);

    /** Headers the client sets itself, as on the JDK engine. */
    private static final Set<String> RESTRICTED_HEADERS = Set.of("host", "content-length", "connection", "expect", "upgrade");

    /** Applies a named {@code quarkus.tls} configuration to client options. */
    @FunctionalInterface
    interface TlsOptions {
        TlsOptions NONE = (options, name) -> {
            throw new IllegalStateException("TLS configuration '" + name
                    + "' was requested, but no TLS registry is available in this context");
        };

        void configure(HttpClientOptions options, String configurationName);
    }

    private record ClientKey(Profile profile, boolean tls) {
    }

    private final Vertx vertx;
    private final OutboundSettings settings;
    private final TlsOptions tlsOptions;
    private final UnaryOperator<String> secretResolver;
    private final Map<ClientKey, HttpClient> clients = new ConcurrentHashMap<>();
    private volatile ProxyOptions proxyOptions;

    VertxOutboundEngine(Vertx vertx, OutboundSettings settings, TlsOptions tlsOptions, UnaryOperator<String> secretResolver) {
        this.vertx = vertx;
        this.settings = settings;
        this.tlsOptions = tlsOptions;
        this.secretResolver = secretResolver;
    }

    @Override
    public Received send(Hop hop) throws IOException, InterruptedException {
        long remaining = hop.deadlineNanos() - System.nanoTime();
        if (remaining <= 0) {
            throw deadlineExceeded(hop.uri());
        }
        URI uri = hop.uri();
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IOException("Unsupported URI scheme for outbound HTTP: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            // A registry-based authority (x_y.example) parses to no host in java.net.URI;
            // never fall back to the raw string, which java.net.URL reads differently.
            throw new IOException("The URI has no host the client can address: " + OutboundHttp.withoutQuery(uri));
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        boolean tls = "https".equals(scheme);
        int port = uri.getPort() != -1 ? uri.getPort() : tls ? 443 : 80;
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        String target = uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();

        RequestOptions options = new RequestOptions()
                .setMethod(HttpMethod.valueOf(hop.method()))
                .setHost(host)
                .setPort(port)
                .setSsl(tls)
                .setURI(target)
                .setFollowRedirects(false)
                // Bounds the wait for a pooled or new connection, so a request queued
                // behind the pool limit leaves the queue once its deadline passed.
                .setConnectTimeout(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
        hop.headers().forEach((name, values) -> {
            if (!RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : values) {
                    options.addHeader(name, value);
                }
            }
        });
        PeerCheck peerCheck = hop.peerCheck();
        ProxySettings proxy = settings.proxy();
        if (proxy.enabled() && !bypassesProxy(proxy, host)) {
            options.setProxyOptions(proxyOptions(proxy));
            // The connection's peer is the proxy, which resolves the target itself.
            peerCheck = null;
        }

        CompletableFuture<Received> result = new CompletableFuture<>();
        AtomicReference<HttpClientRequest> sent = new AtomicReference<>();
        Buffer body = hop.body().length == 0 ? null : Buffer.buffer(hop.body());
        PeerCheck check = peerCheck;
        client(hop.profile(), tls).request(options).onComplete(created -> {
            if (created.failed()) {
                result.completeExceptionally(created.cause());
                return;
            }
            HttpClientRequest request = created.result();
            sent.set(request);
            if (result.isDone()) {
                // The caller gave up while the connection was being assigned.
                request.reset();
                return;
            }
            if (check != null) {
                try {
                    check.check(peerAddress(request));
                } catch (RuntimeException refused) {
                    request.reset();
                    result.completeExceptionally(refused);
                    return;
                }
            }
            Future<HttpClientResponse> response = body == null ? request.send() : request.send(body);
            response.onComplete(arrived -> {
                if (arrived.failed()) {
                    result.completeExceptionally(arrived.cause());
                    return;
                }
                HttpClientResponse httpResponse = arrived.result();
                httpResponse.pause();
                VertxBodyStream stream = new VertxBodyStream(httpResponse, Vertx.currentContext(), request::reset);
                Received received = new Received(httpResponse.statusCode(), httpResponse.statusMessage(),
                        headers(httpResponse.headers()), stream);
                if (!result.complete(received)) {
                    stream.close();
                }
            });
        });

        long hopTimeout = hop.hopTimeout().toNanos();
        boolean deadlineBinds = remaining <= hopTimeout;
        try {
            return result.get(deadlineBinds ? remaining : hopTimeout, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            abandon(result, sent);
            if (deadlineBinds) {
                throw deadlineExceeded(uri);
            }
            // What the JDK client throws when its per-request timeout fires.
            throw new HttpTimeoutException("request timed out");
        } catch (InterruptedException e) {
            abandon(result, sent);
            throw e;
        } catch (ExecutionException e) {
            throw unwrap(e, uri, deadlineBinds);
        }
    }

    /**
     * Stops an exchange the caller no longer waits for: a request not yet created
     * is reset when it is ({@code result} is done), one in flight is reset now, and
     * a response that already arrived has its body closed.
     */
    private void abandon(CompletableFuture<Received> result, AtomicReference<HttpClientRequest> sent) {
        if (!result.cancel(false)) {
            result.thenAccept(received -> OutboundHttp.closeQuietly(received.body()));
            return;
        }
        HttpClientRequest request = sent.get();
        if (request != null) {
            vertx.runOnContext(ignored -> request.reset());
        }
    }

    /**
     * Rethrows a failure the way the JDK engine would: an {@link IOException} as
     * itself, an unchecked exception (an {@link OutboundBlockedException} from the
     * peer check) as itself, anything else — Vert.x reports a closed connection as
     * a {@code VertxException} — as an {@link IOException}; with the caller's
     * frames attached.
     * <p>
     * A {@link TimeoutException} is the wait for a pooled connection running out
     * ({@link RequestOptions#setConnectTimeout}, set to the time left): it is
     * reported as the timeout it is — {@link OutboundTimeoutException} when the
     * whole-exchange deadline was the binding limit, else the hop's
     * {@link HttpTimeoutException} — never as a generic I/O failure.
     *
     * @param deadlineBinds
     *            whether the exchange deadline, not the hop timeout, bounded this
     *            hop
     */
    static IOException unwrap(ExecutionException e, URI uri, boolean deadlineBinds) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        CallerFrames.attachTo(cause);
        if (cause instanceof TimeoutException) {
            HttpTimeoutException timeout = deadlineBinds ? deadlineExceeded(uri) : new HttpTimeoutException("request timed out");
            timeout.initCause(cause);
            return timeout;
        }
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof OutboundBlockedException blocked) {
            throw blocked;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IOException(cause.getMessage(), cause);
    }

    private static OutboundTimeoutException deadlineExceeded(URI uri) {
        return new OutboundTimeoutException("Total request timeout exceeded for: " + OutboundHttp.withoutQuery(uri));
    }

    /** The IP the request's connection reached, or {@code null} when unknown. */
    private static InetAddress peerAddress(HttpClientRequest request) {
        try {
            HttpConnection connection = request.connection();
            SocketAddress remote = connection != null ? connection.remoteAddress() : null;
            return remote != null ? ConnectedPeers.parse(remote.hostAddress()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Map<String, List<String>> headers(MultiMap headers) {
        TreeMap<String, List<String>> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map.Entry<String, String> entry : headers) {
            map.computeIfAbsent(entry.getKey(), name -> new ArrayList<>()).add(entry.getValue());
        }
        return OutboundRequest.caseInsensitiveCopy(map);
    }

    private static boolean bypassesProxy(ProxySettings proxy, String host) {
        return JdkOutboundEngine.NonProxyHostsSelector.matches(proxy.nonProxyHosts(), host);
    }

    private ProxyOptions proxyOptions(ProxySettings proxy) {
        ProxyOptions cached = proxyOptions;
        if (cached != null) {
            return cached;
        }
        ProxyOptions built = new ProxyOptions().setType(ProxyType.HTTP).setHost(proxy.host()).setPort(proxy.port());
        if (proxy.credentials() != null) {
            String credentials = secretResolver.apply(proxy.credentials());
            if (credentials.contains("${vault:")) {
                throw new IllegalStateException(
                        "eddi.http.outbound.proxy.credentials references a vault secret that could not be resolved");
            }
            int colon = credentials.indexOf(':');
            if (colon <= 0) {
                throw new IllegalStateException("eddi.http.outbound.proxy.credentials must have the form user:password");
            }
            // Unlike the JDK, Vert.x sends Basic proxy credentials on an HTTPS tunnel
            // (CONNECT) too; it sends them only to the proxy.
            built.setUsername(credentials.substring(0, colon)).setPassword(credentials.substring(colon + 1));
        }
        proxyOptions = built;
        return built;
    }

    HttpClient client(Profile profile, boolean tls) {
        return clients.computeIfAbsent(new ClientKey(profile, tls), this::build);
    }

    private HttpClient build(ClientKey key) {
        ProfileSettings profileSettings = settings.profile(key.profile());
        int maxPerHost = profileSettings.maxConnectionsPerHost() != null
                ? profileSettings.maxConnectionsPerHost()
                : OutboundSettings.DEFAULT_VERTX_MAX_CONNECTIONS_PER_HOST;
        HttpClientOptions options = new HttpClientOptions()
                .setConnectTimeout((int) Math.min(Integer.MAX_VALUE, profileSettings.connectTimeout().toMillis()))
                .setDecompressionSupported(false)
                .setTracingPolicy(TracingPolicy.IGNORE)
                .setName("eddi-outbound-" + key.profile().key() + (key.tls() ? "-tls" : ""));
        switch (profileSettings.protocol()) {
            case HTTP1 -> options.setProtocolVersion(HttpVersion.HTTP_1_1).setUseAlpn(false);
            case HTTP2 -> options.setProtocolVersion(HttpVersion.HTTP_2).setUseAlpn(true);
            // HTTP/2 where TLS negotiates it; plain HTTP/1.1 in cleartext, never an
            // h2c upgrade a server may mishandle.
            case AUTO -> {
                if (key.tls()) {
                    options.setProtocolVersion(HttpVersion.HTTP_2).setUseAlpn(true);
                } else {
                    options.setProtocolVersion(HttpVersion.HTTP_1_1).setUseAlpn(false);
                }
            }
        }
        if (profileSettings.tlsConfigurationName() != null) {
            tlsOptions.configure(options, profileSettings.tlsConfigurationName());
        }
        LOGGER.debugf("Outbound Vert.x client for profile %s (%s): max %d connections per host", key.profile().key(),
                key.tls() ? "tls" : "cleartext", maxPerHost);
        // HTTP/1 connections per host and port; HTTP/2 multiplexes on the default one.
        return vertx.createHttpClient(options, new PoolOptions().setHttp1MaxSize(maxPerHost));
    }

    @Override
    public void close() {
        clients.values().forEach(client -> client.close());
        clients.clear();
    }
}
