/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import ai.labs.eddi.engine.httpclient.outbound.OutboundSettings.ProfileSettings;
import ai.labs.eddi.engine.httpclient.outbound.OutboundSettings.ProxySettings;
import org.jboss.logging.Logger;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.UnaryOperator;

/**
 * The {@code java.net.http} engine: one {@link HttpClient} per profile, each
 * with its own connection pool, created on first use.
 * <p>
 * The clients never follow redirects ({@link OutboundHttp} does, hop by hop)
 * and hand every body back unread as a stream.
 */
final class JdkOutboundEngine implements OutboundEngine {

    private static final Logger LOGGER = Logger.getLogger(JdkOutboundEngine.class);

    /** Headers the JDK client sets itself and refuses to be given. */
    private static final Set<String> RESTRICTED_HEADERS = Set.of("host", "content-length", "connection", "expect", "upgrade");

    /** Resolves a named {@code quarkus.tls} configuration to an SSL context. */
    @FunctionalInterface
    interface TlsContexts {
        TlsContexts NONE = name -> {
            throw new IllegalStateException("TLS configuration '" + name
                    + "' was requested, but no TLS registry is available in this context");
        };

        SSLContext sslContext(String configurationName);
    }

    private final OutboundSettings settings;
    private final TlsContexts tlsContexts;
    private final UnaryOperator<String> secretResolver;
    private final Map<Profile, HttpClient> clients = new ConcurrentHashMap<>();

    /**
     * @param secretResolver
     *            resolves {@code ${vault:...}} references in the proxy credentials
     */
    JdkOutboundEngine(OutboundSettings settings, TlsContexts tlsContexts, UnaryOperator<String> secretResolver) {
        this.settings = settings;
        this.tlsContexts = tlsContexts;
        this.secretResolver = secretResolver;
    }

    @Override
    public Received send(Hop hop) throws IOException, InterruptedException {
        long remaining = hop.deadlineNanos() - System.nanoTime();
        if (remaining <= 0) {
            throw new OutboundTimeoutException("Total request timeout exceeded for: " + OutboundHttp.withoutQuery(hop.uri()));
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(hop.uri())
                .timeout(hop.hopTimeout())
                .method(hop.method(), hop.body().length == 0
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(hop.body()));
        hop.headers().forEach((name, values) -> {
            if (!RESTRICTED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                for (String value : values) {
                    builder.header(name, value);
                }
            }
        });

        CompletableFuture<HttpResponse<InputStream>> exchange = client(hop.profile())
                .sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        HttpResponse<InputStream> response;
        try {
            response = exchange.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            abandon(exchange);
            throw new OutboundTimeoutException("Total request timeout exceeded for: " + OutboundHttp.withoutQuery(hop.uri()));
        } catch (InterruptedException e) {
            abandon(exchange);
            throw e;
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
        return new Received(response.statusCode(), null, OutboundRequest.caseInsensitiveCopy(response.headers().map()),
                response.body());
    }

    /**
     * Cancels an exchange the caller no longer waits for. If the headers arrived in
     * the same instant, cancelling is too late and the body stream would hold its
     * connection open: close it.
     */
    private static void abandon(CompletableFuture<HttpResponse<InputStream>> exchange) {
        if (!exchange.cancel(true)) {
            exchange.thenAccept(response -> closeQuietly(response.body()));
        }
    }

    /**
     * Rethrows an async failure the way {@link HttpClient#send} would have thrown
     * it: the {@link IOException} itself (so a caller can still catch
     * {@link java.net.http.HttpTimeoutException} or
     * {@link java.net.ConnectException} by type), an unchecked exception or error
     * as is, anything else wrapped — with the caller's frames attached
     * ({@link CallerFrames}).
     */
    static IOException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof IOException || cause instanceof RuntimeException) {
            CallerFrames.attachTo(cause);
        }
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new IOException(cause != null ? cause.getMessage() : e.getMessage(), cause);
    }

    HttpClient client(Profile profile) {
        return clients.computeIfAbsent(profile, this::build);
    }

    private HttpClient build(Profile profile) {
        ProfileSettings profileSettings = settings.profile(profile);
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(profileSettings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        switch (profileSettings.protocol()) {
            case HTTP1 -> builder.version(HttpClient.Version.HTTP_1_1);
            case HTTP2 -> builder.version(HttpClient.Version.HTTP_2);
            case AUTO -> {
                // The JDK default: HTTP/2 via ALPN, falling back to HTTP/1.1.
            }
        }

        if (profileSettings.tlsConfigurationName() != null) {
            builder.sslContext(tlsContexts.sslContext(profileSettings.tlsConfigurationName()));
        }
        ProxySettings proxy = settings.proxy();
        if (proxy.enabled()) {
            builder.proxy(new NonProxyHostsSelector(new InetSocketAddress(proxy.host(), proxy.port()), proxy.nonProxyHosts()));
            if (proxy.credentials() != null) {
                builder.authenticator(proxyAuthenticator(secretResolver.apply(proxy.credentials())));
                // The JDK refuses Basic proxy credentials on an HTTPS tunnel (CONNECT)
                // unless the JVM was started with jdk.http.auth.tunneling.disabledSchemes
                // cleared; it is a JVM-wide switch, so EDDI does not flip it. Say so once,
                // instead of letting every HTTPS call fail with a 407 that names nothing.
                LOGGER.warnf("Outbound proxy credentials are configured. They are sent for plain-HTTP targets; for HTTPS "
                        + "targets the JDK withholds Basic proxy credentials unless the JVM runs with "
                        + "-Djdk.http.auth.tunneling.disabledSchemes= (empty). Profile: %s", profile.key());
            }
        }
        return builder.build();
    }

    /**
     * Answers proxy challenges only — never a target server's, so the proxy
     * password cannot leak to a host that asks for credentials.
     */
    private static Authenticator proxyAuthenticator(String credentials) {
        if (credentials.contains("${vault:")) {
            throw new IllegalStateException(
                    "eddi.http.outbound.proxy.credentials references a vault secret that could not be resolved");
        }
        int colon = credentials.indexOf(':');
        if (colon <= 0) {
            throw new IllegalStateException("eddi.http.outbound.proxy.credentials must have the form user:password");
        }
        String user = credentials.substring(0, colon);
        char[] password = credentials.substring(colon + 1).toCharArray();
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() != RequestorType.PROXY) {
                    return null;
                }
                return new PasswordAuthentication(user, password.clone());
            }
        };
    }

    private static void closeQuietly(InputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // Abandoned anyway.
        }
    }

    /**
     * Routes every request through one proxy except those to a host matching a
     * pattern — exact, or with {@code *} at the start or end, case-insensitive, as
     * in the JDK's {@code http.nonProxyHosts}.
     */
    static final class NonProxyHostsSelector extends ProxySelector {
        private final List<Proxy> viaProxy;
        private final List<String> patterns;

        NonProxyHostsSelector(SocketAddress proxy, List<String> patterns) {
            this.viaProxy = List.of(new Proxy(Proxy.Type.HTTP, proxy));
            this.patterns = patterns.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri.getHost();
            if (host != null && bypasses(host.toLowerCase(Locale.ROOT))) {
                return List.of(Proxy.NO_PROXY);
            }
            return viaProxy;
        }

        boolean bypasses(String host) {
            for (String pattern : patterns) {
                if (pattern.startsWith("*") && host.endsWith(pattern.substring(1))) {
                    return true;
                }
                if (pattern.endsWith("*") && host.startsWith(pattern.substring(0, pattern.length() - 1))) {
                    return true;
                }
                if (pattern.equals(host)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException e) {
            // A single proxy: nothing to fall back to.
        }
    }
}
