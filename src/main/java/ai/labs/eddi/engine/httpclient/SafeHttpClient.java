/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Centralized, SSRF-safe HTTP client wrapper. All outbound HTTP requests from
 * LLM tools and integrations should go through this client.
 * <p>
 * Security properties:
 * <ul>
 * <li>Redirects are NEVER followed automatically — manual per-hop
 * validation</li>
 * <li>Each redirect hop is validated against SSRF rules (private IPs, cloud
 * metadata, etc.)</li>
 * <li>Maximum redirect count is capped</li>
 * <li>Connect timeout is enforced per-hop</li>
 * <li>Overall wall-clock timeout is enforced across all hops</li>
 * <li>On cross-origin redirects, Authorization/Cookie headers are stripped</li>
 * </ul>
 *
 * @since 6.0.2
 */
@ApplicationScoped
public class SafeHttpClient {

    private static final Logger LOGGER = Logger.getLogger(SafeHttpClient.class);

    /** Maximum number of redirect hops per request. */
    private static final int MAX_REDIRECTS = 5;

    /** HTTP status codes considered redirects. */
    private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 303, 307, 308);

    /** Headers managed by HttpClient — must not be copied to redirect requests. */
    private static final Set<String> MANAGED_HEADERS = Set.of("host", "content-length", "connection");

    /** Security-sensitive headers stripped on cross-origin redirects. */
    private static final Set<String> SENSITIVE_HEADERS = Set.of("authorization", "cookie", "proxy-authorization");

    private final HttpClient httpClient;
    private final long connectTimeoutMs;

    public SafeHttpClient(
            @ConfigProperty(name = "httpClient.connectTimeoutInMillis", defaultValue = "10000") int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /**
     * Sends an HTTP request with SSRF-safe redirect handling. The initial URL is
     * NOT validated — callers that fetch user-controlled URLs must call
     * {@link UrlValidationUtils#validateUrl(String)} before building the request.
     * <p>
     * For requests to known-safe internal APIs (e.g., weather, search), skipping
     * initial validation is acceptable since the URL is constructed from config,
     * not user input.
     *
     * @param request
     *            the HTTP request to send
     * @param bodyHandler
     *            the response body handler
     * @return the HTTP response
     * @throws IOException
     *             if the request fails or too many redirects
     * @throws InterruptedException
     *             if the thread is interrupted
     */
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        return sendWithRedirects(request, bodyHandler, 0, Instant.now());
    }

    /**
     * Sends an HTTP request with SSRF validation on the initial URL. Use this for
     * user-controlled URLs (LLM tools, web scraping, etc.).
     *
     * @param request
     *            the HTTP request to send
     * @param bodyHandler
     *            the response body handler
     * @return the HTTP response
     * @throws IOException
     *             if the request fails
     * @throws IllegalArgumentException
     *             if the URL is unsafe
     */
    public <T> HttpResponse<T> sendValidated(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        UrlValidationUtils.validateUrl(request.uri().toString());
        return sendWithRedirects(request, bodyHandler, 0, Instant.now());
    }

    private <T> HttpResponse<T> sendWithRedirects(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler,
                                                  int redirectCount, Instant startTime)
            throws IOException, InterruptedException {

        // Check overall wall-clock timeout (3x connect timeout, e.g. 30s default).
        // This prevents an attacker from chaining slow-resolving redirects to hold
        // connections open indefinitely.
        long elapsedMs = Duration.between(startTime, Instant.now()).toMillis();
        long totalTimeoutMs = connectTimeoutMs * 3;
        if (elapsedMs > totalTimeoutMs) {
            throw new IOException("Total request timeout exceeded (" + elapsedMs + "ms > " + totalTimeoutMs + "ms)");
        }

        HttpResponse<T> response = httpClient.send(request, bodyHandler);
        int statusCode = response.statusCode();

        if (!REDIRECT_CODES.contains(statusCode)) {
            return response;
        }

        // Handle redirect
        redirectCount++;
        if (redirectCount > MAX_REDIRECTS) {
            throw new IOException("Too many redirects (" + redirectCount + ") for URL: " + request.uri());
        }

        String location = response.headers().firstValue("Location").orElse(null);
        if (location == null || location.isBlank()) {
            throw new IOException("Redirect " + statusCode + " without Location header from: " + request.uri());
        }

        // Resolve relative redirect against the current URI
        URI resolvedUri = request.uri().resolve(location);

        // Validate the redirect target — prevents SSRF via 302 → internal
        validateRedirectTarget(resolvedUri.toString());

        LOGGER.debugf("Following redirect %d/%d: %s → %s", redirectCount, MAX_REDIRECTS, request.uri(), resolvedUri);

        // Build redirect request — preserve method for 307/308 per RFC 7538
        boolean methodPreserved = (statusCode == 307 || statusCode == 308) && !"GET".equals(request.method());
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(resolvedUri)
                .timeout(request.timeout().orElse(Duration.ofSeconds(15)));

        // Copy headers from original request, with security-aware filtering
        boolean sameOrigin = isSameOrigin(request.uri(), resolvedUri);
        copyHeaders(request, builder, sameOrigin, methodPreserved);

        if (methodPreserved) {
            // 307/308: preserve original HTTP method and body
            builder.method(request.method(),
                    request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        } else {
            // 301/302/303: always downgrade to GET per RFC 7231
            builder.GET();
        }

        HttpRequest redirectRequest = builder.build();

        return sendWithRedirects(redirectRequest, bodyHandler, redirectCount, startTime);
    }

    /**
     * Copies headers from the original request to the redirect request.
     * <p>
     * Same-origin: all headers are copied (except HttpClient-managed ones).
     * Cross-origin: Authorization, Cookie, Proxy-Authorization are stripped. Method
     * downgrade (301/302/303): Content-Type is not copied (no body on GET).
     */
    private void copyHeaders(HttpRequest original, HttpRequest.Builder builder,
                             boolean sameOrigin, boolean methodPreserved) {
        original.headers().map().forEach((name, values) -> {
            String lower = name.toLowerCase();

            // Skip headers managed by HttpClient
            if (MANAGED_HEADERS.contains(lower))
                return;

            // Skip body-related headers when method downgrades to GET
            if (!methodPreserved && "content-type".equals(lower))
                return;

            // Strip sensitive headers on cross-origin redirects
            if (!sameOrigin && SENSITIVE_HEADERS.contains(lower))
                return;

            for (String value : values) {
                builder.header(name, value);
            }
        });

        // Ensure User-Agent is always present
        if (original.headers().firstValue("User-Agent").isEmpty()) {
            builder.header("User-Agent", "EDDI-Agent/1.0");
        }
    }

    /**
     * Checks if two URIs share the same origin (scheme + host + port).
     */
    private static boolean isSameOrigin(URI a, URI b) {
        if (!Objects.equals(a.getScheme(), b.getScheme()))
            return false;
        if (!a.getHost().equalsIgnoreCase(b.getHost()))
            return false;
        return effectivePort(a) == effectivePort(b);
    }

    /**
     * Returns the effective port for a URI, using default ports for http/https.
     */
    private static int effectivePort(URI uri) {
        int port = uri.getPort();
        if (port != -1)
            return port;
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /**
     * Validates a redirect target URL against SSRF rules. Package-private to allow
     * test overrides for embedded server tests where the redirect target is on
     * loopback.
     */
    void validateRedirectTarget(String url) {
        UrlValidationUtils.validateUrl(url);
    }

    /**
     * Returns the underlying HttpClient for cases where raw access is needed (e.g.,
     * WebSocket, SSE). Callers using this are responsible for their own SSRF
     * protection.
     */
    public HttpClient unwrap() {
        return httpClient;
    }
}
