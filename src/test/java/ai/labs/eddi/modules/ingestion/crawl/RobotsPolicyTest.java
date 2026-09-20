/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RobotsPolicy}.
 *
 * <p>
 * EDDI installations crawl sites their operators do not own, on a schedule.
 * Getting this wrong in the permissive direction gets the installation blocked;
 * getting it wrong in the restrictive direction silently ingests nothing.
 */
class RobotsPolicyTest {

    private static final String AGENT = "EDDI-Crawler/1.0";

    @Test
    @DisplayName("no robots.txt means everything is allowed")
    void absenceMeansPermission() {
        assertTrue(RobotsPolicy.parse(null, AGENT).isAllowed("/anything"));
        assertTrue(RobotsPolicy.parse("", AGENT).isAllowed("/anything"));
        assertTrue(RobotsPolicy.allowAll().isAllowed("/anything"));
    }

    @Test
    @DisplayName("a wildcard Disallow blocks the matching prefix")
    void wildcardDisallow() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Disallow: /private/
                """, AGENT);

        assertFalse(policy.isAllowed("/private/secret"));
        assertTrue(policy.isAllowed("/public/page"));
    }

    @Test
    @DisplayName("the longest matching rule wins, so Allow can carve out an exception")
    void longestRuleWins() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Disallow: /docs/
                Allow: /docs/public/
                """, AGENT);

        assertFalse(policy.isAllowed("/docs/internal"));
        assertTrue(policy.isAllowed("/docs/public/guide"),
                "a more specific Allow must beat a broader Disallow");
    }

    @Test
    @DisplayName("Allow wins a tie against Disallow of the same length")
    void allowWinsTies() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Disallow: /a/
                Allow: /a/
                """, AGENT);

        assertTrue(policy.isAllowed("/a/page"));
    }

    @Test
    @DisplayName("a group naming this crawler replaces the wildcard group")
    void specificGroupReplacesWildcard() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Disallow: /

                User-agent: EDDI-Crawler
                Disallow: /private/
                """, AGENT);

        assertTrue(policy.isAllowed("/docs"), "our own group's rules apply, not the wildcard's");
        assertFalse(policy.isAllowed("/private/x"));
    }

    @Test
    @DisplayName("a group for another crawler is ignored")
    void otherAgentGroupIsIgnored() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: BadBot
                Disallow: /

                User-agent: *
                Disallow: /private/
                """, AGENT);

        assertTrue(policy.isAllowed("/docs"));
        assertFalse(policy.isAllowed("/private/x"));
    }

    @Test
    @DisplayName("consecutive User-agent lines share one rule body")
    void groupedUserAgentsShareRules() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: SomeBot
                User-agent: *
                Disallow: /blocked/
                """, AGENT);

        assertFalse(policy.isAllowed("/blocked/x"));
    }

    @Test
    @DisplayName("an empty Disallow means nothing is disallowed")
    void emptyDisallowAllowsEverything() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Disallow:
                """, AGENT);

        assertTrue(policy.isAllowed("/anything"));
    }

    @Test
    @DisplayName("Disallow: / blocks the whole site")
    void disallowAll() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Disallow: /
                """, AGENT);

        assertFalse(policy.isAllowed("/"));
        assertFalse(policy.isAllowed("/anything"));
    }

    @Test
    @DisplayName("comments and blank lines are ignored")
    void commentsAreIgnored() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                # our robots file
                User-agent: *   # everyone

                Disallow: /private/  # keep out
                """, AGENT);

        assertFalse(policy.isAllowed("/private/x"));
        assertTrue(policy.isAllowed("/public"));
    }

    @Test
    @DisplayName("a * inside a rule path matches across segments")
    void wildcardInsideRule() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Disallow: /*/draft/
                """, AGENT);

        assertFalse(policy.isAllowed("/docs/draft/page"));
        assertTrue(policy.isAllowed("/docs/final/page"));
    }

    @Test
    @DisplayName("$ anchors a rule to the end of the path")
    void dollarAnchorsRule() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Disallow: /*.pdf$
                """, AGENT);

        assertFalse(policy.isAllowed("/docs/report.pdf"));
        assertTrue(policy.isAllowed("/docs/report.pdf.html"));
    }

    @Test
    @DisplayName("Crawl-delay is read and applies to our group")
    void readsCrawlDelay() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Crawl-delay: 2
                """, AGENT);

        assertEquals(Duration.ofSeconds(2), policy.crawlDelay().orElseThrow());
    }

    @Test
    @DisplayName("a fractional Crawl-delay is honoured")
    void readsFractionalCrawlDelay() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Crawl-delay: 0.5
                """, AGENT);

        assertEquals(Duration.ofMillis(500), policy.crawlDelay().orElseThrow());
    }

    @Test
    @DisplayName("an absurd or malformed Crawl-delay is ignored rather than stalling the run")
    void ignoresUnusableCrawlDelay() {
        assertTrue(RobotsPolicy.parse("User-agent: *\nCrawl-delay: 99999", AGENT).crawlDelay().isEmpty());
        assertTrue(RobotsPolicy.parse("User-agent: *\nCrawl-delay: soon", AGENT).crawlDelay().isEmpty());
        assertTrue(RobotsPolicy.parse("User-agent: *\nCrawl-delay: -1", AGENT).crawlDelay().isEmpty());
    }

    @Test
    @DisplayName("Sitemap directives are collected regardless of group")
    void collectsSitemaps() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                Sitemap: https://example.com/sitemap.xml

                User-agent: *
                Disallow: /private/
                Sitemap: https://example.com/sitemap-news.xml
                """, AGENT);

        assertEquals(2, policy.sitemaps().size());
        assertTrue(policy.sitemaps().contains("https://example.com/sitemap.xml"));
    }

    @Test
    @DisplayName("a path outside every rule is allowed")
    void unmatchedPathIsAllowed() {
        RobotsPolicy policy = RobotsPolicy.parse("User-agent: *\nDisallow: /private/", AGENT);

        assertTrue(policy.isAllowed(null));
        assertTrue(policy.isAllowed(""));
        assertTrue(policy.isAllowed("/"));
    }

    @Test
    @DisplayName("unknown directives do not disturb parsing")
    void unknownDirectivesIgnored() {
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Host: example.com
                Request-rate: 1/10s
                Disallow: /private/
                """, AGENT);

        assertFalse(policy.isAllowed("/private/x"));
        assertTrue(policy.isAllowed("/public"));
    }

    @Test
    @DisplayName("a pathological robots.txt is bounded rather than absorbed whole")
    void boundedRuleCount() {
        StringBuilder huge = new StringBuilder("User-agent: *\n");
        for (int i = 0; i < 5_000; i++) {
            huge.append("Disallow: /path").append(i).append("/\n");
        }

        RobotsPolicy policy = RobotsPolicy.parse(huge.toString(), AGENT);

        assertFalse(policy.isAllowed("/path0/x"), "rules within the cap still apply");
        assertTrue(policy.isAllowed("/unlisted"));
        // Both assertions above hold with the cap removed, which is what made this
        // test vacuous: the bound itself is what it exists to pin.
        assertTrue(policy.isAllowed("/path4999/x"),
                "a rule past the cap must be dropped, or one robots.txt can pin unbounded memory per host");
    }

    @Test
    @DisplayName("a robots.txt written on Windows is still read")
    void byteOrderMarkDoesNotSwallowTheFile() {
        // A leading BOM made the first field "?user-agent", so no group opened and
        // every rule was dropped — turning a site that forbids crawling into one
        // that appears to allow it.
        String withBom = (char) 0xFEFF + """
                User-agent: *
                Disallow: /private/
                """;
        RobotsPolicy policy = RobotsPolicy.parse(withBom, AGENT);

        assertFalse(policy.isAllowed("/private/secret"));
        assertTrue(policy.isAllowed("/public/page"));
    }

    @Test
    @DisplayName("an empty Disallow in this crawler's own group grants full access over a wildcard block")
    void emptyDisallowGroupOverridesWildcard() {
        // The standard way to let one crawler in while keeping the rest out. The group
        // has no rules at all, so deciding "is there a group for us" by counting its
        // rules fell back to the wildcard block and crawled nothing.
        RobotsPolicy policy = RobotsPolicy.parse("""
                User-agent: *
                Disallow: /

                User-agent: EDDI-Crawler
                Disallow:
                """, AGENT);

        assertTrue(policy.isAllowed("/docs/guide"));
        assertTrue(policy.isAllowed("/"));
    }
}
