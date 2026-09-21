/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.crawl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UrlPattern} — crawl exclusions.
 *
 * <p>
 * Both defects covered here failed silently, which is the worst way for an
 * exclusion to fail: the operator believes the URLs are skipped.
 */
class UrlPatternTest {

    private static boolean matches(String glob, String path) {
        return UrlPattern.compile(glob).orElseThrow().matches(path);
    }

    @Test
    @DisplayName("*.pdf excludes PDFs — the documented pattern that could never match")
    void suffixPatternWorks() {
        // The draft matched the whole lowercased URL, where "*" cannot cross the
        // slashes in "https://host/", so "*.pdf" matched nothing at all while its
        // own Javadoc claimed it did.
        assertTrue(matches("*.pdf", "/docs/report.pdf"));
        assertTrue(matches("*.pdf", "/report.pdf"));
        assertTrue(matches("*.pdf", "/a/b/c/deep.pdf"));
        assertFalse(matches("*.pdf", "/docs/report.html"));
    }

    @Test
    @DisplayName("a regex metacharacter in a pattern is escaped, not compiled")
    void metacharactersAreEscaped() {
        // The draft escaped only "."; a "+" or "(" threw PatternSyntaxException from
        // inside the crawl loop, where a blanket catch logged it as a fetch error for
        // the current page and dropped all of that page's links. One bad pattern
        // reduced the crawl to its seed URL.
        assertTrue(UrlPattern.compile("**/v1+/**").isPresent());
        assertTrue(UrlPattern.compile("**/a(b)/**").isPresent());
        assertTrue(UrlPattern.compile("**/[draft]/**").isPresent());
        assertTrue(UrlPattern.compile("**/a{2}/**").isPresent());

        assertTrue(matches("**/v1+/**", "/api/v1+/users"));
        assertFalse(matches("**/v1+/**", "/api/v11/users"), "the + must be literal, not a quantifier");
    }

    @Test
    @DisplayName("* stays inside one path segment")
    void singleStarDoesNotCrossSlash() {
        assertTrue(matches("/docs/*", "/docs/intro"));
        assertFalse(matches("/docs/*", "/docs/guide/intro"));
    }

    @Test
    @DisplayName("** crosses path segments")
    void doubleStarCrossesSlash() {
        assertTrue(matches("/docs/**", "/docs/guide/intro"));
        assertTrue(matches("/docs/**", "/docs/a/b/c"));
        assertTrue(matches("**/api/**", "/v2/api/users/list"));
    }

    @Test
    @DisplayName("? matches exactly one character")
    void questionMarkMatchesOneCharacter() {
        assertTrue(matches("/v?/docs", "/v1/docs"));
        assertFalse(matches("/v?/docs", "/v12/docs"));
        assertFalse(matches("/v?/docs", "/v/docs"));
    }

    @Test
    @DisplayName("an anchored pattern matches the whole path, not a prefix")
    void patternsAreFullMatches() {
        assertTrue(matches("/private", "/private"));
        assertFalse(matches("/private", "/private/docs"), "use /private/** to include children");
        assertTrue(matches("/private**", "/private/docs"));
    }

    @Test
    @DisplayName("unusable patterns are dropped rather than breaking the crawl")
    void unusablePatternsAreDropped() {
        assertTrue(UrlPattern.compile(null).isEmpty());
        assertTrue(UrlPattern.compile("").isEmpty());
        assertTrue(UrlPattern.compile("   ").isEmpty());
        assertTrue(UrlPattern.compile("x".repeat(600)).isEmpty(), "over-long patterns are a backtracking risk");
    }

    @Test
    @DisplayName("compiling a list skips the unusable entries and keeps the rest")
    void compileAllSkipsBadEntries() {
        List<UrlPattern> patterns = UrlPattern.compileAll(Arrays.asList("*.pdf", null, "", "/private/**"));

        assertEquals(2, patterns.size());
        assertTrue(UrlPattern.anyMatches(patterns, "/a/b.pdf"));
        assertTrue(UrlPattern.anyMatches(patterns, "/private/x"));
        assertFalse(UrlPattern.anyMatches(patterns, "/public/x"));
    }

    @Test
    @DisplayName("a null pattern list is no exclusions, not a failure")
    void nullListIsEmpty() {
        assertTrue(UrlPattern.compileAll(null).isEmpty());
        assertFalse(UrlPattern.anyMatches(List.of(), "/anything"));
    }

    @Test
    @DisplayName("a null path never matches")
    void nullPathNeverMatches() {
        assertFalse(UrlPattern.compile("**").orElseThrow().matches(null));
    }

    @Test
    @DisplayName("a long adversarial path does not hang the matcher")
    void pathologicalInputTerminates() {
        // Bounded pattern length keeps the compiled regex simple enough that even a
        // deliberately awkward path resolves promptly.
        UrlPattern pattern = UrlPattern.compile("**/a**/b**/c**/d**").orElseThrow();
        String path = "/" + "a".repeat(2000);

        long start = System.nanoTime();
        pattern.matches(path);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 2000, "matching took " + elapsedMillis + "ms — possible backtracking blowup");
    }

    @Test
    @DisplayName("the pattern reports its own source for diagnostics")
    void exposesSource() {
        assertEquals("*.pdf", UrlPattern.compile("*.pdf").orElseThrow().source());
        assertEquals("*.pdf", UrlPattern.compile("*.pdf").orElseThrow().toString());
    }
}
