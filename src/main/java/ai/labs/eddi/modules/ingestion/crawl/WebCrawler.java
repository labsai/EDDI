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
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.zip.GZIPInputStream;

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
     * Cap on page URLs taken from sitemaps, across every sitemap one crawl reads,
     * so a huge sitemap (or an index of many) cannot swamp the queue.
     */
    private static final int MAX_SITEMAP_URLS = 5_000;

    /**
     * Cap on sitemaps read per crawl — configured ones, those robots.txt lists, and
     * the children of any sitemap index among them. All three are the site's to
     * write and may run to thousands; each is a request made before the first page
     * is fetched.
     */
    private static final int MAX_SITEMAPS = 20;

    /**
     * Hard ceiling on the frontier. Reached only by a site whose URL space is
     * generated rather than authored; past it, a crawl is collecting URLs it can
     * never fetch within any budget an operator can configure.
     */
    private static final int MAX_QUEUED_URLS = 100_000;

    /** Cap on the robots.txt and sitemap bodies. */
    private static final long MAX_METADATA_BYTES = 1024L * 1024;

    /**
     * Cap on a gzipped sitemap once decompressed. Well past what
     * {@link #MAX_SITEMAP_URLS} URLs take, and far short of what a small compressed
     * body can expand to.
     */
    static final int MAX_DECOMPRESSED_SITEMAP_BYTES = 16 * 1024 * 1024;

    /**
     * U+FEFF, as a number: the formatter turns a unicode escape into the raw
     * character.
     */
    private static final char BYTE_ORDER_MARK = (char) 0xFEFF;

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
            sink.onError(new CrawlError(null, request.seedUrl(), "Seed URL must be http or https", 0, true));
            counters.errors++;
            counters.unreachable++;
            return counters.summarize(start, StopReason.COMPLETED);
        }

        String seedHost = CrawlUrls.host(request.seedUrl()).orElse(null);
        if (seedHost == null) {
            sink.onError(new CrawlError(null, request.seedUrl(), "Seed URL has no host", 0, true));
            counters.errors++;
            counters.unreachable++;
            return counters.summarize(start, StopReason.COMPLETED);
        }

        List<UrlPattern> excludes = UrlPattern.compileAll(request.scope().excludePatterns());
        // Keyed by host: with sameSiteOnly off or subdomains included a crawl reaches
        // several hosts, and applying the seed's robots.txt to all of them means
        // obeying one site's rules while ignoring another's.
        Map<String, RobotsPolicy> robotsByHost = new HashMap<>();
        Instant deadline = start.plus(request.limits().timeBudget());
        // The seed's robots.txt is a request like any other, so it answers to the
        // same checks — made before it, not only once the loop starts.
        StopReason beforeStart = limitReached(request, sink, counters, deadline);
        if (beforeStart != null) {
            return counters.summarize(start, beforeStart);
        }
        RobotsPolicy robots = request.politeness().respectRobots()
                ? robotsFor(request, request.seedUrl(), robotsByHost, counters)
                : RobotsPolicy.allowAll();
        Duration delay = effectiveDelay(request, robots);

        Set<String> visited = new HashSet<>();
        // Separate from `visited`: a URL is queued long before it is polled, and
        // without this a page linked from 200 others is enqueued 200 times. At
        // maxPages 50,000 with typical navigation that is millions of entries.
        Set<String> queued = new HashSet<>();
        Queue<Candidate> queue = new ArrayDeque<>();
        enqueueSeeds(request, sink, robots, delay, deadline, counters, queue, queued, seedHost, excludes);

        StopReason stopReason = StopReason.COMPLETED;
        // robots.txt and sitemap requests count: the seed page waits out the delay
        // after them like any other request to the same host.
        boolean firstRequest = counters.fetchAttempts == 0;

        while (!queue.isEmpty()) {
            StopReason limit = limitReached(request, sink, counters, deadline);
            if (limit != null) {
                stopReason = limit;
                break;
            }

            Candidate candidate = queue.poll();
            RobotsPolicy candidateRobots = request.politeness().respectRobots()
                    ? robotsFor(request, candidate.fetchUrl(), robotsByHost, counters)
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
                    && !candidateRobots.isAllowed(CrawlUrls.pathAndQuery(candidate.fetchUrl()))) {
                counters.skipped++;
                continue;
            }

            // The candidate host's Crawl-delay, not the seed's: a crawl that reaches a
            // second host would otherwise ignore the one directive that keeps it from
            // being blocked there.
            Duration candidateDelay = effectiveDelay(request, candidateRobots);
            if (!firstRequest && !pause(candidateDelay)) {
                stopReason = StopReason.CANCELLED;
                break;
            }
            firstRequest = false;

            // A new host's robots.txt, fetched above, may have spent the last of a
            // budget, and the wait may have run past the deadline. Checked again so
            // the page fetch cannot overshoot either.
            StopReason spent = limitReached(request, sink, counters, deadline);
            if (spent != null) {
                stopReason = spent;
                break;
            }

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
            counters.unreachable++;
            // A transport failure says nothing about the page: it was never read.
            sink.onError(new CrawlError(candidate.canonicalId(), candidate.fetchUrl(), describe(e), 0, true));
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
            if (saysNothingAboutContent(page.statusCode())) {
                counters.unreachable++;
            }
            sink.onError(new CrawlError(candidate.canonicalId(), candidate.fetchUrl(),
                    "HTTP " + page.statusCode(), page.statusCode(), saysNothingAboutContent(page.statusCode())));
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
            // The server answered, but nothing could be made of it. That is a fact
            // about this response, not evidence that the page is gone.
            sink.onError(new CrawlError(candidate.canonicalId(), candidate.fetchUrl(),
                    "Could not parse response as HTML", page.statusCode(), true));
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
                              List<UrlPattern> excludes, Queue<Candidate> queue, Set<String> queued,
                              Set<String> visited) {

        for (Element link : document.select("a[href]")) {
            if (!hasQueueRoom(queue)) {
                // Deduplication bounds repeats, not breadth. Every distinct in-scope
                // URL used to be retained however many could actually be fetched, and
                // a site with sort/filter/page parameters has an unbounded URL space.
                // Nothing past the remaining fetch budget can ever be polled.
                break;
            }
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

    /**
     * Whether the frontier may still grow.
     *
     * <p>
     * Deduplication bounds repeats, not breadth: every distinct in-scope URL is
     * remembered, and a site with sort, filter and page parameters has an
     * effectively unbounded URL space, so both the queue and the {@code queued} set
     * grow with the site rather than with the budget.
     *
     * <p>
     * Bounded by an absolute cap rather than by the remaining fetch budget: a
     * queued candidate does not always cost a fetch — it may turn out to be a
     * duplicate or robots-disallowed when it is polled — so tying the frontier to
     * the budget starves a crawl that would otherwise finish.
     */
    private static boolean hasQueueRoom(Queue<Candidate> queue) {
        return queue.size() < MAX_QUEUED_URLS;
    }

    /**
     * Queues the seed and whatever the site's sitemaps list.
     */
    private void enqueueSeeds(CrawlRequest request, CrawlSink sink, RobotsPolicy robots, Duration delay,
                              Instant deadline, Counters counters, Queue<Candidate> queue, Set<String> queued,
                              String seedHost, List<UrlPattern> excludes) {

        queue.add(new Candidate(CrawlUrls.stripFragment(request.seedUrl()),
                CrawlUrls.canonicalize(request.seedUrl()), 0));

        // Sitemaps are the cheapest and most reliable discovery there is: the site
        // lists its own pages, so nothing depends on link structure or on a page
        // having changed since the last run. They are requests all the same, made
        // before the crawl loop's own checks run, so they answer to the same
        // budgets, cancellation and politeness here.
        //
        // Configured sitemaps come first: the operator named them, while robots.txt
        // may list many. A sitemap index lists further sitemaps rather than pages;
        // those join the same queue and the same MAX_SITEMAPS budget, so an index
        // of indexes cannot multiply the requests made before the first page.
        Queue<String> sitemapQueue = new ArrayDeque<>();
        Set<String> sitemapsSeen = new HashSet<>();
        for (String sitemapUrl : request.sitemapUrls()) {
            if (sitemapsSeen.add(sitemapUrl)) {
                sitemapQueue.add(sitemapUrl);
            }
        }
        String robotsUrl = robotsUrlFor(request.seedUrl());
        for (String advertised : robots.sitemaps()) {
            // The protocol asks for absolute URLs, but a relative "Sitemap: /sitemap.xml"
            // is common enough to honour — resolved against robots.txt, as a browser would.
            String sitemapUrl = resolve(robotsUrl, advertised);
            if (sitemapUrl != null && sitemapsSeen.add(sitemapUrl)) {
                sitemapQueue.add(sitemapUrl);
            }
        }
        // Nothing configured and nothing advertised: try the conventional location.
        // Plenty of sites publish /sitemap.xml without listing it in robots.txt, and
        // the cost where there is none is a single 404.
        if (sitemapQueue.isEmpty() && robotsUrl != null) {
            String conventional = resolve(robotsUrl, "/sitemap.xml");
            if (conventional != null && sitemapsSeen.add(conventional)) {
                sitemapQueue.add(conventional);
            }
        }

        int sitemapsRead = 0;
        int pagesFromSitemaps = 0;
        while (!sitemapQueue.isEmpty()) {
            if (sitemapsRead >= MAX_SITEMAPS
                    || limitReached(request, sink, counters, deadline) != null
                    || !pause(delay)) {
                break;
            }
            sitemapsRead++;
            String sitemapUrl = sitemapQueue.poll();
            Sitemap sitemap = fetchSitemap(sitemapUrl, request, counters);
            if (!sitemap.complete()) {
                counters.discoveryIncomplete = true;
            }
            String indexHost = CrawlUrls.host(sitemapUrl).orElse(null);
            for (String child : sitemap.childSitemaps()) {
                // The protocol's own rule: an index lists sitemaps on its own host.
                // Without it, any site the crawl reads could have it fetch sitemaps on
                // any public host — SSRF protection stops only private ones. Not the
                // page scope: a source scoped to /docs/ has its sitemaps outside it.
                // Configured and robots.txt sitemaps may be cross-host; the protocol
                // allows that through robots.txt, and the operator chose the others.
                String childHost = CrawlUrls.host(child).orElse(null);
                if (indexHost != null && indexHost.equalsIgnoreCase(childHost) && sitemapsSeen.add(child)) {
                    sitemapQueue.add(child);
                }
            }
            for (String url : sitemap.pageUrls()) {
                if (pagesFromSitemaps >= MAX_SITEMAP_URLS) {
                    counters.discoveryIncomplete = true;
                    break;
                }
                String canonical = CrawlUrls.canonicalize(url);
                // A sitemap is written by the site, not by the operator, and may list
                // anything at all — so it earns no exemption from the scope.
                if (!canonical.isEmpty() && queued.add(canonical)
                        && isInScope(canonical, seedHost, request, excludes)) {
                    queue.add(new Candidate(CrawlUrls.stripFragment(url), canonical, 0));
                    pagesFromSitemaps++;
                }
            }
        }
        // Sitemaps left unread — the budget, a limit or a cancellation stopped the
        // loop — list pages the crawl never learned of. Without saying so, a crawl
        // that then finished its queue reported the source covered, and deletion
        // reconciliation removed pages that only the unread sitemaps listed.
        if (!sitemapQueue.isEmpty()) {
            counters.discoveryIncomplete = true;
        }
    }

    /**
     * The limit that stops the crawl before its next request, or null to go on. One
     * definition for every request — pages, robots.txt and sitemaps — so no kind of
     * request can slip past a budget the others respect.
     */
    private static StopReason limitReached(CrawlRequest request, CrawlSink sink, Counters counters,
                                           Instant deadline) {
        if (sink.isCancelled() || Thread.currentThread().isInterrupted()) {
            return StopReason.CANCELLED;
        }
        if (counters.pagesFetched >= request.limits().maxPages()) {
            return StopReason.PAGE_LIMIT;
        }
        if (counters.fetchAttempts >= request.limits().maxFetchAttempts()) {
            return StopReason.FETCH_LIMIT;
        }
        if (counters.bytesDownloaded >= request.limits().maxTotalBytes()) {
            return StopReason.BYTE_LIMIT;
        }
        if (Instant.now().isAfter(deadline)) {
            return StopReason.TIME_LIMIT;
        }
        return null;
    }

    /**
     * Waits out a politeness delay.
     *
     * @return false if the wait was interrupted, which means the crawl is being
     *         cancelled
     */
    private static boolean pause(Duration delay) {
        if (delay.isZero() || delay.isNegative()) {
            return true;
        }
        try {
            Thread.sleep(delay.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
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
    private RobotsPolicy robotsFor(CrawlRequest request, String url, Map<String, RobotsPolicy> cache,
                                   Counters counters) {
        String host = CrawlUrls.host(url).orElse(null);
        if (host == null) {
            return RobotsPolicy.allowAll();
        }
        return cache.computeIfAbsent(host, ignored -> fetchRobots(request, url, counters));
    }

    /**
     * Counted against the fetch and byte budgets like any other request. It runs
     * once per host: for the seed before the loop, otherwise inside it after the
     * loop's cancellation, deadline and budget checks.
     */
    private RobotsPolicy fetchRobots(CrawlRequest request, String forUrl, Counters counters) {
        String robotsUrl = robotsUrlFor(forUrl);
        if (robotsUrl == null) {
            return RobotsPolicy.allowAll();
        }
        try {
            counters.fetchAttempts++;
            FetchedPage page = fetcher.fetch(new FetchCommand(robotsUrl, request.politeness().userAgent(),
                    request.limits().requestTimeout(), null, null, MAX_METADATA_BYTES));
            counters.bytesDownloaded += page.body() == null ? 0 : page.body().length;
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

    /**
     * What one sitemap lists: pages, or — for a sitemap index — further sitemaps.
     */
    /**
     * @param complete
     *            false when this sitemap may list more than was read — it was cut
     *            at a cap, or could not be read for a reason that says nothing
     *            about what it lists. A crawl that relied on it has not seen the
     *            whole source.
     */
    record Sitemap(List<String> pageUrls, List<String> childSitemaps, boolean complete) {
        /** No sitemap there — a definite answer, as a 404 is. */
        static final Sitemap EMPTY = new Sitemap(List.of(), List.of(), true);
        /**
         * A sitemap that could not be read: an outage, a refusal, a body that would not
         * parse.
         */
        static final Sitemap UNREAD = new Sitemap(List.of(), List.of(), false);
    }

    private Sitemap fetchSitemap(String sitemapUrl, CrawlRequest request, Counters counters) {
        try {
            counters.fetchAttempts++;
            FetchedPage page = fetcher.fetch(new FetchCommand(sitemapUrl, request.politeness().userAgent(),
                    request.limits().requestTimeout(), null, null, MAX_METADATA_BYTES));
            counters.bytesDownloaded += page.body() == null ? 0 : page.body().length;
            if (!page.isOk()) {
                // A 404 or 410 answers that there is no such sitemap; an outage, a rate
                // limit or a refusal answers nothing about what it lists.
                return saysNothingAboutContent(page.statusCode()) ? Sitemap.UNREAD : Sitemap.EMPTY;
            }
            if (page.body() == null || page.body().length == 0) {
                return Sitemap.EMPTY;
            }
            Sitemap sitemap = parseSitemap(page.body(), page.declaredCharset(), sitemapUrl);
            // Cut at the fetch cap: what was read is still used, but the rest may list
            // pages the crawl never queued.
            return page.truncated() ? new Sitemap(sitemap.pageUrls(), sitemap.childSitemaps(), false) : sitemap;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Sitemap.UNREAD;
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf("Could not read sitemap %s: %s",
                    LogSanitizer.sanitize(sitemapUrl), LogSanitizer.sanitize(describe(e)));
            return Sitemap.UNREAD;
        }
    }

    /**
     * Reads every form the sitemap protocol allows, and the feeds sites commonly
     * publish in its place.
     * <ul>
     * <li>{@code <urlset>} — pages, from {@code url/loc}. The {@code loc} of the
     * image, video and news extensions sits under its own element and is not a
     * page, so only a {@code loc} whose parent is {@code url} counts.</li>
     * <li>{@code <sitemapindex>} — further sitemaps, from {@code sitemap/loc}.
     * Taking every {@code <loc>} as a page — which this did — queued an index's
     * child sitemaps as pages; the fetcher refuses XML, so on a site publishing an
     * index, which is most large ones, sitemap discovery found nothing.</li>
     * <li>RSS 2.0 ({@code item/link}) and Atom ({@code entry/link@href}) — the
     * protocol accepts both as sitemaps.</li>
     * <li>Plain text — one URL per line.</li>
     * <li>Any of the above gzip-compressed, whatever the Content-Type says.</li>
     * </ul>
     * Element names are compared without their namespace prefix, so
     * {@code <sm:urlset>} reads like {@code <urlset>}.
     */
    static Sitemap parseSitemap(byte[] body, String declaredCharset, String sitemapUrl) throws IOException {
        boolean gzipped = isGzip(body);
        byte[] bytes = gzipped ? gunzip(body) : body;
        // Reaching a cap means the sitemap may list more than was read.
        boolean decompressionCapped = gzipped && bytes.length >= MAX_DECOMPRESSED_SITEMAP_BYTES;
        if (!looksLikeXml(bytes)) {
            List<String> lines = textSitemapUrls(bytes, bytes == body ? declaredCharset : null);
            return new Sitemap(lines, List.of(), !decompressionCapped && lines.size() < MAX_SITEMAP_URLS);
        }
        // A charset from the HTTP header describes the compressed bytes, not what is
        // inside them; the XML declaration is authoritative for those.
        Document xml = Jsoup.parse(new ByteArrayInputStream(bytes), bytes == body ? declaredCharset : null,
                sitemapUrl, Parser.xmlParser());
        Set<String> found = new LinkedHashSet<>();
        String root = rootName(xml);
        switch (root) {
            case "sitemapindex" -> collectText(xml, "loc", "sitemap", found);
            case "rss", "rdf" -> collectText(xml, "link", "item", found);
            case "feed" -> {
                for (Element link : xml.getAllElements()) {
                    String rel = link.attr("rel");
                    if ("link".equals(localName(link)) && "entry".equals(localName(link.parent()))
                            && (rel.isEmpty() || "alternate".equals(rel))) {
                        addIfHttp(link.attr("href"), found);
                    }
                }
            }
            default -> collectText(xml, "loc", "url", found);
        }
        List<String> urls = List.copyOf(found);
        boolean complete = !decompressionCapped && urls.size() < MAX_SITEMAP_URLS
                && (!SITEMAP_ROOTS.contains(root) || endsWithClosingRoot(bytes, xml.charset(), root));
        return "sitemapindex".equals(root) ? new Sitemap(List.of(), urls, complete) : new Sitemap(urls, List.of(), complete);
    }

    /** The document elements of the forms read as sitemaps. */
    private static final Set<String> SITEMAP_ROOTS = Set.of("urlset", "sitemapindex", "rss", "rdf", "feed");

    /**
     * Whether a sitemap body ends with its own closing root tag — the one sign that
     * it arrived whole.
     * <p>
     * Jsoup's XML parser is lenient by design and reports nothing — not a body that
     * stops mid-element, not a mismatched end tag — so a sitemap cut short (a
     * dropped connection, an upstream timeout, a proxy limit) parsed as a smaller
     * but complete one, and the pages past the cut read as deleted. Checked only
     * for sitemap roots: an HTML page served at {@code /sitemap.xml} is not a
     * sitemap, lists nothing, and must not stop deletions for a site that has none.
     */
    private static boolean endsWithClosingRoot(byte[] bytes, Charset charset, String root) {
        // XML allows comments, processing instructions and whitespace after the root
        // element, and generators append them ("<!-- generated in 0.2s -->"), so they
        // are skipped first; a self-closing root (<urlset/>) is complete too. Flagging
        // either stopped deletions for that site on every run. A trailing comment
        // that was itself cut off does not end in "-->", so that truncation is caught.
        String text = new String(bytes, charset);
        int end = text.length();
        while (true) {
            while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
                end--;
            }
            if (end >= 3 && text.startsWith("-->", end - 3)) {
                end = text.lastIndexOf("<!--", end - 3);
            } else if (end >= 2 && text.startsWith("?>", end - 2)) {
                end = text.lastIndexOf("<?", end - 2);
            } else {
                break;
            }
            if (end < 0) {
                return false;
            }
        }
        if (end == 0 || text.charAt(end - 1) != '>') {
            return false;
        }
        int open = text.lastIndexOf('<', end - 1);
        if (open < 0) {
            return false;
        }
        String tag = text.substring(open + 1, end - 1).strip();
        String name;
        if (tag.startsWith("/")) {
            name = tag.substring(1).strip();
        } else if (tag.endsWith("/")) {
            // A self-closing root: its name is the tag's first token.
            name = tag.substring(0, tag.length() - 1).strip().split("\\s+", 2)[0];
        } else {
            return false;
        }
        int colon = name.indexOf(':');
        return (colon >= 0 ? name.substring(colon + 1) : name).equalsIgnoreCase(root);
    }

    private static void collectText(Document xml, String element, String parent, Set<String> into) {
        for (Element candidate : xml.getAllElements()) {
            if (into.size() >= MAX_SITEMAP_URLS) {
                return;
            }
            if (element.equals(localName(candidate)) && parent.equals(localName(candidate.parent()))) {
                addIfHttp(candidate.text(), into);
            }
        }
    }

    private static void addIfHttp(String url, Set<String> into) {
        String trimmed = url == null ? "" : url.trim();
        if ((trimmed.startsWith("http://") || trimmed.startsWith("https://")) && into.size() < MAX_SITEMAP_URLS) {
            into.add(trimmed);
        }
    }

    /** The document element's name, without prefix, lower-cased. */
    private static String rootName(Document xml) {
        return xml.children().isEmpty() ? "" : localName(xml.child(0));
    }

    private static String localName(Element element) {
        if (element == null) {
            return "";
        }
        String name = element.tagName();
        int colon = name.indexOf(':');
        return (colon >= 0 ? name.substring(colon + 1) : name).toLowerCase(Locale.ROOT);
    }

    private static boolean isGzip(byte[] body) {
        return body.length >= 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b;
    }

    /**
     * Decompresses a gzipped sitemap, stopping at
     * {@link #MAX_DECOMPRESSED_SITEMAP_BYTES}. A megabyte of gzip can expand past a
     * gigabyte; what lies past the cap is never read, and the lenient parse still
     * yields the URLs before it.
     */
    private static byte[] gunzip(byte[] body) throws IOException {
        try (var in = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return in.readNBytes(MAX_DECOMPRESSED_SITEMAP_BYTES);
        }
    }

    private static boolean looksLikeXml(byte[] bytes) {
        for (byte value : bytes) {
            int b = value & 0xff;
            // Skip a UTF-8 byte-order mark and leading whitespace.
            if (b == 0xef || b == 0xbb || b == 0xbf || b == ' ' || b == '\t' || b == '\r' || b == '\n') {
                continue;
            }
            // '<', or the first byte of a UTF-16 byte-order mark.
            return b == '<' || b == 0xfe || b == 0xff;
        }
        return false;
    }

    private static List<String> textSitemapUrls(byte[] bytes, String declaredCharset) {
        Charset charset = StandardCharsets.UTF_8;
        if (declaredCharset != null) {
            try {
                charset = Charset.forName(declaredCharset);
            } catch (RuntimeException e) {
                // An unknown label is the site's mistake; UTF-8 is what the protocol requires.
            }
        }
        String text = new String(bytes, charset);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        Set<String> found = new LinkedHashSet<>();
        for (String line : text.split("\\R")) {
            addIfHttp(line, found);
        }
        return List.copyOf(found);
    }

    private static String resolve(String base, String reference) {
        if (base == null || reference == null || reference.isBlank()) {
            return null;
        }
        try {
            URI resolved = URI.create(base).resolve(reference.trim());
            String scheme = resolved.getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme) ? resolved.toString() : null;
        } catch (IllegalArgumentException e) {
            return null;
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
     * A status that reports the server's condition rather than the page's: an
     * outage, an overloaded origin, a rate limit, or a refusal to say anything at
     * all (401, 403 — an expired credential, an IP block, a WAF). A 404 or 410 is
     * the opposite: the server answering that the page is gone.
     */
    private static boolean saysNothingAboutContent(int statusCode) {
        return statusCode >= 500 || statusCode == 408 || statusCode == 429
                || statusCode == 401 || statusCode == 403;
    }

    /**
     * What a crawl did.
     *
     * @param unreachableErrors
     *            the errors that say nothing about the source's content — transport
     *            failures, server errors, rate limits, an unusable seed
     * @param stopReason
     *            whether the crawl covered the source or ran out of budget. The
     *            caller needs this before concluding that an unseen document has
     *            been deleted: a crawl that hit its page limit saw an arbitrary
     *            subset.
     * @param discoveryIncomplete
     *            a sitemap was cut at a cap or could not be read, so pages it lists
     *            may never have been queued — the same reason as a limit not to
     *            read absence as deletion
     */
    public record CrawlSummary(
            int pagesFetched,
            int pagesUnchanged,
            int pagesSkipped,
            int errors,
            int unreachableErrors,
            int fetchAttempts,
            long bytesDownloaded,
            Duration duration,
            StopReason stopReason,
            boolean discoveryIncomplete) {

        /**
         * A summary whose discovery was complete — every source that has no sitemaps.
         */
        public CrawlSummary(int pagesFetched, int pagesUnchanged, int pagesSkipped, int errors, int unreachableErrors,
                int fetchAttempts, long bytesDownloaded, Duration duration, StopReason stopReason) {
            this(pagesFetched, pagesUnchanged, pagesSkipped, errors, unreachableErrors, fetchAttempts, bytesDownloaded,
                    duration, stopReason, false);
        }

        /**
         * Whether the crawl covered its whole scope, so absence means deletion.
         *
         * <p>
         * A crawl that reached no page proves nothing about which pages exist, yet it
         * also ends as {@code COMPLETED}: an unreachable seed, or a robots.txt that
         * briefly disallows everything, leaves nothing queued. Counting that as
         * coverage would report every document as gone. So a crawl that fetched nothing
         * is coverage only when the server answered that the content is gone — a 404 on
         * the seed is the deletion of the start page, not an outage. A dead link on a
         * site that otherwise answered still counts too.
         */
        public boolean coveredWholeSource() {
            boolean nothingReached = pagesFetched + pagesUnchanged == 0
                    && (errors == 0 || unreachableErrors == errors);
            return stopReason == StopReason.COMPLETED && !nothingReached && !discoveryIncomplete;
        }
    }

    private static final class Counters {
        private int pagesFetched;
        private int unchanged;
        private int skipped;
        private int errors;
        private int unreachable;
        private int fetchAttempts;
        private long bytesDownloaded;
        /**
         * A sitemap was cut short or left unread, so the queue was never the whole
         * source.
         */
        private boolean discoveryIncomplete;

        CrawlSummary summarize(Instant start, StopReason stopReason) {
            return new CrawlSummary(pagesFetched, unchanged, skipped, errors, unreachable, fetchAttempts,
                    bytesDownloaded,
                    Duration.between(start, Instant.now()), stopReason, discoveryIncomplete);
        }
    }
}
