/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SlackWebApiClient}.
 * <p>
 * These tests verify the constructor, exception contract for retryable errors,
 * graceful handling of non-retryable API responses, and the Markdown→mrkdwn
 * converter. Full HTTP integration tests (using WireMock) are in the
 * integration test suite.
 */
class SlackWebApiClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SlackWebApiClient client;

    @BeforeEach
    void setUp() {
        client = new SlackWebApiClient(objectMapper);
    }

    @Test
    void constructor_createsInstance() {
        assertNotNull(client);
    }

    @Test
    void postMessage_invalidToken_returnsNull() {
        // Slack returns HTTP 200 + ok:false for invalid tokens — non-retryable,
        // should return null without throwing
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", null, "test message");
        assertNull(result);
    }

    @Test
    void postMessage_withThreadTs_returnsNull() {
        // Invalid token + thread_ts — verifies thread_ts is included without error
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", "12345.000", "test");
        assertNull(result);
    }

    @Test
    void postMessage_specialCharacters_noSerializationError() {
        // Special chars (control chars, unicode) should be properly escaped by
        // Jackson — the call should reach the API without JSON serialization errors
        String textWithSpecials = "He said \"hello\"\npath\\file\ttab\u0000null";
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", null, textWithSpecials);
        assertNull(result); // API rejects, but no crash
    }

    @Test
    void postMessage_nullText_noNPE() {
        // Null text should be serialized by Jackson as JSON null — should not NPE
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", null, null);
        assertNull(result);
    }

    @Test
    void postMessage_emptyText_returnsNull() {
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", null, "");
        assertNull(result);
    }

    @Test
    void postMessage_longMessage_returnsNull() {
        String longText = "a".repeat(10_000);
        String result = client.postMessage("Bearer xoxb-invalid", "C0123", null, longText);
        assertNull(result);
    }

    // ─── convertMarkdownToSlackMrkdwn ───

    @Test
    void mrkdwn_null_returnsNull() {
        assertNull(SlackWebApiClient.convertMarkdownToSlackMrkdwn(null));
    }

    @Test
    void mrkdwn_empty_returnsEmpty() {
        assertEquals("", SlackWebApiClient.convertMarkdownToSlackMrkdwn(""));
    }

    @Test
    void mrkdwn_bold_converts() {
        assertEquals("This is *bold* text",
                SlackWebApiClient.convertMarkdownToSlackMrkdwn("This is **bold** text"));
    }

    @Test
    void mrkdwn_multipleBold_allConverted() {
        assertEquals("*first* and *second*",
                SlackWebApiClient.convertMarkdownToSlackMrkdwn("**first** and **second**"));
    }

    @Test
    void mrkdwn_heading_h1_convertsToBold() {
        assertEquals("*My Heading*",
                SlackWebApiClient.convertMarkdownToSlackMrkdwn("# My Heading"));
    }

    @Test
    void mrkdwn_heading_h3_convertsToBold() {
        assertEquals("*Sub Section*",
                SlackWebApiClient.convertMarkdownToSlackMrkdwn("### Sub Section"));
    }

    @Test
    void mrkdwn_strikethrough_converts() {
        assertEquals("This is ~deleted~ text",
                SlackWebApiClient.convertMarkdownToSlackMrkdwn("This is ~~deleted~~ text"));
    }

    @Test
    void mrkdwn_horizontalRule_convertsToUnicodeLine() {
        String result = SlackWebApiClient.convertMarkdownToSlackMrkdwn("---");
        assertTrue(result.contains("───"));
    }

    @Test
    void mrkdwn_horizontalRule_asterisks() {
        String result = SlackWebApiClient.convertMarkdownToSlackMrkdwn("***");
        assertTrue(result.contains("───"));
    }

    @Test
    void mrkdwn_codeBlock_preserved() {
        String input = "```java\nSystem.out.println(\"hello\");\n```";
        String result = SlackWebApiClient.convertMarkdownToSlackMrkdwn(input);
        // Code blocks should not have their content converted
        assertTrue(result.contains("System.out.println(\"hello\");"));
        assertTrue(result.contains("```java"));
    }

    @Test
    void mrkdwn_codeBlock_boldNotConverted() {
        String input = "```\n**not bold inside code**\n```";
        String result = SlackWebApiClient.convertMarkdownToSlackMrkdwn(input);
        // Bold inside code should NOT be converted
        assertTrue(result.contains("**not bold inside code**"));
    }

    @Test
    void mrkdwn_table_wrappedInCodeBlock() {
        String input = "| Col A | Col B |\n|---|---|\n| val1 | val2 |";
        String result = SlackWebApiClient.convertMarkdownToSlackMrkdwn(input);
        // Table should be wrapped in ``` for monospace display
        assertTrue(result.startsWith("```"));
        assertTrue(result.contains("val1"));
        // Separator row should be removed
        assertFalse(result.contains("|---|"));
    }

    @Test
    void mrkdwn_table_boldInTableRemoved() {
        String input = "| **Header** | Value |\n|---|---|\n| data | more |";
        String result = SlackWebApiClient.convertMarkdownToSlackMrkdwn(input);
        // Bold markers should be stripped inside tables
        assertTrue(result.contains("Header") && !result.contains("**Header**"));
    }

    @Test
    void mrkdwn_mixedContent_allConverted() {
        String input = "# Title\n\nSome **bold** text with ~~strike~~.\n\n---\n\nA paragraph.";
        String result = SlackWebApiClient.convertMarkdownToSlackMrkdwn(input);
        assertTrue(result.contains("*Title*"));
        assertTrue(result.contains("*bold*"));
        assertTrue(result.contains("~strike~"));
        assertTrue(result.contains("───"));
        assertTrue(result.contains("A paragraph."));
    }

    @Test
    void mrkdwn_plainText_unchanged() {
        assertEquals("Just a normal sentence.",
                SlackWebApiClient.convertMarkdownToSlackMrkdwn("Just a normal sentence."));
    }

    @Test
    void mrkdwn_inlineCode_preserved() {
        // Inline code should pass through unchanged
        assertEquals("Use `git status` to check",
                SlackWebApiClient.convertMarkdownToSlackMrkdwn("Use `git status` to check"));
    }

    @Test
    void mrkdwn_bulletList_preserved() {
        String input = "- item 1\n- item 2";
        String result = SlackWebApiClient.convertMarkdownToSlackMrkdwn(input);
        assertTrue(result.contains("- item 1"));
        assertTrue(result.contains("- item 2"));
    }

    @Test
    void mrkdwn_blockquote_preserved() {
        String input = "> This is a quote";
        assertEquals("> This is a quote",
                SlackWebApiClient.convertMarkdownToSlackMrkdwn(input));
    }

    @Test
    void mrkdwn_emoji_preserved() {
        assertEquals("🟢 Done ✅",
                SlackWebApiClient.convertMarkdownToSlackMrkdwn("🟢 Done ✅"));
    }
}
