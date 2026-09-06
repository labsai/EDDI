/*
 * Copyright EDDI contributors
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
 * <li>Response timeout is enforced per-hop ({@link #DEFAULT_REQUEST_TIMEOUT}
 * when the caller set none)</li>
 * <li>Overall wall-clock budget is checked between hops, so a redirect chain
 * cannot outlive it by more than one hop's timeout</li>
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

    /**
     * Per-hop response timeout applied when the caller set none. The wall-clock
     * budget below is only consulted BETWEEN hops, so without a per-request bound a
     * single hop that accepts the connection and then trickles (or never completes)
     * the response body hangs forever and the budget never gets a chance to fire.
     * Every in-tree caller sets its own timeout; this is the backstop for the ones
     * that do not.
     */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(15);

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
        return sendWithRedirects(withDefaultTimeout(request), bodyHandler, 0, Instant.now());
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
        return sendWithRedirects(withDefaultTimeout(request), bodyHandler, 0, Instant.now());
    }

    /**
     * Returns {@code request} unchanged when it already carries a timeout, else a
     * copy bounded by {@link #DEFAULT_REQUEST_TIMEOUT}. {@link HttpRequest} is
     * immutable, so the bound can only be applied by rebuilding.
     * <p>
     * Package-private so {@code SafeHttpClientTimeoutTest} can pin the rebuild
     * without an embedded server — the server-backed cases live in
     * {@code SafeHttpClientTest} and only run where loopback sockets are available.
     */
    static HttpRequest withDefaultTimeout(HttpRequest request) {
        if (request.timeout().isPresent()) {
            return request;
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(DEFAULT_REQUEST_TIMEOUT)
                .method(request.method(), request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        request.headers().map().forEach((name, values) -> {
            // Same exclusion as copyHeaders: HttpClient owns these and rejects
            // an attempt to set them explicitly.
            if (!MANAGED_HEADERS.contains(name.toLowerCase())) {
                for (String value : values) {
                    builder.header(name, value);
                }
            }
        });
        request.version().ifPresent(builder::version);
        if (request.expectContinue()) {
            builder.expectContinue(true);
        }
        return builder.build();
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

        // Build the redirect request. RFC 9110 sanctions exactly one method rewrite,
        // and it is narrower than "everything becomes GET":
        // • 307/308 (§15.4.8/§15.4.9): method AND body preserved, always.
        // • 303 See Other (§15.4.4): rewrite to GET — that is what the code means.
        // • 301/302 (§15.4.2/§15.4.3): only the historical POST→GET rewrite is
        // permitted. PUT, PATCH and DELETE keep their method and body, or a
        // redirected write silently becomes a read: the caller is told the write
        // succeeded (200 from the GET) while nothing was written, and a DELETE
        // that "worked" leaves the resource in place.
        // • HEAD survives every one of them (an existence or size probe must not
        // turn into a full body download).
        boolean methodPreserved = methodSurvivesRedirect(request.method(), statusCode);
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(resolvedUri)
                .timeout(request.timeout().orElse(DEFAULT_REQUEST_TIMEOUT));

        // Copy headers from original request, with security-aware filtering
        boolean sameOrigin = isSameOrigin(request.uri(), resolvedUri);
        copyHeaders(request, builder, sameOrigin, methodPreserved);

        if (methodPreserved) {
            builder.method(request.method(),
                    request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        } else if ("HEAD".equals(request.method())) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else {
            builder.GET();
        }

        HttpRequest redirectRequest = builder.build();

        return sendWithRedirects(redirectRequest, bodyHandler, redirectCount, startTime);
    }

    /**
     * Whether this request's method (and its body) carries across a redirect of
     * this status code — see the rules quoted at the call site.
     * <p>
     * GET and HEAD answer {@code false} because they have no body to carry and are
     * rebuilt explicitly by the caller; every other method answers whether the
     * status code leaves it alone.
     * <p>
     * Package-private so the rule can be pinned per method/code pair without a
     * server on every combination.
     */
    static boolean methodSurvivesRedirect(String method, int statusCode) {
        if ("GET".equals(method) || "HEAD".equals(method)) {
            return false;
        }
        if (statusCode == 307 || statusCode == 308) {
            return true;
        }
        if (statusCode == 303) {
            return false;
        }
        // 301/302: POST is the only method the historical rewrite covers.
        return !"POST".equals(method);
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
