/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.modules.ingestion.connector.Coverage;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryContext;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryResult;
import ai.labs.eddi.modules.ingestion.connector.Partition;
import ai.labs.eddi.modules.ingestion.connector.SourceConnector;
import ai.labs.eddi.modules.ingestion.connector.SourceContext;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument.Content;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument.ResponseValidators;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.modules.ingestion.connector.SourceMetadataKeys;
import ai.labs.eddi.modules.ingestion.connector.StopReason;
import ai.labs.eddi.modules.ingestion.connector.WorkItem;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code web} source: a crawl, through {@link WebCrawler}.
 *
 * <p>
 * Discovery style C (listing only), where the listing is the crawl itself: a
 * crawler learns the next link only by reading the page, so every page is
 * handed over already fetched and {@link #fetch} is never called. The crawler's
 * conditional requests use the framework's {@link DiscoveryContext#knownVersion
 * known versions}, so an unchanged page costs a 304 and arrives as an
 * {@link WorkItem.Action#UNCHANGED} item.
 *
 * <p>
 * A thin adapter on purpose: {@link WebCrawler} and its {@link CrawlSink}
 * contract are unchanged, and so are their tests.
 */
@ApplicationScoped
public class WebSourceConnector implements SourceConnector {

    /** Logged when a crawl reports coverage its sitemaps did not deliver. */
    static final String INCOMPLETE_SITEMAPS = "incomplete sitemap discovery";

    private final WebCrawler crawler;

    @Inject
    public WebSourceConnector(WebCrawler crawler) {
        this.crawler = crawler;
    }

    @Override
    public String type() {
        return IngestionSource.TYPE_WEB;
    }

    @Override
    public DiscoveryResult discover(DiscoveryContext context) {
        Partition partition = context.partition();
        WebCrawler.CrawlSummary summary = crawler.crawl(toCrawlRequest(context.source().source()),
                new ContextSink(context, partition));
        // A crawl infers coverage from what it reached; the framework adds the
        // "learned something definitive" guard for FULL_TRAVERSAL.
        Coverage coverage = summary.coveredWholeSource() ? Coverage.FULL_TRAVERSAL : Coverage.PARTIAL;
        return new DiscoveryResult(StopReason.valueOf(summary.stopReason().name()), coverage,
                summary.discoveryIncomplete() ? INCOMPLETE_SITEMAPS : null, null);
    }

    /**
     * Never called: every page arrives prefetched.
     */
    @Override
    public SourceDocument fetch(WorkItem item, SourceContext context) throws SourceException {
        throw new SourceException(new SourceError.Rejected(
                "A web source delivers every page while crawling; there is nothing to fetch separately"));
    }

    /**
     * Classifies a crawl error by what it says about the page, preserving the
     * crawler's own {@link CrawlSink.CrawlError#contentUnknown()} verdict: a
     * definitive kind exactly when the crawler said the content is known.
     */
    static SourceError classify(CrawlSink.CrawlError error) {
        int status = error.statusCode();
        String reason = error.reason();
        if (!error.contentUnknown()) {
            return status == 404 || status == 410
                    ? new SourceError.NotFound(reason)
                    : new SourceError.Permanent(reason);
        }
        if (status == 429) {
            return new SourceError.Throttled(reason);
        }
        if (status == 401 || status == 403) {
            return new SourceError.Auth(reason);
        }
        return new SourceError.Transient(reason);
    }

    /** One crawled page as a document. */
    static SourceDocument toDocument(CrawlSink.CrawledPage page) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(SourceMetadataKeys.TITLE, page.title());
        metadata.put(SourceMetadataKeys.CITATION_URL, page.finalUrl());
        metadata.put(SourceMetadataKeys.MIME_TYPE, "text/html");
        metadata.put(SourceMetadataKeys.FORMAT, "html");
        metadata.put(SourceMetadataKeys.PROVIDER, IngestionSource.TYPE_WEB);
        // No version token: an ETag is not reliably tied to the content, so the
        // hash of the converted text decides whether the page changed.
        return new SourceDocument(page.documentId(), new Content.HtmlPage(page.html()), null,
                new ResponseValidators(page.etag(), page.lastModified()), page.truncated(), metadata);
    }

    static CrawlRequest toCrawlRequest(IngestionSource source) {
        IngestionSource.WebSource web = source.getWeb();
        IngestionSource.IngestionSettings settings = source.settings();

        var scope = new CrawlRequest.Scope(
                web.isSameSiteOnly(),
                web.isIncludeSubdomains(),
                web.getPathPrefix(),
                web.getMaxDepth() == null ? 3 : web.getMaxDepth(),
                web.getExcludePatterns());

        int maxPages = web.getMaxPages() == null ? 200 : web.getMaxPages();
        var limits = new CrawlRequest.Limits(
                maxPages,
                0, // derived from maxPages
                settings.maxBytesPerPageOrDefault(),
                0, // default total budget
                Duration.ofMinutes(settings.timeBudgetMinutesOrDefault()),
                Duration.ofSeconds(web.getTimeoutSeconds() == null ? 15 : web.getTimeoutSeconds()));

        var politeness = new CrawlRequest.Politeness(
                Duration.ofMillis(web.getRequestDelayMs() == null ? 500 : web.getRequestDelayMs()),
                web.getUserAgent(),
                web.isRespectRobots());

        return new CrawlRequest(web.getStartUrl(), scope, limits, politeness, web.getSitemapUrls());
    }

    /** The crawler's callbacks, forwarded to the framework one for one. */
    private record ContextSink(DiscoveryContext context, Partition partition) implements CrawlSink {

        @Override
        public ConditionalHeaders conditionalFor(String documentId) {
            return context.knownVersion(documentId)
                    .map(known -> new ConditionalHeaders(known.etag(), known.lastModified()))
                    .orElseGet(ConditionalHeaders::none);
        }

        @Override
        public void onPage(CrawledPage page) {
            context.accept(WorkItem.prefetched(toDocument(page), partition));
        }

        @Override
        public void onUnchanged(String documentId) {
            context.accept(WorkItem.unchanged(documentId, partition));
        }

        @Override
        public void onError(CrawlError error) {
            context.reportFailure(error.documentId(), error.url(), classify(error));
        }

        @Override
        public boolean isCancelled() {
            return context.isCancelled();
        }
    }
}
