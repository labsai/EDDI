/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import ai.labs.eddi.modules.ingestion.crawl.CrawlRequest.Limits;
import ai.labs.eddi.modules.ingestion.crawl.CrawlRequest.Politeness;
import ai.labs.eddi.modules.ingestion.crawl.CrawlRequest.Scope;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler.CrawlSummary;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler.StopReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WebCrawler}.
 *
 * <p>
 * Every case runs against {@link FakeSite} — no network, no container, no test
 * server — so the crawler's real decisions are covered by the unit gate. The
 * draft this replaces had one Testcontainers test that the unit run does not
 * execute, which is why none of the defects pinned here were caught.
 */
class WebCrawlerTest {

    private static final String SITE = "https://example.com";
    private static final String NEWLINE = System.lineSeparator();

    private static String linkTo(String... urls) {
        StringBuilder html = new StringBuilder("<html><head><title>Page</title></head><body>");
        for (String url : urls) {
            html.append("<a href=\"").append(url).append("\">link</a>");
        }
        return html.append("</body></html>").toString();
    }

    private static CrawlRequest request(String seed, Scope scope, Limits limits) {
        // No delay: these tests assert behaviour, not politeness, and a real delay
        // would make the suite take minutes.
        return new CrawlRequest(seed, scope, limits, new Politeness(Duration.ZERO, "EDDI-Crawler/1.0", false));
    }

    private static CrawlRequest request(String seed) {
        return request(seed, Scope.defaults(), Limits.defaults());
    }

    @Nested
    @DisplayName("traversal")
    class Traversal {

        @Test
        @DisplayName("follows links breadth-first within the depth limit")
        void followsLinks() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a", SITE + "/b"))
                    .page(SITE + "/a", linkTo(SITE + "/c"))
                    .page(SITE + "/b", "<html><body>B</body></html>")
                    .page(SITE + "/c", "<html><body>C</body></html>");
            RecordingSink sink = new RecordingSink();

            CrawlSummary summary = new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertEquals(4, summary.pagesFetched());
            assertTrue(sink.documentIds().containsAll(
                    List.of(SITE, SITE + "/a", SITE + "/b", SITE + "/c")), sink.documentIds().toString());
            assertEquals(StopReason.COMPLETED, summary.stopReason());
        }

        @Test
        @DisplayName("does not follow links past maxDepth")
        void respectsMaxDepth() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a"))
                    .page(SITE + "/a", linkTo(SITE + "/b"))
                    .page(SITE + "/b", linkTo(SITE + "/c"))
                    .page(SITE + "/c", "<html><body>too deep</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(
                    request(SITE + "/", new Scope(true, false, "/", 1, List.of()), Limits.defaults()), sink);

            assertTrue(sink.documentIds().contains(SITE + "/a"));
            assertFalse(sink.documentIds().contains(SITE + "/b"), "depth 2 is past maxDepth 1");
        }

        @Test
        @DisplayName("visits a page once even when many pages link to it")
        void visitsEachPageOnce() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a", SITE + "/b"))
                    .page(SITE + "/a", linkTo(SITE + "/shared"))
                    .page(SITE + "/b", linkTo(SITE + "/shared"))
                    .page(SITE + "/shared", "<html><body>shared</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertEquals(1, sink.documentIds().stream().filter((SITE + "/shared")::equals).count());
        }

        @Test
        @DisplayName("URLs differing only by fragment or tracking parameter are one page")
        void canonicalizesBeforeVisiting() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a#top", SITE + "/a?utm_source=x", SITE + "/a"))
                    .page(SITE + "/a", "<html><body>A</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertEquals(1, sink.documentIds().stream().filter((SITE + "/a")::equals).count(),
                    "one page, not three: " + sink.documentIds());
        }

        @Test
        @DisplayName("ignores a link marked rel=nofollow")
        void honoursNofollow() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/x\" rel=\"nofollow\">x</a></body></html>")
                    .page(SITE + "/x", "<html><body>X</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertFalse(site.wasRequested(SITE + "/x"));
        }
    }

    @Nested
    @DisplayName("identity")
    class Identity {

        @Test
        @DisplayName("a redirected page is identified by where it landed, not what was asked for")
        void usesFinalUrlAfterRedirect() {
            // The draft recorded the requested URL, so /docs and /docs/ became two
            // documents with identical content, and relative links on the page
            // resolved against the wrong base.
            FakeSite site = new FakeSite()
                    .redirect(SITE + "/docs", SITE + "/docs/guide", "<html><body>Guide</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/docs"), sink);

            assertEquals(List.of(SITE + "/docs/guide"), sink.documentIds());
            assertEquals(SITE + "/docs/guide", sink.pages().get(0).finalUrl());
        }

        @Test
        @DisplayName("a redirect that leaves the site is dropped, not ingested")
        void redirectOffSiteIsRejected() {
            // sameSiteOnly was satisfied by the pre-redirect host; without re-checking
            // the final URL a redirect smuggles a foreign page into the knowledge base.
            FakeSite site = new FakeSite()
                    .redirect(SITE + "/out", "https://elsewhere.test/page", "<html><body>Foreign</body></html>");
            RecordingSink sink = new RecordingSink();

            CrawlSummary summary = new WebCrawler(site).crawl(request(SITE + "/out"), sink);

            assertTrue(sink.pages().isEmpty(), "a page on another host must not be ingested");
            assertEquals(1, summary.pagesSkipped());
        }

        @Test
        @DisplayName("a canonical link decides the document identity")
        void honoursCanonicalLink() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/a?page=2", "<html><head><link rel=\"canonical\" href=\"" + SITE + "/a\">"
                            + "</head><body>A</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/a?page=2"), sink);

            assertEquals(List.of(SITE + "/a"), sink.documentIds());
        }

        @Test
        @DisplayName("a canonical link pointing off-site is ignored")
        void ignoresForeignCanonical() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/a", "<html><head><link rel=\"canonical\" href=\"https://elsewhere.test/a\">"
                            + "</head><body>A</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/a"), sink);

            assertEquals(List.of(SITE + "/a"), sink.documentIds(),
                    "a third party must not be able to claim our document's identity");
        }

        @Test
        @DisplayName("decodes a non-UTF-8 page using the declared charset")
        void decodesDeclaredCharset() {
            // Reading the body as a String assumes UTF-8 and turns every legacy
            // Windows-1252 page into mojibake — which embeds without complaint.
            FakeSite site = new FakeSite().pageEncoded(SITE + "/a",
                    "<html><body>Preisänderung für Café</body></html>",
                    StandardCharsets.ISO_8859_1, true);
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/a"), sink);

            assertTrue(sink.page(SITE + "/a").html().contains("Preisänderung"),
                    "was: " + sink.page(SITE + "/a").html());
        }
    }

    @Nested
    @DisplayName("scope")
    class ScopeRules {

        @Test
        @DisplayName("stays on the seed's site")
        void staysOnSite() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo("https://elsewhere.test/page", SITE + "/a"))
                    .page(SITE + "/a", "<html><body>A</body></html>")
                    .page("https://elsewhere.test/page", "<html><body>Foreign</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertFalse(site.wasRequested("https://elsewhere.test/page"));
        }

        @Test
        @DisplayName("stays under the configured path prefix")
        void staysUnderPathPrefix() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/docs/", linkTo(SITE + "/docs/a", SITE + "/blog/b"))
                    .page(SITE + "/docs/a", "<html><body>A</body></html>")
                    .page(SITE + "/blog/b", "<html><body>B</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(
                    request(SITE + "/docs/", new Scope(true, false, "/docs/", 3, List.of()), Limits.defaults()), sink);

            assertTrue(sink.documentIds().contains(SITE + "/docs/a"));
            assertFalse(site.wasRequested(SITE + "/blog/b"));
        }

        @Test
        @DisplayName("applies exclude globs against the path")
        void appliesExcludes() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/docs/report.pdf", SITE + "/docs/guide"))
                    .page(SITE + "/docs/guide", "<html><body>Guide</body></html>")
                    .page(SITE + "/docs/report.pdf", "<html><body>PDF</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(
                    request(SITE + "/", new Scope(true, false, "/", 3, List.of("*.pdf")), Limits.defaults()), sink);

            assertFalse(site.wasRequested(SITE + "/docs/report.pdf"), "the documented *.pdf pattern must work");
            assertTrue(sink.documentIds().contains(SITE + "/docs/guide"));
        }

        @Test
        @DisplayName("a broken exclude pattern does not reduce the crawl to its seed")
        void brokenExcludeDoesNotBreakCrawl() {
            // The draft compiled patterns without escaping, and the resulting
            // PatternSyntaxException was caught as a *fetch* error for the current
            // page, dropping all of its links.
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a"))
                    .page(SITE + "/a", "<html><body>A</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(
                    request(SITE + "/", new Scope(true, false, "/", 3, List.of("**/v1+/**", "(")), Limits.defaults()),
                    sink);

            assertTrue(sink.documentIds().contains(SITE + "/a"), "links must still be followed");
        }

        @Test
        @DisplayName("never follows a non-http scheme")
        void ignoresNonHttpSchemes() {
            FakeSite site = new FakeSite().page(SITE + "/",
                    linkTo("mailto:a@b.com", "javascript:alert(1)", "file:///etc/passwd"));
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertEquals(1, sink.pages().size());
            assertFalse(site.wasRequested("file:///etc/passwd"));
        }

        @Test
        @DisplayName("a seed that is not http fails cleanly")
        void rejectsNonHttpSeed() {
            RecordingSink sink = new RecordingSink();

            CrawlSummary summary = new WebCrawler(new FakeSite()).crawl(request("file:///etc/passwd"), sink);

            assertEquals(0, summary.pagesFetched());
            assertEquals(1, summary.errors());
            assertEquals(1, sink.errors().size());
        }
    }

    @Nested
    @DisplayName("budgets")
    class Budgets {

        @Test
        @DisplayName("stops at maxPages")
        void stopsAtPageLimit() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a", SITE + "/b", SITE + "/c"))
                    .page(SITE + "/a", "<html><body>A</body></html>")
                    .page(SITE + "/b", "<html><body>B</body></html>")
                    .page(SITE + "/c", "<html><body>C</body></html>");
            RecordingSink sink = new RecordingSink();

            CrawlSummary summary = new WebCrawler(site).crawl(
                    request(SITE + "/", Scope.defaults(),
                            new Limits(2, 100, 0, 0, Duration.ofMinutes(1), Duration.ofSeconds(5))),
                    sink);

            assertEquals(2, summary.pagesFetched());
            assertEquals(StopReason.PAGE_LIMIT, summary.stopReason());
            assertFalse(summary.coveredWholeSource(),
                    "a capped crawl saw a subset — the caller must not treat absence as deletion");
        }

        @Test
        @DisplayName("bounds total requests, not just emitted pages")
        void boundsFetchAttempts() {
            // maxPages alone bounds nothing: a docs site linking 5,000 assets fetches
            // all 5,000 while emitting almost none, which is what the draft did.
            FakeSite site = new FakeSite().page(SITE + "/",
                    linkTo(SITE + "/1.zip", SITE + "/2.zip", SITE + "/3.zip", SITE + "/4.zip", SITE + "/5.zip"));
            for (int i = 1; i <= 5; i++) {
                site.binary(SITE + "/" + i + ".zip", "application/zip", 1024);
            }
            RecordingSink sink = new RecordingSink();

            CrawlSummary summary = new WebCrawler(site).crawl(
                    request(SITE + "/", Scope.defaults(),
                            new Limits(100, 3, 0, 0, Duration.ofMinutes(1), Duration.ofSeconds(5))),
                    sink);

            assertEquals(StopReason.FETCH_LIMIT, summary.stopReason());
            assertTrue(summary.fetchAttempts() <= 3, "made " + summary.fetchAttempts() + " requests");
        }

        @Test
        @DisplayName("stops when the download budget is spent")
        void stopsAtByteLimit() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a", SITE + "/b"))
                    .page(SITE + "/a", "<html><body>" + "x".repeat(4000) + "</body></html>")
                    .page(SITE + "/b", "<html><body>" + "y".repeat(4000) + "</body></html>");
            RecordingSink sink = new RecordingSink();

            CrawlSummary summary = new WebCrawler(site).crawl(
                    request(SITE + "/", Scope.defaults(),
                            new Limits(100, 100, 0, 2000, Duration.ofMinutes(1), Duration.ofSeconds(5))),
                    sink);

            assertEquals(StopReason.BYTE_LIMIT, summary.stopReason());
        }

        @Test
        @DisplayName("caps a single response body so one huge file cannot exhaust the heap")
        void capsPerPageBytes() {
            FakeSite site = new FakeSite().page(SITE + "/", "<html><body>" + "x".repeat(100_000) + "</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(
                    request(SITE + "/", Scope.defaults(),
                            new Limits(10, 10, 1024, 0, Duration.ofMinutes(1), Duration.ofSeconds(5))),
                    sink);

            assertTrue(sink.pages().get(0).truncated(), "the sink must know the content is incomplete");
        }

        @Test
        @DisplayName("stops when the time budget is spent")
        void stopsAtTimeLimit() {
            FakeSite site = new FakeSite().page(SITE + "/", linkTo(SITE + "/a"))
                    .page(SITE + "/a", "<html><body>A</body></html>");
            RecordingSink sink = new RecordingSink();

            CrawlSummary summary = new WebCrawler(site).crawl(
                    request(SITE + "/", Scope.defaults(),
                            new Limits(100, 100, 0, 0, Duration.ofNanos(1), Duration.ofSeconds(5))),
                    sink);

            assertEquals(StopReason.TIME_LIMIT, summary.stopReason());
        }

        @Test
        @DisplayName("stops when the sink asks to cancel")
        void stopsWhenCancelled() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a", SITE + "/b", SITE + "/c"))
                    .page(SITE + "/a", "<html><body>A</body></html>")
                    .page(SITE + "/b", "<html><body>B</body></html>")
                    .page(SITE + "/c", "<html><body>C</body></html>");
            RecordingSink sink = new RecordingSink().cancelAfter(2);

            CrawlSummary summary = new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertEquals(StopReason.CANCELLED, summary.stopReason());
            assertEquals(2, sink.pages().size());
        }
    }

    @Nested
    @DisplayName("responses")
    class Responses {

        @Test
        @DisplayName("a 304 reports the document unchanged rather than re-ingesting it")
        void notModifiedIsReportedUnchanged() {
            FakeSite site = new FakeSite().conditional(SITE + "/a", "<html><body>A</body></html>", "\"v1\"");
            RecordingSink sink = new RecordingSink().knows(SITE + "/a", "\"v1\"", null);

            CrawlSummary summary = new WebCrawler(site).crawl(request(SITE + "/a"), sink);

            assertEquals(List.of(SITE + "/a"), sink.unchanged());
            assertTrue(sink.pages().isEmpty());
            assertEquals(1, summary.pagesUnchanged());
        }

        @Test
        @DisplayName("stored validators are sent as conditional headers")
        void sendsConditionalHeaders() {
            FakeSite site = new FakeSite().page(SITE + "/a", "<html><body>A</body></html>");
            RecordingSink sink = new RecordingSink().knows(SITE + "/a", "\"v1\"", "Wed, 21 Oct 2026 07:28:00 GMT");

            new WebCrawler(site).crawl(request(SITE + "/a"), sink);

            var command = site.requests().stream().filter(r -> r.url().equals(SITE + "/a")).findFirst().orElseThrow();
            assertEquals("\"v1\"", command.ifNoneMatch());
            assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", command.ifModifiedSince());
        }

        @Test
        @DisplayName("validators from the response reach the sink for the next run")
        void passesValidatorsToSink() {
            FakeSite site = new FakeSite().pageWithValidators(SITE + "/a", "<html><body>A</body></html>",
                    "\"v2\"", "Thu, 22 Oct 2026 07:28:00 GMT");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/a"), sink);

            assertEquals("\"v2\"", sink.page(SITE + "/a").etag());
            assertEquals("Thu, 22 Oct 2026 07:28:00 GMT", sink.page(SITE + "/a").lastModified());
        }

        @Test
        @DisplayName("an HTTP error is recorded against the URL and the crawl continues")
        void httpErrorDoesNotStopCrawl() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/gone", SITE + "/ok"))
                    .status(SITE + "/gone", 500)
                    .page(SITE + "/ok", "<html><body>OK</body></html>");
            RecordingSink sink = new RecordingSink();

            CrawlSummary summary = new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertEquals(1, summary.errors());
            assertEquals(500, sink.errors().get(0).statusCode());
            assertTrue(sink.documentIds().contains(SITE + "/ok"), "one bad page must not end the crawl");
        }

        @Test
        @DisplayName("a transport failure is recorded and the crawl continues")
        void transportFailureDoesNotStopCrawl() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/broken", SITE + "/ok"))
                    .failure(SITE + "/broken", "Connection reset")
                    .page(SITE + "/ok", "<html><body>OK</body></html>");
            RecordingSink sink = new RecordingSink();

            CrawlSummary summary = new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertEquals(1, summary.errors());
            assertTrue(sink.errors().get(0).reason().contains("Connection reset"));
            assertTrue(sink.documentIds().contains(SITE + "/ok"));
        }

        @Test
        @DisplayName("a non-HTML resource is skipped, not treated as an error")
        void nonHtmlIsSkipped() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/logo.png"))
                    .binary(SITE + "/logo.png", "image/png", 2048);
            RecordingSink sink = new RecordingSink();

            CrawlSummary summary = new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertEquals(0, summary.errors(), "a docs site legitimately links images");
            assertEquals(1, summary.pagesSkipped());
            assertEquals(1, sink.pages().size());
        }
    }

    @Nested
    @DisplayName("review findings")
    class ReviewFindings {

        @Test
        @DisplayName("a dead link in a site-wide footer is fetched once, not once per page")
        void failedUrlIsNotRefetched() {
            // Only successful pages entered the visited set, so a 404 linked from every
            // page was fetched once per referring page: N requests, N errors, and a
            // fetch budget so exhausted that the crawl never reported full coverage —
            // which silently disabled deletion reconciliation for that site forever.
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a", SITE + "/b", SITE + "/legal"))
                    .page(SITE + "/a", linkTo(SITE + "/legal"))
                    .page(SITE + "/b", linkTo(SITE + "/legal"))
                    .status(SITE + "/legal", 404);
            RecordingSink sink = new RecordingSink();

            CrawlSummary summary = new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertEquals(1, site.requestCount(SITE + "/legal"), "the dead link must be tried once");
            assertEquals(1, summary.errors(), "and counted once");
            assertEquals(StopReason.COMPLETED, summary.stopReason(),
                    "a site with one dead link must still report full coverage");
        }

        @Test
        @DisplayName("a page linked from many others is queued once")
        void queueDoesNotGrowWithEveryLink() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a", SITE + "/b", SITE + "/shared"))
                    .page(SITE + "/a", linkTo(SITE + "/shared"))
                    .page(SITE + "/b", linkTo(SITE + "/shared"))
                    .page(SITE + "/shared", "<html><body>shared</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertEquals(1, site.requestCount(SITE + "/shared"));
        }

        @Test
        @DisplayName("a sitemap or feed is not ingested as a document")
        void xmlIsNotADocument() {
            // "xml" in the content type used to be treated as HTML, so a docs site
            // linking its own sitemap.xml got a knowledge-base entry made of
            // concatenated <loc> URLs, which retrieval then returned.
            FakeSite site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/sitemap.xml", SITE + "/feed.xml"))
                    .sitemap(SITE + "/sitemap.xml", SITE + "/a")
                    .binary(SITE + "/feed.xml", "application/rss+xml", 512);
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/"), sink);

            assertFalse(sink.documentIds().contains(SITE + "/sitemap.xml"), sink.documentIds().toString());
            assertFalse(sink.documentIds().contains(SITE + "/feed.xml"), sink.documentIds().toString());
        }

        @Test
        @DisplayName("each host's own robots.txt is honoured, not the seed's")
        void robotsIsFetchedPerHost() {
            // With subdomains included a crawl reaches several hosts; applying the
            // seed's rules to all of them means obeying one site and ignoring another.
            FakeSite site = new FakeSite()
                    .robots(SITE, "User-agent: *" + NEWLINE + "Disallow:")
                    .robots("https://docs.example.com", "User-agent: *" + NEWLINE + "Disallow: /private/")
                    .page(SITE + "/", linkTo("https://docs.example.com/private/x",
                            "https://docs.example.com/public"))
                    .page("https://docs.example.com/private/x", "<html><body>private</body></html>")
                    .page("https://docs.example.com/public", "<html><body>public</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(new CrawlRequest(SITE + "/",
                    new Scope(true, true, "/", 3, List.of()), Limits.defaults(),
                    new Politeness(Duration.ZERO, "EDDI-Crawler/1.0", true)), sink);

            assertFalse(site.wasRequested("https://docs.example.com/private/x"),
                    "the subdomain's own Disallow must be obeyed");
            assertTrue(sink.documentIds().contains("https://docs.example.com/public"));
        }
    }

    @Nested
    @DisplayName("robots.txt")
    class Robots {

        private CrawlRequest politeRequest(String seed) {
            return new CrawlRequest(seed, Scope.defaults(), Limits.defaults(),
                    new Politeness(Duration.ZERO, "EDDI-Crawler/1.0", true));
        }

        @Test
        @DisplayName("a disallowed path is not fetched")
        void honoursDisallow() {
            FakeSite site = new FakeSite()
                    .robots(SITE, "User-agent: *\nDisallow: /private/")
                    .page(SITE + "/", linkTo(SITE + "/private/secret", SITE + "/public"))
                    .page(SITE + "/private/secret", "<html><body>secret</body></html>")
                    .page(SITE + "/public", "<html><body>public</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(politeRequest(SITE + "/"), sink);

            assertFalse(site.wasRequested(SITE + "/private/secret"), "robots.txt must be obeyed before fetching");
            assertTrue(sink.documentIds().contains(SITE + "/public"));
        }

        @Test
        @DisplayName("robots is not consulted when the operator opts out")
        void respectRobotsCanBeDisabled() {
            FakeSite site = new FakeSite()
                    .robots(SITE, "User-agent: *\nDisallow: /")
                    .page(SITE + "/a", "<html><body>A</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(request(SITE + "/a"), sink);

            assertFalse(site.wasRequested(SITE + "/robots.txt"), "no robots fetch when it is turned off");
            assertEquals(1, sink.pages().size());
        }

        @Test
        @DisplayName("a missing robots.txt means the crawl proceeds")
        void missingRobotsAllowsCrawl() {
            FakeSite site = new FakeSite().page(SITE + "/a", "<html><body>A</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(politeRequest(SITE + "/a"), sink);

            assertEquals(1, sink.pages().size(), "absence of robots.txt means permission");
        }

        @Test
        @DisplayName("an unreadable robots.txt does not halt ingestion")
        void brokenRobotsAllowsCrawl() {
            FakeSite site = new FakeSite()
                    .failure(SITE + "/robots.txt", "connection reset")
                    .page(SITE + "/a", "<html><body>A</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(politeRequest(SITE + "/a"), sink);

            assertEquals(1, sink.pages().size());
        }

        @Test
        @DisplayName("sitemap URLs from robots.txt are crawled without needing a link")
        void seedsFromSitemap() {
            // The cheapest discovery there is, and the mitigation for a 304 page whose
            // links are not re-read.
            FakeSite site = new FakeSite()
                    .robots(SITE, "User-agent: *\nSitemap: " + SITE + "/sitemap.xml")
                    .sitemap(SITE + "/sitemap.xml", SITE + "/orphan")
                    .page(SITE + "/", "<html><body>no links here</body></html>")
                    .page(SITE + "/orphan", "<html><body>unlinked but listed</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(politeRequest(SITE + "/"), sink);

            assertTrue(sink.documentIds().contains(SITE + "/orphan"),
                    "a page in the sitemap must be found even when nothing links to it");
        }

        @Test
        @DisplayName("a sitemap page still has to satisfy the scope")
        void sitemapUrlsRespectScope() {
            FakeSite site = new FakeSite()
                    .robots(SITE, "User-agent: *\nSitemap: " + SITE + "/sitemap.xml")
                    .sitemap(SITE + "/sitemap.xml", "https://elsewhere.test/page")
                    .page(SITE + "/", "<html><body>home</body></html>");
            RecordingSink sink = new RecordingSink();

            new WebCrawler(site).crawl(politeRequest(SITE + "/"), sink);

            assertFalse(site.wasRequested("https://elsewhere.test/page"));
        }
    }
}
