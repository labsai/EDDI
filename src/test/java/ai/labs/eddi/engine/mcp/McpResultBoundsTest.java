/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MCP tool results are bounded")
class McpResultBoundsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String conversation(int steps) {
        StringBuilder json = new StringBuilder("{\"conversationState\":\"READY\",\"conversationSteps\":[");
        for (int i = 0; i < steps; i++) {
            json.append(i == 0 ? "" : ",").append("{\"n\":").append(i).append(",\"text\":\"").append("x".repeat(40)).append("\"}");
        }
        return json.append("],\"conversationOutputs\":[{\"o\":1},{\"o\":2},{\"o\":3}]}").toString();
    }

    @Test
    @DisplayName("a result within the cap is returned as it is")
    void withinCapIsUntouched() {
        String json = "{\"a\":1}";
        assertSame(json, McpToolUtils.bounded(json, 100, "hint", "a"));
        assertSame("text", McpToolUtils.boundedText("text", 100, "hint"));
    }

    @Test
    @DisplayName("a JSON result over the cap is valid JSON of its own shape: the newest list entries, whole, with truncated/total/returned/hint")
    void overCapKeepsTheNewestEntries() throws Exception {
        String json = conversation(200);

        String bounded = McpToolUtils.bounded(json, 1_000, "Ask for less.", "conversationSteps", "conversationOutputs");

        assertTrue(bounded.length() <= 1_000, "fits the cap: " + bounded.length());
        JsonNode node = MAPPER.readTree(bounded); // valid JSON, never cut inside a value
        assertEquals("READY", node.get("conversationState").asText(), "the document keeps its normal shape");
        assertTrue(node.get("truncated").asBoolean());
        assertEquals(json.length(), node.get("totalChars").asInt());
        assertEquals("Ask for less.", node.get("hint").asText());
        assertEquals(200, node.get("totalConversationSteps").asInt());
        int returned = node.get("returnedConversationSteps").asInt();
        assertTrue(returned > 0 && returned < 200, "some newest steps: " + returned);
        assertEquals(returned, node.get("conversationSteps").size());
        assertEquals(199, node.get("conversationSteps").get(returned - 1).get("n").asInt(), "the newest step is kept");
        assertEquals(199 - returned + 1, node.get("conversationSteps").get(0).get("n").asInt(), "a contiguous tail");
    }

    @Test
    @DisplayName("a document that cannot be shortened by its lists becomes the small truncated envelope, still valid JSON")
    void unshortenableBecomesAnEnvelope() throws Exception {
        String json = "{\"blob\":\"" + "y".repeat(5_000) + "\"}";

        JsonNode node = MAPPER.readTree(McpToolUtils.bounded(json, 500, "Too big.", "items"));

        assertTrue(node.get("truncated").asBoolean());
        assertEquals("Too big.", node.get("hint").asText());
        assertFalse(node.has("blob"));
    }

    @Test
    @DisplayName("a result too large to re-parse is answered with the envelope alone, without building a tree of it")
    void oversizedIsNotParsed() throws Exception {
        // Not even valid JSON: had it been parsed, this would also end in the envelope
        // —
        // but through the parse failure. The text length decides first.
        String huge = "{\"conversationSteps\":[" + "z".repeat(McpToolUtils.MAX_BOUNDABLE_CHARS);

        JsonNode node = MAPPER.readTree(McpToolUtils.bounded(huge, 1_000, "Ask for less.", "conversationSteps"));

        assertTrue(node.get("truncated").asBoolean());
        assertEquals(huge.length(), node.get("totalChars").asInt());
        assertEquals(3, node.size(), "only truncated, totalChars and hint");
    }

    @Test
    @DisplayName("the kept entries are whole values: a surrogate pair is never split")
    void shorteningKeepsSurrogatePairsWhole() throws Exception {
        StringBuilder json = new StringBuilder("{\"conversationSteps\":[");
        for (int i = 0; i < 300; i++) {
            json.append(i == 0 ? "" : ",").append("{\"t\":\"").append("😀".repeat(10)).append("\"}");
        }
        json.append("]}");

        String bounded = McpToolUtils.bounded(json.toString(), 2_000, "h", "conversationSteps");

        JsonNode node = MAPPER.readTree(bounded);
        for (JsonNode step : node.get("conversationSteps")) {
            assertEquals("😀".repeat(10), step.get("t").asText(), "every kept entry is whole, its surrogate pairs too");
        }
    }

    @Test
    @DisplayName("a text result over the cap is cut at a line end and ends in an explicit truncated marker with the hint")
    void overCapTextHasAMarker() {
        String text = McpToolUtils.boundedText(("line of text\n").repeat(20), 100, "Use a smaller logSize.");

        assertTrue(text.contains("\n[truncated: true"), text);
        String kept = text.substring(0, text.indexOf("\n[truncated"));
        assertTrue(kept.length() <= 100 && kept.endsWith("line of text"), "cut at a line end: " + kept);
        assertTrue(text.endsWith("Use a smaller logSize.]"), text);
    }

    @Test
    @DisplayName("a text cut never splits a surrogate pair")
    void textCutKeepsSurrogatePairs() {
        String text = "a".repeat(99) + "😀" + "b".repeat(50); // an emoji straddling the cap

        String cut = McpToolUtils.boundedText(text, 100, "hint");

        String kept = cut.substring(0, cut.indexOf("\n[truncated"));
        assertFalse(Character.isHighSurrogate(kept.charAt(kept.length() - 1)), "no dangling half of a pair");
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
