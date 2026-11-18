/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MCP tool results are bounded")
class McpResultBoundsTest {

    @Test
    @DisplayName("a result within the cap is returned as it is")
    void withinCapIsUntouched() {
        String json = "{\"a\":1}";
        assertSame(json, McpToolUtils.bounded(json, 100, "hint"));
        assertSame("text", McpToolUtils.boundedText("text", 100, "hint"));
    }

    @Test
    @DisplayName("a JSON result over the cap becomes a valid envelope that says truncated:true and how to ask for less")
    void overCapBecomesAnEnvelope() throws Exception {
        String json = "{\"k\":\"" + "x\"y".repeat(100) + "\"}";

        String bounded = McpToolUtils.bounded(json, 50, "Ask for less.");

        var node = new ObjectMapper().readTree(bounded);
        assertTrue(node.get("truncated").asBoolean());
        assertEquals(json.length(), node.get("totalChars").asInt());
        assertEquals(50, node.get("returnedChars").asInt());
        assertEquals("Ask for less.", node.get("hint").asText());
        assertEquals(json.substring(0, 50), node.get("partial").asText());
    }

    @Test
    @DisplayName("a text result over the cap ends in an explicit truncated marker with the hint")
    void overCapTextHasAMarker() {
        String text = McpToolUtils.boundedText("a".repeat(120), 100, "Use a smaller logSize.");

        assertTrue(text.startsWith("a".repeat(100) + "\n[truncated: true"), text);
        assertTrue(text.contains("returned 100 of 120"), text);
        assertTrue(text.endsWith("Use a smaller logSize.]"), text);
    }

    @Test
    @DisplayName("clampLimit: absent or non-positive is the default, a large value is the maximum")
    void clampLimit() {
        assertEquals(20, McpToolUtils.clampLimit(null, 20, 100));
        assertEquals(20, McpToolUtils.clampLimit(0, 20, 100));
        assertEquals(20, McpToolUtils.clampLimit(-5, 20, 100));
        assertEquals(7, McpToolUtils.clampLimit(7, 20, 100));
        assertEquals(100, McpToolUtils.clampLimit(Integer.MAX_VALUE, 20, 100));
        assertEquals(10, McpToolUtils.clampLimit(null, 50, 10));
    }
}
