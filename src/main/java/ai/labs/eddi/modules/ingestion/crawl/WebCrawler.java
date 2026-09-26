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
import java.util.ArrayList;
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
 * A 304 has no body, so a page revalidated that way contributes no links. That
 * is harmless for a page whose links the crawl would not follow anyway — one at
 * {@code maxDepth} — and destructive for any other: its children are never
 * reached, a crawl that otherwise covered the site calls them missing, and
 * after {@code tombstoneAfterMissedRuns} runs every page below an unchanged
 * parent loses its vectors. So validators are sent only for pages at the depth
 * limit. A page above it is downloaded in full and its links read; whether it
 * is re-embedded is still decided by its content hash, so an unchanged page
 * costs a download, never an embedding.
 *
 * <h2>Identity</h2>
 * <p>
 * A page is stored under the URL that served it (after redirects), never under
 * the URL its {@code <link rel="canonical">} names. The canonical link is a
 * de-duplication hint: a page naming another page as canonical defers to it —
 * that page is fetched and stored under its own URL, with its own content — and
 * is stored itself only when the page it names produced nothing. Using the
 * canonical as the identity let any page on the host write its content under
 * another page's id, and dropped a paginated listing's links along with its
 * content.
 */
@ApplicationScoped
public class WebCrawler {

    private static final Logger LOGGER = Logger.getLogger(WebCrawler.class);

    /**
     * Cap on URLs taken from sitemaps, so a huge sitemap cannot swamp the queue.
     */
    private static final int MAX_SITEMAP_URLS = 5_000;

    /**
     * Cap on sitemaps read from one robots.txt. The file is the site's to write and
     * may list thousands; each is a request made before the first page is fetched.
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

        Frontier frontier = new Frontier();
        enqueueSeeds(request, sink, robots, delay, deadline, counters, frontier, seedHost, excludes);
        Queue<Candidate> queue = frontier.queue;

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
            //
            // A page released from deferring to its canonical was already polled once,
            // so it is the one candidate the mark must not turn away.
            if (!frontier.visited.add(candidate.canonicalId()) && !candidate.ignoreCanonical()) {
                continue;
            }
            if (request.politeness().respectRobots()
                    && !candidateRobots.isAllowed(CrawlUrls.pathAndQuery(candidate.fetchUrl()))) {
                counters.skipped++;
                frontier.releaseDeferred(candidate.canonicalId());
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

            processCandidate(request, sink, candidate, seedHost, excludes, frontier, counters);
            // Whatever became of this URL, pages that deferred to it as their
            // canonical are owed a decision: if it produced no document of its own,
            // they are stored after all.
            frontier.releaseDeferred(candidate.canonicalId());
        }

        if (stopReason == StopReason.PAGE_LIMIT && counters.sitemapPages > 0) {
            // Sitemap pages are queued at depth 0, so maxDepth does not narrow them. A
            // source that stayed under maxPages by depth alone can now run into it —
            // and a crawl that stops at a limit concludes nothing about deletions.
            LOGGER.warnf("Crawl of %s stopped at maxPages (%d) with %d pages queued from its sitemap; deletions "
                    + "are not reconciled for a crawl that stops at a limit. Raise maxPages, or narrow the scope "
                    + "with pathPrefix or excludePatterns", LogSanitizer.sanitize(request.seedUrl()),
                    request.limits().maxPages(), counters.sitemapPages);
        }
        CrawlSummary summary = counters.summarize(start, stopReason);
        LOGGER.debugf("Crawl of %s finished: %s", LogSanitizer.sanitize(request.seedUrl()), summary);
        return summary;
    }

    private void processCandidate(CrawlRequest request, CrawlSink sink, Candidate candidate, String seedHost,
                                  List<UrlPattern> excludes, Frontier frontier, Counters counters) {

        // Revalidated only where a 304 costs nothing but the download: a page whose
        // links this crawl would not follow anyway. Above the depth limit a 304 hides
        // the page's links, and every page reachable only through it is reported
        // missing and, a couple of runs later, deleted.
        boolean linksWanted = candidate.depth() < request.scope().maxDepth();
        ConditionalHeaders conditional = linksWanted
                ? ConditionalHeaders.none()
                : sink.conditionalFor(candidate.canonicalId());
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
            frontier.visited.add(candidate.canonicalId());
            frontier.delivered.add(candidate.canonicalId());
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

        // The page's identity is the URL that served it. Never its canonical link:
        // that is the page's word about another page, and taking it as the identity
        // let any page on the host store its content under someone else's id.
        String documentId = CrawlUrls.canonicalize(finalUrl);

        // A redirect can leave the scope the operator asked for; re-check rather
        // than trusting the pre-redirect decision.
        if (!isInScope(documentId, seedHost, request, excludes)) {
            counters.skipped++;
            return;
        }

        // Links first, whatever the page turns out to be. A duplicate, or a page
        // that defers to its canonical, still links to the rest of the site: the
        // second page of a listing that names the first as canonical is exactly
        // where the listing's later entries are linked from, and returning before
        // this made them unreachable.
        if (linksWanted) {
            enqueueLinks(document, candidate.depth() + 1, seedHost, request, excludes, frontier);
        }

        String canonical = candidate.ignoreCanonical()
                ? null
                : canonicalTarget(document, finalUrl, documentId, seedHost, request, excludes);
        if (canonical != null) {
            if (frontier.delivered.contains(canonical)) {
                // The page it names is already in: this is its duplicate.
                counters.skipped++;
                return;
            }
            if (!frontier.visited.contains(canonical)) {
                // Let the page it names speak for itself, under its own URL. If that
                // produces nothing, this page is re-queued and stored after all.
                frontier.defer(canonical, candidate);
                counters.skipped++;
                return;
            }
            // The page it names was fetched and produced no document — gone, broken,
            // or a redirect somewhere else. This page is the content, so it stays.
        }

        // The requested URL was marked visited when it was polled, so only a
        // documentId that DIFFERS from it — a redirect landing on a page another URL
        // already produced — can be a duplicate here.
        if (!frontier.delivered.add(documentId)) {
            counters.skipped++;
            return;
        }
        frontier.visited.add(documentId);

        counters.pagesFetched++;
        sink.onPage(new CrawledPage(documentId, finalUrl, document.title(), document.outerHtml(),
                page.etag(), page.lastModified(), candidate.depth(), page.truncated()));
    }

    private void enqueueLinks(Document document, int depth, String seedHost, CrawlRequest request,
                              List<UrlPattern> excludes, Frontier frontier) {

        Queue<Candidate> queue = frontier.queue;
        Set<String> queued = frontier.queued;
        Set<String> visited = frontier.visited;
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
            queue.add(new Candidate(CrawlUrls.stripFragment(href), canonical, depth, false));
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
     *
     * <p>
     * The sitemaps are the ones robots.txt names or, when it names none — or was
     * not read, because the source does not respect it — the conventional
     * {@code /sitemap.xml} on the seed's host. A sitemap index is followed to the
     * sitemaps it lists rather than having those queued as pages, which is how a
     * site with more than one sitemap publishes them. Every sitemap read, index or
     * not, counts against the same cap.
     */
    private void enqueueSeeds(CrawlRequest request, CrawlSink sink, RobotsPolicy robots, Duration delay,
                              Instant deadline, Counters counters, Frontier frontier, String seedHost,
                              List<UrlPattern> excludes) {

        String seedId = CrawlUrls.canonicalize(request.seedUrl());
        frontier.queued.add(seedId);
        frontier.queue.add(new Candidate(CrawlUrls.stripFragment(request.seedUrl()), seedId, 0, false));

        // Sitemaps are the cheapest and most reliable discovery there is: the site
        // lists its own pages, so nothing depends on link structure or on a page
        // having changed since the last run. They are requests all the same, made
        // before the crawl loop's own checks run, so they answer to the same
        // budgets, cancellation and politeness here.
        ArrayDeque<String> sitemaps = new ArrayDeque<>(robots.sitemaps());
        if (sitemaps.isEmpty()) {
            String conventional = conventionalSitemapUrl(request.seedUrl());
            if (conventional != null && (!request.politeness().respectRobots()
                    || robots.isAllowed(CrawlUrls.pathAndQuery(conventional)))) {
                sitemaps.add(conventional);
            }
        }
        Set<String> seenSitemaps = new HashSet<>(sitemaps);
        int sitemapsRead = 0;
        while (!sitemaps.isEmpty()) {
            if (sitemapsRead >= MAX_SITEMAPS
                    || limitReached(request, sink, counters, deadline) != null
                    || !pause(delay)) {
                break;
            }
            sitemapsRead++;
            Sitemap sitemap = fetchSitemap(sitemaps.poll(), request, counters);
            for (String child : sitemap.childSitemaps()) {
                if (CrawlUrls.isHttpScheme(child) && seenSitemaps.add(child)) {
                    sitemaps.add(child);
                }
            }
            for (String url : sitemap.pageUrls()) {
                String canonical = CrawlUrls.canonicalize(url);
                // A sitemap is written by the site, not by the operator, and may list
                // anything at all — so it earns no exemption from the scope.
                if (!canonical.isEmpty() && frontier.queued.add(canonical)
                        && isInScope(canonical, seedHost, request, excludes)) {
                    frontier.queue.add(new Candidate(CrawlUrls.stripFragment(url), canonical, 0, false));
                    counters.sitemapPages++;
                }
            }
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
     * The page this one names as canonical, when it is a different page this crawl
     * could fetch — or null to treat the page as its own.
     *
     * <p>
     * Honoured only on the same host, so a third-party canonical cannot pull the
     * crawl off-site, and only inside the operator's scope: deferring to a page the
     * crawl will never fetch would lose this page's content for nothing.
     */
    private String canonicalTarget(Document document, String finalUrl, String documentId, String seedHost,
                                   CrawlRequest request, List<UrlPattern> excludes) {
        Element canonical = document.selectFirst("link[rel=canonical][href]");
        if (canonical == null) {
            return null;
        }
        String href = canonical.attr("abs:href");
        if (href == null || href.isBlank() || !CrawlUrls.isHttpScheme(href)) {
            return null;
        }
        String canonicalHost = CrawlUrls.host(href).orElse(null);
        String actualHost = CrawlUrls.host(finalUrl).orElse(null);
        if (canonicalHost == null || !canonicalHost.equals(actualHost)) {
            return null;
        }
        String target = CrawlUrls.canonicalize(href);
        if (target.isEmpty() || target.equals(documentId) || !isInScope(target, seedHost, request, excludes)) {
            return null;
        }
        return target;
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
     * Reads one sitemap: the pages a {@code <urlset>} lists, or the sitemaps a
     * {@code <sitemapindex>} lists. The two are told apart by the element each
     * {@code <loc>} sits in rather than by the root, so one that mixes them still
     * yields both.
     */
    private Sitemap fetchSitemap(String sitemapUrl, CrawlRequest request, Counters counters) {
        try {
            counters.fetchAttempts++;
            FetchedPage page = fetcher.fetch(new FetchCommand(sitemapUrl, request.politeness().userAgent(),
                    request.limits().requestTimeout(), null, null, MAX_METADATA_BYTES));
            counters.bytesDownloaded += page.body() == null ? 0 : page.body().length;
            if (!page.isOk() || page.body() == null || page.body().length == 0) {
                return Sitemap.EMPTY;
            }
            Document sitemap = Jsoup.parse(new ByteArrayInputStream(page.body()), page.declaredCharset(),
                    sitemapUrl, Parser.xmlParser());
            Set<String> pages = new LinkedHashSet<>();
            Set<String> children = new LinkedHashSet<>();
            for (Element location : sitemap.select("loc")) {
                String url = location.text().trim();
                if (url.isEmpty()) {
                    continue;
                }
                Element parent = location.parent();
                if (parent != null && "sitemap".equalsIgnoreCase(parent.normalName())) {
                    if (children.size() < MAX_SITEMAPS) {
                        children.add(url);
                    }
                } else if (pages.size() < MAX_SITEMAP_URLS) {
                    pages.add(url);
                }
            }
            return new Sitemap(List.copyOf(pages), List.copyOf(children));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Sitemap.EMPTY;
        } catch (IOException | RuntimeException e) {
            LOGGER.debugf("Could not read sitemap %s: %s",
                    LogSanitizer.sanitize(sitemapUrl), LogSanitizer.sanitize(describe(e)));
            return Sitemap.EMPTY;
        }
    }

    /** What one sitemap listed: pages, or further sitemaps. */
    private record Sitemap(List<String> pageUrls, List<String> childSitemaps) {
        private static final Sitemap EMPTY = new Sitemap(List.of(), List.of());
    }

    /**
     * {@code /sitemap.xml} on the seed's host — where a site puts it by convention.
     */
    private static String conventionalSitemapUrl(String seedUrl) {
        return rootResourceUrl(seedUrl, "/sitemap.xml");
    }

    private static String robotsUrlFor(String seedUrl) {
        return rootResourceUrl(seedUrl, "/robots.txt");
    }

    private static String rootResourceUrl(String url, String path) {
        try {
            URI uri = new URI(url);
            if (uri.getHost() == null) {
                return null;
            }
            return new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), path, null, null).toString();
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
     * @param ignoreCanonical
     *            the page deferred to its canonical once and the page it named
     *            produced nothing, so this time it is stored as itself
     */
    private record Candidate(String fetchUrl, String canonicalId, int depth, boolean ignoreCanonical) {
    }

    /**
     * What one crawl knows about URLs: what is queued, what has been polled, which
     * documents it has handed to the sink, and which pages are waiting on the page
     * they named as canonical.
     */
    private static final class Frontier {
        private final Queue<Candidate> queue = new ArrayDeque<>();
        /**
         * Separate from {@code visited}: a URL is queued long before it is polled, and
         * without this a page linked from 200 others is enqueued 200 times. At maxPages
         * 50,000 with typical navigation that is millions of entries.
         */
        private final Set<String> queued = new HashSet<>();
        private final Set<String> visited = new HashSet<>();
        /** Document ids this crawl has reported, as a page or as unchanged. */
        private final Set<String> delivered = new HashSet<>();
        private final Map<String, List<Candidate>> deferredTo = new HashMap<>();

        /** Holds a page back until the page it names as canonical has been tried. */
        void defer(String canonical, Candidate candidate) {
            deferredTo.computeIfAbsent(canonical, ignored -> new ArrayList<>()).add(candidate);
            if (queued.add(canonical)) {
                queue.add(new Candidate(canonical, canonical, candidate.depth(), false));
            }
        }

        /**
         * Once a canonical target has been tried: the pages that deferred to it are
         * duplicates if it produced a document, and are re-queued to be stored as
         * themselves if it did not.
         */
        void releaseDeferred(String polledId) {
            List<Candidate> waiting = deferredTo.remove(polledId);
            if (waiting == null || delivered.contains(polledId)) {
                return;
            }
            for (Candidate candidate : waiting) {
                queue.add(new Candidate(candidate.fetchUrl(), candidate.canonicalId(), candidate.depth(), true));
            }
        }
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
            StopReason stopReason) {

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
            return stopReason == StopReason.COMPLETED && !nothingReached;
        }
    }

    private static final class Counters {
        private int pagesFetched;
        private int unchanged;
        private int skipped;
        private int errors;
        private int unreachable;
        private int fetchAttempts;
        /** In-scope pages queued from sitemaps, for the page-limit warning. */
        private int sitemapPages;
        private long bytesDownloaded;

        CrawlSummary summarize(Instant start, StopReason stopReason) {
            return new CrawlSummary(pagesFetched, unchanged, skipped, errors, unreachable, fetchAttempts,
                    bytesDownloaded,
                    Duration.between(start, Instant.now()), stopReason);
        }
    }
}
