/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

/**
 * Where a crawl's results go, one page at a time.
 *
 * <p>
 * A callback rather than a returned list. The draft this replaces collected
 * every page's full HTML into a {@code List} and returned it when the crawl
 * finished, so a 10,000-page allowance meant holding the whole site — gigabytes
 * — in memory before a single embedding call. Streaming makes the crawler's
 * memory use independent of the site's size, and makes a dry-run preview and
 * mid-crawl cancellation fall out for free.
 */
public interface CrawlSink {

    /**
     * Conditional-request headers for a document the sink has seen before.
     * Returning what a previous run stored turns an unchanged page into a 304 with
     * no body.
     */
    default ConditionalHeaders conditionalFor(String documentId) {
        return ConditionalHeaders.none();
    }

    /** A page was fetched and parsed. */
    void onPage(CrawledPage page);

    /** The server answered 304: the sink's stored copy is still current. */
    default void onUnchanged(String documentId) {
        // Most sinks only need the page callback.
    }

    /** A URL could not be used. The crawl continues. */
    default void onError(CrawlError error) {
        // Errors are aggregated in the summary; overriding is for reporting detail.
    }

    /**
     * Checked between pages so a long crawl can be stopped — by an operator, or by
     * a scheduler whose lease is about to expire.
     */
    default boolean isCancelled() {
        return false;
    }

    /** ETag and Last-Modified from a previous fetch of the same document. */
    record ConditionalHeaders(String etag, String lastModified) {

        private static final ConditionalHeaders NONE = new ConditionalHeaders(null, null);

        public static ConditionalHeaders none() {
            return NONE;
        }

        public boolean isEmpty() {
            return (etag == null || etag.isBlank()) && (lastModified == null || lastModified.isBlank());
        }
    }

    /**
     * One fetched page.
     *
     * @param documentId
     *            stable canonical identity, used to match this page against what a
     *            previous run stored. Derived from the URL after redirects and
     *            after any {@code <link rel="canonical">}.
     * @param finalUrl
     *            the address actually fetched, for citation
     * @param html
     *            the decoded document
     * @param truncated
     *            whether the body hit its size cap, so the content is incomplete
     */
    record CrawledPage(
            String documentId,
            String finalUrl,
            String title,
            String html,
            String etag,
            String lastModified,
            int depth,
            boolean truncated) {
    }

    /**
     * A URL that could not be crawled.
     *
     * @param statusCode
     *            the HTTP status, or 0 when the failure was not an HTTP response
     */
    record CrawlError(String url, String reason, int statusCode) {
    }
}
