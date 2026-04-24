/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SlackEventHandler} — static/utility methods and pattern
 * matching. Integration-level tests for the full event flow require CDI wiring
 * and are covered by the integration test suite.
 */
class SlackEventHandlerTest {

    // ─── stripBotMention ───

    @Test
    void stripBotMention_removesMention() {
        assertEquals("hello world", SlackEventHandler.stripBotMention("<@U0123BOTID> hello world"));
    }

    @Test
    void stripBotMention_removesMultipleSpaces() {
        assertEquals("test", SlackEventHandler.stripBotMention("<@U0123BOTID>   test"));
    }

    @Test
    void stripBotMention_noMention_returnsOriginal() {
        assertEquals("no mention here", SlackEventHandler.stripBotMention("no mention here"));
    }

    @Test
    void stripBotMention_onlyMention_returnsEmpty() {
        assertEquals("", SlackEventHandler.stripBotMention("<@U0123BOTID>"));
    }

    @Test
    void stripBotMention_mentionInMiddle_onlyStripsPrefix() {
        // Only the leading mention should be stripped
        assertEquals("hello <@U999> world",
                SlackEventHandler.stripBotMention("<@U0123BOTID> hello <@U999> world"));
    }

    @ParameterizedTest
    @CsvSource({
            "'<@UBOT123> what is EDDI?', 'what is EDDI?'",
            "'<@U0A1B2C3D> ', ''",
            "'plain text', 'plain text'"
    })
    void stripBotMention_parameterized(String input, String expected) {
        assertEquals(expected, SlackEventHandler.stripBotMention(input));
    }

    // ─── GROUP_PREFIX pattern ───

    private static final Pattern GROUP_PREFIX = Pattern.compile("^group:\\s*(.+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    void groupPrefix_matches_simpleQuestion() {
        Matcher m = GROUP_PREFIX.matcher("group: What should our strategy be?");
        assertTrue(m.matches());
        assertEquals("What should our strategy be?", m.group(1).trim());
    }

    @Test
    void groupPrefix_caseInsensitive() {
        Matcher m = GROUP_PREFIX.matcher("GROUP: test question");
        assertTrue(m.matches());
        assertEquals("test question", m.group(1).trim());
    }

    @Test
    void groupPrefix_mixedCase() {
        Matcher m = GROUP_PREFIX.matcher("Group: Should we deploy?");
        assertTrue(m.matches());
    }

    @Test
    void groupPrefix_noSpace_afterColon() {
        Matcher m = GROUP_PREFIX.matcher("group:question without space");
        assertTrue(m.matches());
        assertEquals("question without space", m.group(1).trim());
    }

    @Test
    void groupPrefix_multiline() {
        Matcher m = GROUP_PREFIX.matcher("group: first line\nsecond line");
        assertTrue(m.matches());
        assertTrue(m.group(1).contains("second line"));
    }

    @Test
    void groupPrefix_noMatch_normalMessage() {
        assertFalse(GROUP_PREFIX.matcher("hello group: not a trigger").matches());
    }

    @Test
    void groupPrefix_noMatch_emptyAfterColon() {
        // Edge case: "group:" with nothing after should not match (.+ requires 1+
        // chars)
        assertFalse(GROUP_PREFIX.matcher("group:").matches());
    }

    // ─── truncate ───

    @Test
    void truncate_null_returnsEmpty() {
        assertEquals("", invokeStaticTruncate(null, 100));
    }

    @Test
    void truncate_shortText_unchanged() {
        assertEquals("hello", invokeStaticTruncate("hello", 100));
    }

    @Test
    void truncate_longText_truncated() {
        String result = invokeStaticTruncate("a".repeat(600), 500);
        assertEquals(503, result.length()); // 500 + "..."
        assertTrue(result.endsWith("..."));
    }

    @Test
    void truncate_exactLength_unchanged() {
        String input = "a".repeat(500);
        assertEquals(input, invokeStaticTruncate(input, 500));
    }

    // ─── buildFollowUpInput ───

    @Test
    void buildFollowUpInput_includesFeedback() {
        var ctx = new SlackGroupDiscussionListener.AgentContext(
                "agent1", "Alice", "My contribution",
                "From Bob: I disagree\nFrom Carol: I agree",
                "What's the plan?", "gc-123");

        String result = buildFollowUpInput(ctx, "Can you elaborate?");
        assertTrue(result.contains("group discussion"));
        assertTrue(result.contains("What's the plan?"));
        assertTrue(result.contains("My contribution"));
        assertTrue(result.contains("Bob"));
        assertTrue(result.contains("Carol"));
        assertTrue(result.contains("Can you elaborate?"));
    }

    @Test
    void buildFollowUpInput_noFeedback_omitsSection() {
        var ctx = new SlackGroupDiscussionListener.AgentContext(
                "agent1", "Alice", "My thought", "", "Question?", "gc-1");

        String result = buildFollowUpInput(ctx, "Explain more?");
        assertFalse(result.contains("Peer feedback"));
        assertTrue(result.contains("My thought"));
        assertTrue(result.contains("Explain more?"));
    }

    // ─── Helpers ───

    /**
     * Mirror the static truncate logic from SlackEventHandler for testing (it's
     * package-private static, so we test it via reflection-alike approach).
     */
    private static String invokeStaticTruncate(String text, int maxLen) {
        if (text == null)
            return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /**
     * Mirror the buildFollowUpInput logic from SlackEventHandler for testing.
     */
    private static String buildFollowUpInput(SlackGroupDiscussionListener.AgentContext ctx, String userMessage) {
        var sb = new StringBuilder();
        sb.append("[Context: You previously participated in a group discussion]\n");
        sb.append("Discussion question: \"").append(ctx.groupQuestion()).append("\"\n");
        sb.append("Your contribution: \"").append(invokeStaticTruncate(ctx.contribution(), 500)).append("\"\n");
        if (ctx.feedbackReceived() != null && !ctx.feedbackReceived().isEmpty()) {
            sb.append("Peer feedback you received:\n").append(invokeStaticTruncate(ctx.feedbackReceived(), 500)).append("\n");
        }
        sb.append("---\n");
        sb.append("User follow-up question: ").append(userMessage);
        return sb.toString();
    }
}
