/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import java.io.IOException;
import java.time.Duration;

/**
 * One HTTP GET, as the crawler needs it.
 *
 * <p>
 * This exists so {@link WebCrawler} can be tested. The crawler's interesting
 * behaviour — scope decisions, budgets, redirect identity, robots, conditional
 * requests, error accounting — is all reachable with a stub fetcher and no
 * network, no container and no test server. The draft this replaces built its
 * requests inline against {@code SafeHttpClient}, so none of that logic could
 * be exercised without standing up a web server, and in practice none of it
 * was: its only coverage was one Testcontainers test that the unit gate does
 * not run.
 */
@FunctionalInterface
public interface PageFetcher {

    /**
     * Fetches a URL. Implementations must not follow a redirect to an address the
     * SSRF rules reject, and must not read more than {@code maxBytes} of the body.
     *
     * @throws IOException
     *             on a transport failure; the crawler records it against the URL
     *             and carries on with the rest of the queue
     */
    FetchedPage fetch(FetchCommand command) throws IOException, InterruptedException;

    /**
     * What to fetch and under what limits.
     *
     * @param ifNoneMatch
     *            ETag from a previous run, for a conditional request; may be null
     * @param ifModifiedSince
     *            Last-Modified from a previous run; may be null
     * @param maxBytes
     *            hard cap on the body read, so one oversized response cannot
     *            exhaust the heap
     */
    record FetchCommand(
            String url,
            String userAgent,
            Duration timeout,
            String ifNoneMatch,
            String ifModifiedSince,
            long maxBytes) {
    }

    /**
     * What came back.
     *
     * @param finalUrl
     *            the URL after redirects — the page's real identity. Recording the
     *            requested URL instead makes {@code /docs} and {@code /docs/} two
     *            documents with identical content, and resolves that page's
     *            relative links against the wrong base.
     * @param declaredCharset
     *            charset from the Content-Type header, or null to let the parser
     *            sniff the document's own declaration
     * @param truncated
     *            whether the body hit {@code maxBytes} and was cut short
     */
    record FetchedPage(
            int statusCode,
            String finalUrl,
            String contentType,
            String declaredCharset,
            byte[] body,
            String etag,
            String lastModified,
            boolean truncated) {

        public boolean isOk() {
            return statusCode == 200;
        }

        /**
         * The server says nothing changed since the conditional headers were issued.
         */
        public boolean isNotModified() {
            return statusCode == 304;
        }

        public boolean isHtml() {
            if (contentType == null || contentType.isBlank()) {
                // No Content-Type at all: assume HTML rather than discarding the page.
                // Parsing something non-HTML yields noise; discarding it loses content.
                return true;
            }
            String normalized = contentType.toLowerCase();
            return normalized.contains("html") || normalized.contains("xhtml") || normalized.contains("xml");
        }
    }
}
