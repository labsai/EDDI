/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CrawlUrls} — page identity.
 *
 * <p>
 * Identity decides both what the crawler considers already visited and which
 * knowledge-base document a page updates, so errors here are invisible in
 * either direction: too little normalization ingests one page repeatedly, too
 * much silently skips pages that were never crawled.
 */
class CrawlUrlsTest {

    @Nested
    @DisplayName("canonicalize")
    class Canonicalize {

        @Test
        @DisplayName("preserves path case — the bug that silently skipped pages")
        void preservesPathCase() {
            // The draft lowercased the entire URL, so these two collapsed into one
            // entry and whichever was seen second was never crawled. Paths are
            // case-sensitive; hosts are not.
            assertNotEquals(CrawlUrls.canonicalize("https://example.com/Docs/Guide"),
                    CrawlUrls.canonicalize("https://example.com/docs/guide"));
            assertTrue(CrawlUrls.canonicalize("https://example.com/Docs/Guide").contains("/Docs/Guide"));
        }

        @Test
        @DisplayName("lowercases scheme and host")
        void lowercasesSchemeAndHost() {
            assertEquals("https://example.com/Path",
                    CrawlUrls.canonicalize("HTTPS://Example.COM/Path"));
        }

        @Test
        @DisplayName("drops the fragment — it is never sent to a server")
        void dropsFragment() {
            assertEquals(CrawlUrls.canonicalize("https://example.com/docs"),
                    CrawlUrls.canonicalize("https://example.com/docs#installation"));
        }

        @Test
        @DisplayName("drops a default port but keeps a non-default one")
        void handlesPorts() {
            assertEquals(CrawlUrls.canonicalize("https://example.com/a"),
                    CrawlUrls.canonicalize("https://example.com:443/a"));
            assertEquals(CrawlUrls.canonicalize("http://example.com/a"),
                    CrawlUrls.canonicalize("http://example.com:80/a"));
            assertTrue(CrawlUrls.canonicalize("https://example.com:8443/a").contains(":8443"));
        }

        @Test
        @DisplayName("folds a trailing slash")
        void foldsTrailingSlash() {
            assertEquals(CrawlUrls.canonicalize("https://example.com/docs"),
                    CrawlUrls.canonicalize("https://example.com/docs/"));
        }

        @Test
        @DisplayName("folds an index filename into its directory")
        void foldsIndexFile() {
            assertEquals(CrawlUrls.canonicalize("https://example.com/docs/"),
                    CrawlUrls.canonicalize("https://example.com/docs/index.html"));
        }

        @Test
        @DisplayName("strips tracking parameters so a campaign link is not a second document")
        void stripsTrackingParameters() {
            assertEquals(CrawlUrls.canonicalize("https://example.com/post"),
                    CrawlUrls.canonicalize("https://example.com/post?utm_source=twitter&utm_campaign=launch"));
            assertEquals(CrawlUrls.canonicalize("https://example.com/post"),
                    CrawlUrls.canonicalize("https://example.com/post?gclid=abc123"));
        }

        @Test
        @DisplayName("keeps meaningful query parameters")
        void keepsMeaningfulParameters() {
            assertTrue(CrawlUrls.canonicalize("https://example.com/search?q=rag").contains("q=rag"));
        }

        @Test
        @DisplayName("sorts query parameters so link order does not create duplicates")
        void sortsQueryParameters() {
            assertEquals(CrawlUrls.canonicalize("https://example.com/p?a=1&b=2"),
                    CrawlUrls.canonicalize("https://example.com/p?b=2&a=1"));
        }

        @Test
        @DisplayName("resolves dot segments")
        void resolvesDotSegments() {
            assertEquals(CrawlUrls.canonicalize("https://example.com/docs/guide"),
                    CrawlUrls.canonicalize("https://example.com/docs/./sub/../guide"));
        }

        @Test
        @DisplayName("returns something comparable for input it cannot parse")
        void handlesUnparseableInput() {
            assertEquals("", CrawlUrls.canonicalize(null));
            assertEquals("", CrawlUrls.canonicalize("   "));
            String odd = CrawlUrls.canonicalize("not a url at all");
            assertEquals(odd, CrawlUrls.canonicalize("not a url at all"), "must at least be stable");
        }
    }

    @Nested
    @DisplayName("scope helpers")
    class ScopeHelpers {

        @Test
        @DisplayName("same host matches")
        void sameHostMatches() {
            assertTrue(CrawlUrls.isSameSite("example.com", "example.com", false));
        }

        @Test
        @DisplayName("www is treated as the same site, which is what operators mean")
        void wwwIsSameSite() {
            assertTrue(CrawlUrls.isSameSite("www.example.com", "example.com", false));
            assertTrue(CrawlUrls.isSameSite("example.com", "www.example.com", false));
        }

        @Test
        @DisplayName("a subdomain is a different site unless subdomains are enabled")
        void subdomainsAreOptIn() {
            assertFalse(CrawlUrls.isSameSite("docs.example.com", "example.com", false));
            assertTrue(CrawlUrls.isSameSite("docs.example.com", "example.com", true));
        }

        @Test
        @DisplayName("an unrelated host never matches, even with subdomains enabled")
        void unrelatedHostNeverMatches() {
            assertFalse(CrawlUrls.isSameSite("evil.com", "example.com", true));
            assertFalse(CrawlUrls.isSameSite("notexample.com", "example.com", true));
            assertFalse(CrawlUrls.isSameSite(null, "example.com", true));
        }

        @Test
        @DisplayName("path prefixes normalize to leading and trailing slashes")
        void normalizesPathPrefix() {
            assertEquals("/docs/", CrawlUrls.normalizePathPrefix("docs"));
            assertEquals("/docs/", CrawlUrls.normalizePathPrefix("/docs"));
            assertEquals("/docs/", CrawlUrls.normalizePathPrefix("/docs/"));
            assertEquals("/", CrawlUrls.normalizePathPrefix(null));
            assertEquals("/", CrawlUrls.normalizePathPrefix(""));
        }

        @Test
        @DisplayName("a prefix includes the section's own landing page")
        void prefixIncludesItsOwnLandingPage() {
            // Scoping a crawl to /docs/ and then excluding /docs itself would drop the
            // section index, which is usually the most useful page in it.
            assertTrue(CrawlUrls.isUnderPathPrefix("/docs", "/docs/"));
            assertTrue(CrawlUrls.isUnderPathPrefix("/docs/guide", "/docs/"));
            assertFalse(CrawlUrls.isUnderPathPrefix("/blog/post", "/docs/"));
        }

        @Test
        @DisplayName("the root prefix admits everything")
        void rootPrefixAdmitsEverything() {
            assertTrue(CrawlUrls.isUnderPathPrefix("/anything/at/all", "/"));
        }

        @Test
        @DisplayName("only http and https are crawlable")
        void onlyHttpSchemes() {
            assertTrue(CrawlUrls.isHttpScheme("http://example.com"));
            assertTrue(CrawlUrls.isHttpScheme("https://example.com"));
            assertFalse(CrawlUrls.isHttpScheme("file:///etc/passwd"));
            assertFalse(CrawlUrls.isHttpScheme("javascript:alert(1)"));
            assertFalse(CrawlUrls.isHttpScheme("mailto:a@b.com"));
            assertFalse(CrawlUrls.isHttpScheme("ftp://example.com"));
        }

        @Test
        @DisplayName("path defaults to / when a URL has none")
        void pathDefaults() {
            assertEquals("/", CrawlUrls.path("https://example.com"));
            assertEquals("/docs/guide", CrawlUrls.path("https://example.com/docs/guide"));
            assertEquals("/", CrawlUrls.path("nonsense"));
        }
    }

    @Test
    @DisplayName("a URL with characters the RFC forbids is encoded rather than dropped")
    void encodesIllegalCharacters() {
        // jsoup resolves href="my doc.html" without percent-encoding it, and the
        // single-argument URI constructor then refuses the space. The link used to
        // be discarded with no error and no counter, which silently excluded whole
        // sections of SharePoint and Confluence exports.
        String canonical = CrawlUrls.canonicalize("https://example.com/docs/my doc.html");

        assertTrue(CrawlUrls.isHttpScheme(canonical), "must still be a usable http URL: " + canonical);
        assertEquals("https://example.com/docs/my%20doc.html", canonical);
    }

    @Test
    @DisplayName("a pipe or a brace in a query survives canonicalization")
    void encodesIllegalQueryCharacters() {
        String canonical = CrawlUrls.canonicalize("https://example.com/search?q=a|b");

        assertTrue(CrawlUrls.isHttpScheme(canonical), canonical);
        assertTrue(canonical.startsWith("https://example.com/search?q=a"), canonical);
    }
}
