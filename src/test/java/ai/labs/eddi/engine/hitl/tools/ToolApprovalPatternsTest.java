/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ToolApprovalPatternsTest {

    @Test
    void star_matchesAnyRun_includingEmpty() {
        assertTrue(ToolApprovalPatterns.compile("delete_*").matcher("delete_account").matches());
        assertTrue(ToolApprovalPatterns.compile("delete_*").matcher("delete_").matches());
        assertFalse(ToolApprovalPatterns.compile("delete_*").matcher("undelete_x").matches());
        assertTrue(ToolApprovalPatterns.compile("*").matcher("anything").matches());
    }

    @Test
    void literalDotsAreQuoted_notRegexMeta() {
        assertFalse(ToolApprovalPatterns.compile("a.b").matcher("axb").matches());
        assertTrue(ToolApprovalPatterns.compile("a.b").matcher("a.b").matches());
    }

    @Test
    void matchingIsCaseSensitive() {
        assertFalse(ToolApprovalPatterns.compile("Delete_*").matcher("delete_account").matches());
    }

    @Test
    void validate_rejectsBlank_illegalChars_overlong_unknownSource() {
        assertTrue(ToolApprovalPatterns.validate("").isPresent());
        assertTrue(ToolApprovalPatterns.validate("has space").isPresent());
        assertTrue(ToolApprovalPatterns.validate("x".repeat(257)).isPresent());
        var err = ToolApprovalPatterns.validate("mpc:read_*");
        assertTrue(err.isPresent());
        assertTrue(err.get().contains("mcp"), "should suggest 'mcp' for typo 'mpc': " + err.get());
        assertTrue(ToolApprovalPatterns.validate("mcp:read_*").isEmpty());
        assertTrue(ToolApprovalPatterns.validate("plainName").isEmpty());
    }

    @Test
    void validate_rejectsLeadingOrTrailingColon() {
        // ":foo" would compile to a pattern matching nothing at runtime — reject it
        // at save time with an actionable message instead of silently accepting.
        var lead = ToolApprovalPatterns.validate(":foo");
        assertTrue(lead.isPresent());
        assertTrue(lead.get().contains("colon"), lead.get());
        var trail = ToolApprovalPatterns.validate("mcp:");
        assertTrue(trail.isPresent());
        assertTrue(trail.get().contains("colon"), trail.get());
    }

    @Test
    void matches_agreesWithTheRegexForm() {
        String[][] cases = {{"delete_*", "delete_account"}, {"delete_*", "delete_"}, {"delete_*", "undelete_x"}, {"*", ""}, {"*", "anything"},
                {"a.b", "axb"}, {"a.b", "a.b"}, {"*mid*", "xmidy"}, {"*mid*", "xmiy"}, {"http.*:/a/*/c", "http.post:/a/b/c"},
                {"http.*:/a/*/c", "http.post:/a/b/d"}, {"a**b", "ab"}, {"a*b*c", "abcbc"}, {"a*b*c", "acb"}, {"Delete_*", "delete_account"}};
        for (String[] c : cases) {
            assertEquals(ToolApprovalPatterns.compile(c[0]).matcher(c[1]).matches(), ToolApprovalPatterns.matches(c[0], c[1]),
                    c[0] + " vs " + c[1]);
        }
    }

    /**
     * One regex {@code .*} per wildcard backtracks combinatorially on a failing
     * match; the glob matcher's work is bounded by pattern length times input
     * length.
     */
    @Test
    void matches_wildcardHeavyPatternAgainstALongNameFailsFast() {
        String glob = "a*".repeat(100) + "b";
        String name = "a".repeat(5_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertFalse(ToolApprovalPatterns.matches(glob, name)));
    }
}
