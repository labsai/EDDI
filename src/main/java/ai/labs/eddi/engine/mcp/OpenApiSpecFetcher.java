/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.httpclient.OutboundTargetPolicy;
import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.BodyTooLargeException;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.OutboundTimeoutException;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.RedirectPolicy;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

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
 * <li>redirects are followed by hand — each hop is one {@link OutboundHttp}
 * send with {@link RedirectPolicy#NEVER} — at most {@value #MAX_REDIRECTS} of
 * them, GET only, no credentials;</li>
 * <li>the body is a capped facade stream, refused above
 * {@value #MAX_SPEC_BYTES} bytes while it streams, not after buffering.</li>
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

    /** Redirect hops followed before the fetch fails, as {@link OutboundHttp}. */
    static final int MAX_REDIRECTS = 5;

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<Integer> REDIRECT_CODES = Set.of(301, 302, 303, 307, 308);

    /**
     * Lazily supplied for the permissive instance: a standalone facade's engine
     * opens a loopback selector pipe on first use, which an inline-only caller (and
     * a sandbox without loopback) never needs. Memoized after the first remote
     * fetch.
     */
    private final Supplier<OutboundHttp> http;
    private final OutboundTargetPolicy targetPolicy;
    private final long maxSpecBytes;

    @Inject
    public OpenApiSpecFetcher(OutboundHttp http, OutboundTargetPolicy targetPolicy) {
        this(http, targetPolicy, MAX_SPEC_BYTES);
    }

    /** For tests: a cap small enough to hit without serving 16 MB. */
    OpenApiSpecFetcher(OutboundHttp http, OutboundTargetPolicy targetPolicy, long maxSpecBytes) {
        this(() -> http, targetPolicy, maxSpecBytes);
    }

    private OpenApiSpecFetcher(Supplier<OutboundHttp> http, OutboundTargetPolicy targetPolicy, long maxSpecBytes) {
        this.http = http;
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
        private static final OpenApiSpecFetcher INSTANCE = new OpenApiSpecFetcher(
                memoized(() -> OutboundHttp.standalone(Duration.ofSeconds(10))), OutboundTargetPolicy.permissive(), MAX_SPEC_BYTES);

        private static Supplier<OutboundHttp> memoized(Supplier<OutboundHttp> factory) {
            return new Supplier<>() {
                private volatile OutboundHttp client;

                @Override
                public OutboundHttp get() {
                    OutboundHttp local = client;
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
     * A fetched document and the URL it was finally read from — after any redirects
     * — which is the base a relative {@code $ref} resolves against. A spec at
     * {@code /openapi.yaml} redirected to {@code /v2/openapi.yaml} references
     * {@code ./schemas.yaml} beside the <em>fetched</em> document, not the
     * original.
     */
    public record FetchedSpec(String content, String location) {
    }

    /** {@link #fetchSpec(String)}, content only. */
    public String fetch(String location) {
        return fetchSpec(location).content();
    }

    /**
     * The document at {@code location}, as text, with the URL it was read from.
     *
     * @throws IllegalArgumentException
     *             when the location or a redirect target is refused by the policy,
     *             the server does not answer 200, the body exceeds the cap, there
     *             are too many redirects, or the fetch fails
     */
    public FetchedSpec fetchSpec(String location) {
        URI uri = UrlValidationUtils.validateUrlSyntax(location.trim());
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            targetPolicy.validateUrl(uri.toString());
            // This hop passed targetPolicy.validateUrl above, which is the gate (it
            // carries the deployment's SSRF flag and allowed hosts), so the facade adds
            // no check of its own; redirects are this loop's, never the facade's.
            OutboundRequest request = OutboundRequest.get(Profile.DEFAULT, uri)
                    .ssrf(SsrfPolicy.NONE)
                    .redirects(RedirectPolicy.NEVER)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json, application/yaml, application/x-yaml, text/yaml, */*;q=0.5")
                    .header("User-Agent", "EDDI-OpenAPI-Discovery/1.0")
                    .build();
            OutboundResponse<InputStream> response;
            try {
                // codeql[java/ssrf] False Positive: fetching the editor-supplied spec
                // location is the feature; this hop passed targetPolicy.validateUrl above.
                // Capped while it streams; the read budget is 4 x the request timeout.
                response = http.get().send(request, BodyMode.stream(maxSpecBytes, REQUEST_TIMEOUT.multipliedBy(4)));
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not fetch the OpenAPI spec from " + forMessage(uri) + ": " + e.getMessage(), e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalArgumentException("Interrupted while fetching the OpenAPI spec from " + forMessage(uri), e);
            }
            int status = response.status();
            if (REDIRECT_CODES.contains(status)) {
                // A redirect's body is never read: closing it drops the connection.
                closeQuietly(response.body());
                String target = response.firstHeader("Location").orElse(null);
                if (target == null || target.isBlank()) {
                    throw new IllegalArgumentException(
                            "OpenAPI spec location " + forMessage(uri) + " answered " + status + " without a Location header");
                }
                URI next = uri.resolve(target.trim());
                // The target comes from the server's Location header: sanitized so a
                // crafted header cannot forge a log line.
                LOGGER.debugf("OpenAPI spec redirect %d/%d: %s -> %s", hop + 1, MAX_REDIRECTS, sanitize(forMessage(uri)), sanitize(forMessage(next)));
                uri = next;
                continue;
            }
            if (status != 200) {
                // Nothing reads an error body, but the stream holds the connection until it
                // is closed; repeated failed discoveries would otherwise pile them up.
                closeQuietly(response.body());
                throw new IllegalArgumentException("OpenAPI spec location " + forMessage(uri) + " answered HTTP " + status);
            }
            byte[] body;
            try (InputStream in = response.body()) {
                body = in.readAllBytes();
            } catch (BodyTooLargeException | OutboundTimeoutException e) {
                throw new IllegalArgumentException("OpenAPI spec at " + forMessage(uri) + " is larger than the " + maxSpecBytes
                        + " byte limit, or did not arrive within " + REQUEST_TIMEOUT.multipliedBy(4).toSeconds() + " seconds", e);
            } catch (IOException e) {
                throw new IllegalArgumentException("Could not read the OpenAPI spec from " + forMessage(uri) + ": " + e.getMessage(), e);
            }
            return new FetchedSpec(new String(body, StandardCharsets.UTF_8), uri.toString());
        }
        throw new IllegalArgumentException("OpenAPI spec location redirected more than " + MAX_REDIRECTS + " times: " + forMessage(uri));
    }

    private static void closeQuietly(InputStream body) {
        try {
            body.close();
        } catch (IOException e) {
            // Best effort: the status is what the caller needs to hear about.
        }
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
