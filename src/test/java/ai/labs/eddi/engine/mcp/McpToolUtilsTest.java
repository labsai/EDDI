package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.model.Deployment.Environment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for McpToolUtils — shared MCP utility methods.
 */
class McpToolUtilsTest {

    // --- parseEnvironment ---

    @Test
    void parseEnvironment_validValues() {
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("production"));
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("restricted"));
        assertEquals(Environment.test, McpToolUtils.parseEnvironment("test"));
    }

    @Test
    void parseEnvironment_caseInsensitive() {
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("UNRESTRICTED"));
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("Restricted"));
    }

    @Test
    void parseEnvironment_nullOrBlank_defaultsToUnrestricted() {
        assertEquals(Environment.production, McpToolUtils.parseEnvironment(null));
        assertEquals(Environment.production, McpToolUtils.parseEnvironment(""));
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("   "));
    }

    @Test
    void parseEnvironment_invalid_defaultsToUnrestricted() {
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("invalid"));
        assertEquals(Environment.production, McpToolUtils.parseEnvironment("production"));
    }

    // --- parseIntOrDefault ---

    @Test
    void parseIntOrDefault_validValues() {
        assertEquals(42, McpToolUtils.parseIntOrDefault("42", 0));
        assertEquals(0, McpToolUtils.parseIntOrDefault("0", 99));
        assertEquals(-1, McpToolUtils.parseIntOrDefault("-1", 0));
    }

    @Test
    void parseIntOrDefault_nullOrBlank_returnsDefault() {
        assertEquals(20, McpToolUtils.parseIntOrDefault(null, 20));
        assertEquals(20, McpToolUtils.parseIntOrDefault("", 20));
        assertEquals(20, McpToolUtils.parseIntOrDefault("  ", 20));
    }

    @Test
    void parseIntOrDefault_invalidNumber_returnsDefault() {
        assertEquals(10, McpToolUtils.parseIntOrDefault("abc", 10));
        assertEquals(10, McpToolUtils.parseIntOrDefault("1.5", 10));
    }

    // --- parseBooleanOrDefault ---

    @Test
    void parseBooleanOrDefault_trueValues() {
        assertTrue(McpToolUtils.parseBooleanOrDefault("true", false));
        assertTrue(McpToolUtils.parseBooleanOrDefault("TRUE", false));
        assertTrue(McpToolUtils.parseBooleanOrDefault("True", false));
    }

    @Test
    void parseBooleanOrDefault_falseValues() {
        assertFalse(McpToolUtils.parseBooleanOrDefault("false", true));
        assertFalse(McpToolUtils.parseBooleanOrDefault("anything", true));
    }

    @Test
    void parseBooleanOrDefault_nullOrBlank_returnsDefault() {
        assertTrue(McpToolUtils.parseBooleanOrDefault(null, true));
        assertFalse(McpToolUtils.parseBooleanOrDefault("", false));
        assertTrue(McpToolUtils.parseBooleanOrDefault("  ", true));
    }

    // --- escapeJsonString ---

    @Test
    void escapeJsonString_null_returnsEmpty() {
        assertEquals("", McpToolUtils.escapeJsonString(null));
    }

    @Test
    void escapeJsonString_noSpecialChars() {
        assertEquals("hello world", McpToolUtils.escapeJsonString("hello world"));
    }

    @Test
    void escapeJsonString_quotes() {
        assertEquals("say \\\"hello\\\"", McpToolUtils.escapeJsonString("say \"hello\""));
    }

    @Test
    void escapeJsonString_backslash() {
        assertEquals("path\\\\to\\\\file", McpToolUtils.escapeJsonString("path\\to\\file"));
    }

    @Test
    void escapeJsonString_specialChars() {
        assertEquals("line1\\nline2\\ttab\\rreturn",
                McpToolUtils.escapeJsonString("line1\nline2\ttab\rreturn"));
    }

    @Test
    void escapeJsonString_controlChars() {
        assertEquals("\\u0000\\u001f", McpToolUtils.escapeJsonString("\u0000\u001f"));
    }

    // --- errorJson ---

    @Test
    void errorJson_simpleMessage() {
        String result = McpToolUtils.errorJson("something went wrong");
        assertEquals("{\"error\":\"something went wrong\"}", result);
    }

    @Test
    void errorJson_messageWithQuotes() {
        String result = McpToolUtils.errorJson("value \"foo\" is invalid");
        assertEquals("{\"error\":\"value \\\"foo\\\" is invalid\"}", result);
    }

    @Test
    void errorJson_messageWithNewlines() {
        String result = McpToolUtils.errorJson("line1\nline2");
        assertEquals("{\"error\":\"line1\\nline2\"}", result);
    }
}
