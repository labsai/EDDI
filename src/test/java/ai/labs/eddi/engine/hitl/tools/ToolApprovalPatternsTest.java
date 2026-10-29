/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.tools;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

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
    void stars_inTheMiddleAndAtBothEnds() {
        assertTrue(ToolApprovalPatterns.compile("*mid*").matcher("amidb").matches());
        assertTrue(ToolApprovalPatterns.compile("*mid*").matcher("mid").matches());
        assertFalse(ToolApprovalPatterns.compile("*mid*").matcher("mi d").matches());
        assertTrue(ToolApprovalPatterns.compile("a*b*c").matcher("abc").matches());
        assertTrue(ToolApprovalPatterns.compile("a*b*c").matcher("axxbyybzc").matches());
        assertFalse(ToolApprovalPatterns.compile("a*b*c").matcher("acb").matches());
        // the last segment must sit at the end, not merely somewhere after the others
        assertTrue(ToolApprovalPatterns.compile("a*bc*bc").matcher("abcbc").matches());
        assertFalse(ToolApprovalPatterns.compile("a*bc*bc").matcher("abc").matches(), "the two bc segments cannot share characters");
        assertTrue(ToolApprovalPatterns.compile("**x**").matcher("x").matches());
        assertTrue(ToolApprovalPatterns.compile("http.*:/a/*").matcher("http.post:/a/b").matches());
    }

    /**
     * The atomic-group translation must mean exactly what one greedy {@code .*} per
     * star meant. Compare the two over every short glob and input on a small
     * alphabet, where overlaps between segments are dense.
     */
    @Test
    void compile_isEquivalentToTheNaiveTranslation() {
        char[] alphabet = {'a', 'b', '*'};
        List<String> globs = allStrings(alphabet, 6);
        List<String> inputs = allStrings(new char[]{'a', 'b'}, 7);
        for (String glob : globs) {
            Pattern safe = ToolApprovalPatterns.compile(glob);
            Pattern naive = naive(glob);
            for (String input : inputs) {
                assertEquals(naive.matcher(input).matches(), safe.matcher(input).matches(), () -> "glob '" + glob + "' on '" + input + "'");
            }
        }
    }

    /**
     * Many stars and a suffix that never occurs: the naive regex tries every way of
     * splitting the input between the stars. Both the save-time validation (which
     * matches the prefix against every HTTP method) and the runtime gate hit this
     * path.
     */
    @Test
    void manyStarsAndAnImpossibleSuffix_finishPromptly() {
        String glob = "a*".repeat(60) + "z";
        String input = "a".repeat(200);
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertFalse(ToolApprovalPatterns.compile(glob).matcher(input).matches());
            assertTrue(ToolApprovalPatterns.validate("h*t*t*p*.*p*o*s*t*h*t*t*p*.*p*o*s*t*h*t*t*p*.*p*o*s*t*x:/a").isPresent());
        });
    }

    private static Pattern naive(String glob) {
        String[] parts = glob.split("\\*", -1);
        StringBuilder sb = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(".*");
            }
            if (!parts[i].isEmpty()) {
                sb.append(Pattern.quote(parts[i]));
            }
        }
        return Pattern.compile(sb.append("$").toString());
    }

    private static List<String> allStrings(char[] alphabet, int maxLength) {
        List<String> out = new ArrayList<>();
        out.add("");
        int from = 0;
        for (int len = 1; len <= maxLength; len++) {
            int to = out.size();
            for (int i = from; i < to; i++) {
                for (char c : alphabet) {
                    out.add(out.get(i) + c);
                }
            }
            from = to;
        }
        return out;
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
}
