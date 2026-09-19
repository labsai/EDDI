/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import ai.labs.eddi.modules.ingestion.crawl.CrawlSink.ConditionalHeaders;
import ai.labs.eddi.modules.ingestion.crawl.CrawlSink.CrawlError;
import ai.labs.eddi.modules.ingestion.crawl.CrawlSink.CrawledPage;
import ai.labs.eddi.modules.ingestion.crawl.PageFetcher.FetchCommand;
import ai.labs.eddi.modules.ingestion.crawl.PageFetcher.FetchedPage;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import org.jsoup.parser.Parser;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

/**
 * Breadth-first web crawler for knowledge-base ingestion.
 *
 * <p>
 * Streams pages to a {@link CrawlSink} as it finds them, stays inside a scope
 * and a set of budgets, honours {@code robots.txt}, and identifies each page by
 * its canonical URL after redirects. All the HTTP goes through an injected
 * {@link PageFetcher}, so every decision below is reachable in a unit test
 * without a network.
 *
 * <h2>Blocking</h2>
 * <p>
 * This blocks: it sleeps between requests to be polite and waits on each fetch.
 * Run it on its own thread — a virtual thread is ideal — never on a request
 * thread or a shared pool. A 200-page crawl at the default delay takes minutes
 * by design.
 *
 * <h2>Conditional requests and link discovery</h2>
 * <p>
 * When the sink supplies an ETag or Last-Modified for a document, the fetch is
 * conditional and an unchanged page comes back as a 304 with no body. That
 * saves the download but means the page's links are not re-read on that run, so
 * a new page linked <em>only</em> from an unchanged page is discovered on the
 * next run that sees the parent change — or immediately, if the site publishes
 * a sitemap, which is why sitemap seeding is preferred over relying on link
 * discovery alone.
 */
@ApplicationScoped
public class WebCrawler {

    private static final Logger LOGGER = Logger.getLogger(WebCrawler.class);

    /**
     * Cap on URLs taken from sitemaps, so a huge sitemap cannot swamp the queue.
     */
    private static final int MAX_SITEMAP_URLS = 5_000;

    /** Cap on the robots.txt and sitemap bodies. */
    private static final long MAX_METADATA_BYTES = 1024L * 1024;

    private final PageFetcher fetcher;

    @Inject
    public WebCrawler(PageFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Crawls from the request's seed, handing each page to the sink.
     *
     * @return what happened, including why the crawl stopped
     */
    public CrawlSummary crawl(CrawlRequest request, CrawlSink sink) {
        Instant start = Instant.now();
        Counters counters = new Counters();

        if (!CrawlUrls.isHttpScheme(request.seedUrl())) {
            sink.onError(new CrawlError(request.seedUrl(), "Seed URL must be http or https", 0));
            counters.errors++;
            return counters.summarize(start, StopReason.COMPLETED);
        }

        String seedHost = CrawlUrls.host(request.seedUrl()).orElse(null);
        if (seedHost == null) {
            sink.onError(new CrawlError(request.seedUrl(), "Seed URL has no host", 0));
            counters.errors++;
            return counters.summarize(start, StopReason.COMPLETED);
        }

        List<UrlPattern> excludes = UrlPattern.compileAll(request.scope().excludePatterns());
        // Keyed by host: with sameSiteOnly off or subdomains included a crawl reaches
        // several hosts, and applying the seed's robots.txt to all of them means
        // obeying one site's rules while ignoring another's.
        Map<String, RobotsPolicy> robotsByHost = new HashMap<>();
        RobotsPolicy robots = request.politeness().respectRobots()
                ? robotsFor(request, request.seedUrl(), robotsByHost)
                : RobotsPolicy.allowAll();
        Duration delay = effectiveDelay(request, robots);

        Set<String> visited = new HashSet<>();
        // Separate from `visited`: a URL is queued long before it is polled, and
        // without this a page linked from 200 others is enqueued 200 times. At
        // maxPages 50,000 with typical navigation that is millions of entries.
        Set<String> queued = new HashSet<>();
        Queue<Candidate> queue = new ArrayDeque<>();
        enqueueSeeds(request, robots, queue, queued, seedHost, excludes);

        Instant deadline = start.plus(request.limits().timeBudget());
        StopReason stopReason = StopReason.COMPLETED;
        boolean firstRequest = true;

        while (!queue.isEmpty()) {
            if (sink.isCancelled() || Thread.currentThread().isInterrupted()) {
                stopReason = StopReason.CANCELLED;
                break;
            }
            if (counters.pagesFetched >= request.limits().maxPages()) {
                stopReason = StopReason.PAGE_LIMIT;
                break;
            }
            if (counters.fetchAttempts >= request.limits().maxFetchAttempts()) {
                stopReason = StopReason.FETCH_LIMIT;
                break;
            }
            if (counters.bytesDownloaded >= request.limits().maxTotalBytes()) {
                stopReason = StopReason.BYTE_LIMIT;
                break;
            }
            if (Instant.now().isAfter(deadline)) {
                stopReason = StopReason.TIME_LIMIT;
                break;
            }

            Candidate candidate = queue.poll();
            RobotsPolicy candidateRobots = request.politeness().respectRobots()
                    ? robotsFor(request, candidate.fetchUrl(), robotsByHost)
                    : robots;
            // Marked before the fetch, not after it. Recording only successes meant a
            // dead link in a site-wide footer was fetched once per referring page:
            // 200 requests, 200 errors, and a fetch budget exhausted so completely
            // that the crawl never reported full coverage — which silently disabled
            // deletion reconciliation for that site on every run.
            if (!visited.add(candidate.canonicalId())) {
                continue;
            }
            if (request.politeness().respectRobots()
                    && !candidateRobots.isAllowed(CrawlUrls.path(candidate.fetchUrl()))) {
                counters.skipped++;
                continue;
            }

            if (!firstRequest && !delay.isZero()) {
                try {
                    Thread.sleep(delay.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    stopReason = StopReason.CANCELLED;
                    break;
                }
            }
            firstRequest = false;

            processCandidate(request, sink, candidate, seedHost, excludes, queue, queued, visited, counters);
        }

        CrawlSummary summary = counters.summarize(start, stopReason);
        LOGGER.debugf("Crawl of %s finished: %s", LogSanitizer.sanitize(request.seedUrl()), summary);
        return summary;
    }

    private void processCandidate(CrawlRequest request, CrawlSink sink, Candidate candidate, String seedHost,
                                  List<UrlPattern> excludes, Queue<Candidate> queue, Set<String> queued, Set<String> visited,
                                  Counters counters) {

        ConditionalHeaders conditional = sink.conditionalFor(candidate.canonicalId());
        FetchCommand command = new FetchCommand(
                candidate.fetchUrl(),
                request.politeness().userAgent(),
                request.limits().requestTimeout(),
                conditional.etag(),
                conditional.lastModified(),
                request.limits().maxBytesPerPage());

        FetchedPage page;
        try {
            counters.fetchAttempts++;
            page = fetcher.fetch(command);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (IOException | RuntimeException e) {
            counters.errors++;
            sink.onError(new CrawlError(candidate.fetchUrl(), describe(e), 0));
            return;
        }

        counters.bytesDownloaded += page.body() == null ? 0 : page.body().length;

        if (page.isNotModified()) {
            counters.unchanged++;
            visited.add(candidate.canonicalId());
            sink.onUnchanged(candidate.canonicalId());
            return;
        }
        if (!page.isOk()) {
            counters.errors++;
            sink.onError(new CrawlError(candidate.fetchUrl(), "HTTP " + page.statusCode(), page.statusCode()));
            return;
        }
        if (!page.isHtml()) {
            // Not an error: a documentation site legitimately links PDFs and images.
            // Counted so an operator can see why a crawl found fewer pages than links.
            counters.skipped++;
            return;
        }

        // The page's identity is where it ended up, not where we asked. Using the
        // requested URL makes /docs and /docs/ two documents with identical content
        // and resolves relative links against the wrong base.
        String finalUrl = page.finalUrl() == null ? candidate.fetchUrl() : page.finalUrl();
        Document document = parse(page, finalUrl);
        if (document == null) {
            counters.errors++;
            sink.onError(new CrawlError(candidate.fetchUrl(), "Could not parse response as HTML", page.statusCode()));
            return;
        }

        String documentId = CrawlUrls.canonicalize(canonicalUrlOf(document, finalUrl));

        // A redirect can leave the scope the operator asked for; re-check rather
        // than trusting the pre-redirect decision.
        if (!isInScope(documentId, seedHost, request, excludes)) {
            counters.skipped++;
            return;
        }
        // The requested URL was marked visited when it was polled, so only a
        // documentId that DIFFERS from it — a redirect or a canonical link landing on
        // a page another URL already produced — can be a duplicate here.
        if (!documentId.equals(candidate.canonicalId()) && !visited.add(documentId)) {
            counters.skipped++;
            return;
        }
        visited.add(documentId);

        counters.pagesFetched++;
        sink.onPage(new CrawledPage(documentId, finalUrl, document.title(), document.outerHtml(),
                page.etag(), page.lastModified(), candidate.depth(), page.truncated()));

        if (candidate.depth() < request.scope().maxDepth()) {
            enqueueLinks(document, candidate.depth() + 1, seedHost, request, excludes, queue, queued, visited);
        }
    }

    private void enqueueLinks(Document document, int depth, String seedHost, CrawlRequest request,
                              List<UrlPattern> excludes, Queue<Candidate> queue, Set<String> queued, Set<String> visited) {

        for (Element link : document.select("a[href]")) {
            String href = link.attr("abs:href");
            if (href == null || href.isBlank()) {
                continue;
            }
            // rel=nofollow is the site asking not to be crawled through this link.
            if (link.attr("rel").toLowerCase().contains("nofollow")) {
                continue;
            }
            String canonical = CrawlUrls.canonicalize(href);
            if (canonical.isEmpty() || visited.contains(canonical) || queued.contains(canonical)) {
                continue;
            }
            if (!isInScope(canonical, seedHost, request, excludes)) {
                continue;
            }
            queued.add(canonical);
            queue.add(new Candidate(CrawlUrls.stripFragment(href), canonical, depth));
        }
    }

    private void enqueueSeeds(CrawlRequest request, RobotsPolicy robots, Queue<Candidate> queue,
                              Set<String> queued, String seedHost, List<UrlPattern> excludes) {

        queue.add(new Candidate(CrawlUrls.stripFragment(request.seedUrl()),
                CrawlUrls.canonicalize(request.seedUrl()), 0));

        // Sitemaps are the cheapest and most reliable discovery there is: the site
        // lists its own pages, so nothing depends on link structure or on a page
        // having changed since the last run.
        for (String sitemapUrl : robots.sitemaps()) {
            for (String url : fetchSitemapUrls(sitemapUrl, request)) {
                String canonical = CrawlUrls.canonicalize(url);
                // A sitemap is written by the site, not by the operator, and may list
                // anything at all — so it earns no exemption from the scope.
                if (!canonical.isEmpty() && queued.add(canonical)
                        && isInScope(canonical, seedHost, request, excludes)) {
                    queue.add(new Candidate(CrawlUrls.stripFragment(url), canonical, 0));
                }
            }
        }
    }

    private boolean isInScope(String url, String seedHost, CrawlRequest request, List<UrlPattern> excludes) {
        if (!CrawlUrls.isHttpScheme(url)) {
            return false;
        }
        if (request.scope().sameSiteOnly()) {
            String host = CrawlUrls.host(url).orElse(null);
            if (!CrawlUrls.isSameSite(host, seedHost, request.scope().includeSubdomains())) {
                return false;
            }
        }
        String path = CrawlUrls.path(url);
        if (!CrawlUrls.isUnderPathPrefix(path, request.scope().pathPrefix())) {
            return false;
        }
        return !UrlPattern.anyMatches(excludes, path);
    }

    /**
     * {@code <link rel="canonical">} is the site telling us which URL is the real
     * one. Honoured only when it stays on the same host, so a third-party canonical
     * cannot redirect a document's identity off-site.
     */
    private static String canonicalUrlOf(Document document, String fallbackUrl) {
        Element canonical = document.selectFirst("link[rel=canonical][href]");
        if (canonical == null) {
            return fallbackUrl;
        }
        String href = canonical.attr("abs:href");
        if (href == null || href.isBlank() || !CrawlUrls.isHttpScheme(href)) {
            return fallbackUrl;
        }
        String canonicalHost = CrawlUrls.host(href).orElse(null);
        String actualHost = CrawlUrls.host(fallbackUrl).orElse(null);
        return canonicalHost != null && canonicalHost.equals(actualHost) ? href : fallbackUrl;
    }

    /**
     * Parsed from bytes with the declared charset, letting jsoup fall back to the
     * document's own {@code <meta charset>}. Decoding as UTF-8 regardless — which
     * is what reading the body as a String does — turns legacy pages into mojibake,
     * and mojibake embeds without complaint.
     */
    private static Document parse(FetchedPage page, String baseUri) {
        byte[] body = page.body();
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            return Jsoup.parse(new ByteArrayInputStream(body), page.declaredCharset(), baseUri);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The robots policy for a URL's host, fetched once per host per crawl.
     */
    private RobotsPolicy robotsFor(CrawlRequest request, String url, Map<String, RobotsPolicy> cache) {
        String host = CrawlUrls.host(url).orElse(null);
        if (host == null) {
            return RobotsPolicy.allowAll();
        }
        return cache.computeIfAbsent(host, ignored -> fetchRobots(request, url));
    }

    private RobotsPolicy fetchRobots(CrawlRequest request, String forUrl) {
        String robotsUrl = robotsUrlFor(forUrl);
        if (robotsUrl == null) {
            return RobotsPolicy.allowAll();
        }
        try {
            FetchedPage page = fetcher.fetch(new FetchCommand(robotsUrl, request.politeness().userAgent(),
                    request.limits().requestTimeout(), null, null, MAX_METADATA_BYTES));
            if (!page.isOk() || page.body() == null) {
                // No robots.txt, or it could not be read: absence means permission. A
                // 500 from a robots endpoint must not silently halt an operator's
                // ingestion.
                return RobotsPolicy.allowAll();
            }
            return RobotsPolicy.parse(new String(page.body(), StandardCharsets.UTF_8),
                    request.politeness().userAgent());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RobotsPolicy.allowAll();
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf("Could not read robots.txt from %s: %s",
                    LogSanitizer.sanitize(robotsUrl), LogSanitizer.sanitize(describe(e)));
            return RobotsPolicy.allowAll();
        }
    }

    private List<String> fetchSitemapUrls(String sitemapUrl, CrawlRequest request) {
        try {
            FetchedPage page = fetcher.fetch(new FetchCommand(sitemapUrl, request.politeness().userAgent(),
                    request.limits().requestTimeout(), null, null, MAX_METADATA_BYTES));
            if (!page.isOk() || page.body() == null || page.body().length == 0) {
                return List.of();
            }
            Document sitemap = Jsoup.parse(new ByteArrayInputStream(page.body()), page.declaredCharset(),
                    sitemapUrl, Parser.xmlParser());
            Set<String> urls = new LinkedHashSet<>();
            for (Element location : sitemap.select("loc")) {
                String url = location.text().trim();
                if (!url.isEmpty()) {
                    urls.add(url);
                }
                if (urls.size() >= MAX_SITEMAP_URLS) {
                    break;
                }
            }
            return List.copyOf(urls);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf("Could not read sitemap %s: %s",
                    LogSanitizer.sanitize(sitemapUrl), LogSanitizer.sanitize(describe(e)));
            return List.of();
        }
    }

    private static String robotsUrlFor(String seedUrl) {
        try {
            URI uri = new URI(seedUrl);
            if (uri.getHost() == null) {
                return null;
            }
            URI robots = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(),
                    "/robots.txt", null, null);
            return robots.toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** The site's asked-for rate wins when it is slower than ours. */
    private static Duration effectiveDelay(CrawlRequest request, RobotsPolicy robots) {
        Duration configured = request.politeness().requestDelay();
        return robots.crawlDelay()
                .filter(fromRobots -> fromRobots.compareTo(configured) > 0)
                .orElse(configured);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    /**
     * A URL waiting to be crawled.
     *
     * @param fetchUrl
     *            the address as the site published it. Fetching the canonical form
     *            instead would invent a URL the site never offered — dropping a
     *            trailing slash, for example, on a server that does not redirect.
     * @param canonicalId
     *            the identity used for the visited set, so two spellings of one
     *            page are not both crawled
     */
    private record Candidate(String fetchUrl, String canonicalId, int depth) {
    }

    /** Why a crawl stopped. */
    public enum StopReason {
        /** The queue emptied — the whole scope was covered. */
        COMPLETED, PAGE_LIMIT, FETCH_LIMIT, BYTE_LIMIT, TIME_LIMIT, CANCELLED
    }

    /**
     * What a crawl did.
     *
     * @param stopReason
     *            whether the crawl covered the source or ran out of budget. The
     *            caller needs this before concluding that an unseen document has
     *            been deleted: a crawl that hit its page limit saw an arbitrary
     *            subset.
     */
    public record CrawlSummary(
            int pagesFetched,
            int pagesUnchanged,
            int pagesSkipped,
            int errors,
            int fetchAttempts,
            long bytesDownloaded,
            Duration duration,
            StopReason stopReason) {

        /**
         * Whether the crawl covered its whole scope, so absence means deletion.
         *
         * <p>
         * A crawl that failed on every page it tried also ends as {@code COMPLETED}: an
         * unreachable seed leaves nothing queued. Counting that as coverage would have
         * an outage report every document as gone. Errors alongside pages that did
         * arrive still count — a dead link on a live site must not block deletion
         * forever — and the store's missed-runs threshold absorbs a transient failure.
         */
        public boolean coveredWholeSource() {
            boolean reachedTheSource = errors == 0 || pagesFetched + pagesUnchanged > 0;
            return stopReason == StopReason.COMPLETED && reachedTheSource;
        }
    }

    private static final class Counters {
        private int pagesFetched;
        private int unchanged;
        private int skipped;
        private int errors;
        private int fetchAttempts;
        private long bytesDownloaded;

        CrawlSummary summarize(Instant start, StopReason stopReason) {
            return new CrawlSummary(pagesFetched, unchanged, skipped, errors, fetchAttempts, bytesDownloaded,
                    Duration.between(start, Instant.now()), stopReason);
        }
    }
}
