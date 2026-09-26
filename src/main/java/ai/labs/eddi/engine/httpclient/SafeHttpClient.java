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
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
 * <li>Overall wall-clock deadline covers the whole exchange — every hop,
 * <em>including reading the response body</em> — see {@link #totalBudget}</li>
 * <li>The bodies of redirect responses are discarded, never handed to the
 * caller's body handler, so an {@code ofInputStream} caller cannot leak the
 * connection of a hop it never sees</li>
 * <li>On cross-origin redirects, Authorization/Cookie headers are stripped</li>
 * </ul>
 * <p>
 * The deadline bounds time, not size: a caller reading an untrusted body should
 * pass a {@link BoundedBodyHandlers} handler so the body cannot exhaust memory
 * before the deadline arrives. A streaming handler ({@code ofInputStream},
 * {@code ofLines}, a publisher) completes when the headers arrive, so the
 * deadline cannot reach reads made after that — such callers bound their own
 * reads, as {@code SafeHttpPageFetcher} does.
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
        HttpRequest bounded = withDefaultTimeout(request);
        return sendWithRedirects(bounded, bodyHandler, 0, deadlineFor(bounded));
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
        HttpRequest bounded = withDefaultTimeout(request);
        return sendWithRedirects(bounded, bodyHandler, 0, deadlineFor(bounded));
    }

    /**
     * Sends an HTTP request with SSRF validation on the initial URL and <em>without
     * following any redirect</em>: a 3xx is returned to the caller as the response,
     * {@code Location} and all, and no second request is ever made.
     * <p>
     * For requests that carry a credential the caller has vouched for one specific
     * origin — an OAuth token request, whose client secret sits in the
     * {@code Authorization} header or the form body and whose refresh token or
     * authorization code is in the body. {@link #sendValidated} would honour a
     * 307/308 with the method and body preserved and re-send all of that to
     * whatever host the redirect named, after checking only that the host is not
     * private; the caller's own allowlist is never consulted for the second hop.
     * Here the caller decides what a 3xx means, and for a token endpoint the answer
     * is "refuse".
     *
     * @throws IllegalArgumentException
     *             if the URL is unsafe
     */
    public <T> HttpResponse<T> sendValidatedNoRedirect(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        validateInitialTarget(request.uri().toString());
        return sendNoRedirect(request, bodyHandler);
    }

    /**
     * Sends an HTTP request exactly once, following no redirect, and without
     * validating the URL.
     * <p>
     * For callers that have already decided the target is acceptable by a rule
     * stricter than the SSRF check — an operator-maintained allowlist, for example,
     * which is allowed to name a host on a private network that the SSRF rules
     * would refuse. Callers that fetch anything user- or config-controlled without
     * such a rule must use {@link #sendValidatedNoRedirect} instead.
     */
    public <T> HttpResponse<T> sendNoRedirect(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        HttpRequest bounded = withDefaultTimeout(request);
        return sendOnce(bounded, bodyHandler, deadlineFor(bounded));
    }

    /**
     * The wall-clock budget for one call, redirects and body included: three times
     * the connect timeout (30s by default), or the request's own timeout when the
     * caller asked for longer. The per-request timeout alone is no bound at all on
     * the body — the JDK stops timing once the response headers arrive, so a server
     * that sends headers and then trickles the body a byte at a time held a tool
     * call open indefinitely.
     * <p>
     * Package-private for the test.
     */
    Duration totalBudget(HttpRequest request) {
        Duration floor = Duration.ofMillis(connectTimeoutMs * 3);
        Duration requested = request.timeout().orElse(DEFAULT_REQUEST_TIMEOUT);
        return requested.compareTo(floor) > 0 ? requested : floor;
    }

    private long deadlineFor(HttpRequest request) {
        return System.nanoTime() + totalBudget(request).toNanos();
    }

    /**
     * One exchange, bounded by {@code deadlineNanos} from sending the request to
     * the body handler's completion. On expiry the exchange is cancelled, which
     * closes its connection, and the call fails with {@link HttpTimeoutException}.
     */
    private <T> HttpResponse<T> sendOnce(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler, long deadlineNanos)
            throws IOException, InterruptedException {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            throw new HttpTimeoutException("Total request timeout exceeded for: " + request.uri());
        }
        CompletableFuture<HttpResponse<T>> exchange = httpClient.sendAsync(request, bodyHandler);
        try {
            return exchange.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            exchange.cancel(true);
            throw new HttpTimeoutException("Total request timeout exceeded for: " + request.uri());
        } catch (InterruptedException e) {
            exchange.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            throw unwrap(e);
        }
    }

    /**
     * Rethrows an async failure the way {@link HttpClient#send} would have thrown
     * it: the {@link IOException} itself (so a caller can still catch
     * {@link BoundedBodyHandlers.ResponseTooLargeException} or
     * {@link HttpTimeoutException} by type), an unchecked exception or error as is,
     * anything else wrapped.
     */
    private static IOException unwrap(ExecutionException e) {
        Throwable cause = e.getCause();
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
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

    /**
     * Hands a 3xx body to a discarding subscriber and every other body to the
     * caller's handler. A redirect response is never returned to the caller, so its
     * body has no reader: given to an {@code ofInputStream} handler it was an
     * unclosed stream per hop, holding that hop's connection until the GC found it.
     */
    private static <T> HttpResponse.BodyHandler<T> discardingRedirectBodies(HttpResponse.BodyHandler<T> bodyHandler) {
        return info -> REDIRECT_CODES.contains(info.statusCode())
                ? HttpResponse.BodySubscribers.replacing(null)
                : bodyHandler.apply(info);
    }

    /**
     * Validates the initial request URL against SSRF rules. Package-private for the
     * same reason as {@link #validateRedirectTarget(String)}: an embedded test
     * server lives on loopback.
     */
    void validateInitialTarget(String url) {
        UrlValidationUtils.validateUrl(url);
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
                                                  int redirectCount, long deadlineNanos)
            throws IOException, InterruptedException {

        // One deadline for the whole chain, bodies included (see totalBudget): an
        // attacker can neither chain slow redirects nor trickle a body to hold the
        // connection — and the calling tool — open indefinitely.
        HttpResponse<T> response = sendOnce(request, discardingRedirectBodies(bodyHandler), deadlineNanos);
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

        return sendWithRedirects(redirectRequest, bodyHandler, redirectCount, deadlineNanos);
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
