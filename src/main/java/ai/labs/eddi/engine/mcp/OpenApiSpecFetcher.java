/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.httpclient.BoundedBodyReader;
import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import ai.labs.eddi.engine.httpclient.SafeHttpClient;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

/**
 * Fetches a remote OpenAPI document for {@link McpApiToolBuilder#parseSpec}.
 * <p>
 * swagger-parser's own {@code readLocation} fetched the document through its
 * {@code RemoteUrl} helper, which follows up to five redirects with no check on
 * where they lead: a spec URL on a public host that answered
 * {@code 302 Location: http://10.0.0.5/…} was followed, and the result parsed
 * and reflected back to the editor as a list of endpoints. The spec location is
 * editor-supplied ({@code POST /apicallstore/apicalls/discover-endpoints}, the
 * setup wizard's {@code openApiSpec}), so the document is fetched here instead:
 * <ul>
 * <li>every hop — the location and each redirect target — goes through
 * {@link OutboundTargetPolicy#validateUrl}: the cloud metadata service is
 * refused always, and with {@code eddi.security.ssrf-protection.enabled} so is
 * any private, loopback or link-local address;</li>
 * <li>redirects are followed by hand ({@link SafeHttpClient#sendNoRedirect}),
 * at most {@value #MAX_REDIRECTS} of them, GET only, no credentials;</li>
 * <li>the body is read through {@link BoundedBodyReader} and refused above
 * {@value #MAX_SPEC_BYTES} bytes, while it streams, not after buffering.</li>
 * </ul>
 * The content is then handed to the parser as text with the location as its
 * base, so a multi-file spec's relative {@code $ref}s still resolve as before —
 * through swagger-parser's own blocked-URL resolver, which is unchanged.
 *
 * @since 6.6.0
 */
@ApplicationScoped
public class OpenApiSpecFetcher {

    private static final Logger LOGGER = Logger.getLogger(OpenApiSpecFetcher.class);

    /** Most bytes of spec accepted. Large public specs run to a few megabytes. */
    static final long MAX_SPEC_BYTES = 16L * 1024 * 1024;

    /** Redirect hops followed before the fetch fails, as {@link SafeHttpClient}. */
    static final int MAX_REDIRECTS = 5;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 303, 307, 308);

    /**
     * Lazily supplied: a JDK {@code HttpClient} opens a loopback selector pipe on
     * construction, which an inline-only caller (and a sandbox without loopback)
     * never needs. Memoized after the first remote fetch.
     */
    private final Supplier<SafeHttpClient> httpClient;
    private final OutboundTargetPolicy targetPolicy;
    private final long maxSpecBytes;
    private final ScheduledExecutorService bodyReadWatchdog = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "openapi-spec-body-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    @Inject
    public OpenApiSpecFetcher(SafeHttpClient httpClient, OutboundTargetPolicy targetPolicy) {
        this(httpClient, targetPolicy, MAX_SPEC_BYTES);
    }

    /** For tests: a cap small enough to hit without serving 16 MB. */
    OpenApiSpecFetcher(SafeHttpClient httpClient, OutboundTargetPolicy targetPolicy, long maxSpecBytes) {
        this(() -> httpClient, targetPolicy, maxSpecBytes);
    }

    private OpenApiSpecFetcher(Supplier<SafeHttpClient> httpClient, OutboundTargetPolicy targetPolicy, long maxSpecBytes) {
        this.httpClient = httpClient;
        this.targetPolicy = targetPolicy;
        this.maxSpecBytes = maxSpecBytes;
    }

    /**
     * A fetcher with protection off (metadata check per hop only), for callers
     * outside CDI: the tests, and {@link McpApiToolBuilder}'s fetcher-less
     * overloads. Production callers inject their own, which carries the
     * deployment's setting.
     */
    public static OpenApiSpecFetcher permissive() {
        return Permissive.INSTANCE;
    }

    private static final class Permissive {
        private static final OpenApiSpecFetcher INSTANCE = new OpenApiSpecFetcher(memoized(() -> new SafeHttpClient(10_000)),
                OutboundTargetPolicy.permissive(), MAX_SPEC_BYTES);

        private static Supplier<SafeHttpClient> memoized(Supplier<SafeHttpClient> factory) {
            return new Supplier<>() {
                private volatile SafeHttpClient client;

                @Override
                public SafeHttpClient get() {
                    SafeHttpClient local = client;
                    if (local == null) {
                        synchronized (this) {
                            local = client;
                            if (local == null) {
                                client = local = factory.get();
                            }
                        }
                    }
                    return local;
                }
            };
        }
    }

    /**
     * The document at {@code location}, as text.
     *
     * @throws IllegalArgumentException
     *             when the location or a redirect target is refused by the policy,
     *             the server does not answer 200, the body exceeds the cap, there
     *             are too many redirects, or the fetch fails
     */
    public String fetch(String location) {
        URI uri = UrlValidationUtils.validateUrlSyntax(location.trim());
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            targetPolicy.validateUrl(uri.toString());
            HttpRequest request = HttpRequest.newBuilder(uri).GET().timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json, application/yaml, application/x-yaml, text/yaml, */*;q=0.5")
                    .header("User-Agent", "EDDI-OpenAPI-Discovery/1.0").build();
            HttpResponse<InputStream> response;
            try {
                response = httpClient.get().sendNoRedirect(request, OpenApiSpecFetcher::bodyOrNothing);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not fetch the OpenAPI spec from " + forMessage(uri) + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("Interrupted while fetching the OpenAPI spec from " + forMessage(uri), e);
            }
            int status = response.statusCode();
            if (REDIRECT_CODES.contains(status)) {
                String target = response.headers().firstValue("Location").orElse(null);
                if (target == null || target.isBlank()) {
                    throw new IllegalArgumentException(
                            "OpenAPI spec location " + forMessage(uri) + " answered " + status + " without a Location header");
                }
                URI next = uri.resolve(target.trim());
                LOGGER.debugf("OpenAPI spec redirect %d/%d: %s -> %s", hop + 1, MAX_REDIRECTS, forMessage(uri), forMessage(next));
                uri = next;
                continue;
            }
            if (status != 200) {
                throw new IllegalArgumentException("OpenAPI spec location " + forMessage(uri) + " answered HTTP " + status);
            }
            BoundedBodyReader.Bounded body;
            try {
                body = BoundedBodyReader.read(response.body(), maxSpecBytes, REQUEST_TIMEOUT, bodyReadWatchdog);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not read the OpenAPI spec from " + forMessage(uri) + ": " + e.getMessage(), e);
            }
            if (body.truncated()) {
                throw new IllegalArgumentException("OpenAPI spec at " + forMessage(uri) + " is larger than the " + maxSpecBytes
                        + " byte limit, or did not arrive within " + REQUEST_TIMEOUT.toSeconds() + " seconds");
            }
            return new String(body.bytes(), StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("OpenAPI spec location redirected more than " + MAX_REDIRECTS + " times: " + forMessage(uri));
    }

    /**
     * A redirect's body is never read, so it gets a subscriber that drops it and
     * completes with an empty stream; any other response is streamed to the caller.
     */
    private static HttpResponse.BodySubscriber<InputStream> bodyOrNothing(HttpResponse.ResponseInfo info) {
        return REDIRECT_CODES.contains(info.statusCode())
                ? HttpResponse.BodySubscribers.replacing(InputStream.nullInputStream())
                : HttpResponse.BodySubscribers.ofInputStream();
    }

    /**
     * {@code uri} for an error message or log line: scheme, host, port and path
     * only. Userinfo and the query may carry a credential, and these messages reach
     * the editor and the log.
     */
    static String forMessage(URI uri) {
        int port = uri.getPort();
        return uri.getScheme() + "://" + uri.getHost() + (port == -1 ? "" : ":" + port) + (uri.getRawPath() == null ? "" : uri.getRawPath());
    }
}
