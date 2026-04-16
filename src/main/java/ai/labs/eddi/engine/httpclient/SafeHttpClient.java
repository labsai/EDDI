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
 * <li>Connect timeout is enforced</li>
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

    private final HttpClient httpClient;

    public SafeHttpClient(
            @ConfigProperty(name = "httpClient.connectTimeoutInMillis", defaultValue = "10000") int connectTimeoutMs) {
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
        return sendWithRedirects(request, bodyHandler, 0);
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
        return sendWithRedirects(request, bodyHandler, 0);
    }

    private <T> HttpResponse<T> sendWithRedirects(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler,
                                                  int redirectCount)
            throws IOException, InterruptedException {

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
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(resolvedUri)
                .timeout(request.timeout().orElse(Duration.ofSeconds(15)))
                .header("User-Agent", request.headers().firstValue("User-Agent").orElse("EDDI-Agent/1.0"));

        if ((statusCode == 307 || statusCode == 308) && !"GET".equals(request.method())) {
            // 307/308: preserve original HTTP method and body
            builder.method(request.method(),
                    request.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()));
        } else {
            // 301/302/303: always downgrade to GET per RFC 7231
            builder.GET();
        }

        HttpRequest redirectRequest = builder.build();

        return sendWithRedirects(redirectRequest, bodyHandler, redirectCount);
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
