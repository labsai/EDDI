/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * The production {@link PageFetcher}: {@link OutboundHttp} with a bounded body
 * read.
 *
 * <p>
 * Every request is {@link SsrfPolicy#STRICT}, so the seed and each redirect hop
 * are checked against the SSRF rules. The crawler follows links harvested from
 * third-party pages, which is as user-controlled as a URL gets — so validation
 * happens here, per request, rather than once on the seed.
 *
 * <p>
 * The body is capped as it is read, never buffered whole first. A draft read
 * the entire response into a String <em>before</em> checking its Content-Type,
 * so a documentation page linking a 1 GB installer downloaded the installer in
 * full and then threw it away. The cap also removes the tarpit: a server that
 * streams forever cannot exhaust the heap. The facade gives the body read four
 * times the request timeout, since the request timeout covers the headers only;
 * a body cut short by either limit comes back marked truncated.
 */
@ApplicationScoped
public class SafeHttpPageFetcher implements PageFetcher {

    private static final String HEADER_USER_AGENT = "User-Agent";
    private static final String HEADER_ACCEPT = "Accept";

    private static final String ACCEPT_HTML = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    private final OutboundHttp http;

    @Inject
    public SafeHttpPageFetcher(OutboundHttp http) {
        this.http = http;
    }

    @Override
    public FetchedPage fetch(FetchCommand command) throws IOException, InterruptedException {
        OutboundRequest.Builder builder = OutboundRequest.get(Profile.DEFAULT, URI.create(command.url()))
                .ssrf(SsrfPolicy.STRICT)
                .header(HEADER_USER_AGENT, command.userAgent())
                .header(HEADER_ACCEPT, ACCEPT_HTML);
        if (command.timeout() != null && !command.timeout().isZero() && !command.timeout().isNegative()) {
            builder.timeout(command.timeout());
        }

        // Conditional headers turn an unchanged page into a 304 with no body: the
        // difference between re-downloading a whole site nightly and asking it what
        // changed.
        if (command.ifNoneMatch() != null && !command.ifNoneMatch().isBlank()) {
            builder.header("If-None-Match", command.ifNoneMatch());
        }
        if (command.ifModifiedSince() != null && !command.ifModifiedSince().isBlank()) {
            builder.header("If-Modified-Since", command.ifModifiedSince());
        }

        long cap = command.maxBytes() > 0 ? command.maxBytes() : Long.MAX_VALUE;
        OutboundResponse<byte[]> response = http.send(builder.build(), BodyMode.bytes(cap, BodyMode.OnOverflow.TRUNCATE));

        String contentType = response.firstHeader("Content-Type").orElse(null);
        String etag = response.firstHeader("ETag").orElse(null);
        String lastModified = response.firstHeader("Last-Modified").orElse(null);
        String finalUrl = response.finalUri() != null ? response.finalUri().toString() : command.url();

        if (response.status() == 304) {
            return new FetchedPage(304, finalUrl, contentType, null, new byte[0], etag, lastModified, false);
        }
        return new FetchedPage(response.status(), finalUrl, contentType, charsetOf(contentType),
                response.body(), etag, lastModified, response.truncated());
    }

    /**
     * Charset from the Content-Type header, or null so the parser can sniff the
     * document's own {@code <meta charset>}. Assuming UTF-8 — which
     * {@code BodyHandlers.ofString()} does — turns every legacy Windows-1252 page
     * into mojibake, and mojibake embeds perfectly happily.
     */
    private static String charsetOf(String contentType) {
        if (contentType == null) {
            return null;
        }
        for (String part : contentType.split(";")) {
            String trimmed = part.trim().toLowerCase(Locale.ROOT);
            if (trimmed.startsWith("charset=")) {
                String charset = trimmed.substring("charset=".length()).replace("\"", "").trim();
                if (charset.isEmpty()) {
                    return null;
                }
                // A name the JVM does not know (utf8mb4) or cannot even parse
                // ("utf-8, utf-8", which a broken proxy really does send) would throw
                // out of the parser and lose the page — and a lost page counts as a
                // miss, so two runs later it is deleted. Null instead: the parser
                // sniffs the document's own meta charset.
                try {
                    return Charset.isSupported(charset) ? charset : null;
                } catch (IllegalArgumentException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
