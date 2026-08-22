/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ToolResponseLimits;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for {@link ToolResponseTruncator}.
 */
class ToolResponseTruncatorTest {

    private ToolResponseTruncator truncator;
    private SimpleMeterRegistry meterRegistry;

    private static final String TASK_TYPE = "openai";
    private static final Map<String, String> TASK_PARAMS = Map.of(
            "apiKey", "sk-test", "modelName", "gpt-4o");

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        truncator = new ToolResponseTruncator(meterRegistry, mock(ChatModelRegistry.class));
    }

    @Test
    void truncateIfNeeded_nullLimits_returnsOriginal() {
        String result = truncator.truncateIfNeeded("myTool", "some response", null, TASK_TYPE, TASK_PARAMS);
        assertEquals("some response", result);
    }

    @Test
    void truncateIfNeeded_nullResult_returnsNull() {
        var limits = new ToolResponseLimits();
        assertNull(truncator.truncateIfNeeded("myTool", null, limits, TASK_TYPE, TASK_PARAMS));
    }

    @Test
    void truncateIfNeeded_withinLimit_returnsOriginal() {
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(100);
        String input = "short response";
        assertEquals(input, truncator.truncateIfNeeded("myTool", input, limits, TASK_TYPE, TASK_PARAMS));
    }

    @Test
    void truncateIfNeeded_exceedsLimit_truncates() {
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(10);
        String input = "a".repeat(100);

        String result = truncator.truncateIfNeeded("myTool", input, limits, TASK_TYPE, TASK_PARAMS);

        assertTrue(result.startsWith("a".repeat(10)));
        assertTrue(result.contains("[TRUNCATED"));
        assertTrue(result.contains("100 characters"));
        assertTrue(result.contains("limit is 10"));
    }

    @Test
    void truncateIfNeeded_perToolOverride_usesOverride() {
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(1000);
        limits.setPerToolLimits(Map.of("verboseTool", 5));
        String input = "a".repeat(50);

        // Tool with override
        String result = truncator.truncateIfNeeded("verboseTool", input, limits, TASK_TYPE, TASK_PARAMS);
        assertTrue(result.contains("[TRUNCATED"));

        // Tool without override uses default (1000) — not truncated
        String result2 = truncator.truncateIfNeeded("otherTool", input, limits, TASK_TYPE, TASK_PARAMS);
        assertEquals(input, result2);
    }

    @Test
    void truncateIfNeeded_exactlyAtLimit_noTruncation() {
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(10);
        String input = "a".repeat(10);
        assertEquals(input, truncator.truncateIfNeeded("myTool", input, limits, TASK_TYPE, TASK_PARAMS));
    }

    @Test
    void truncateIfNeeded_incrementsMetric() {
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(5);
        String input = "a".repeat(100);

        truncator.truncateIfNeeded("webscraper", input, limits, TASK_TYPE, TASK_PARAMS);

        var counter = meterRegistry.find("eddi.mcp.response.truncation.count").tag("tool", "webscraper").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void truncateIfNeeded_defaultMaxChars_is50000() {
        var limits = new ToolResponseLimits();
        assertEquals(50000, limits.getDefaultMaxChars());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // A per-tool entry that is present but null. This is the authoritative guard
    // — ToolLoopRunner drops such entries before it gets here, but nothing
    // obliges every caller to.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a per-tool entry present but null falls back to the default limit")
    void truncateIfNeeded_nullPerToolEntry_usesDefaultLimit() {
        // perToolLimits is deserialized from agent JSON, where
        // {"myTool": null} is a legal document. The containsKey-then-get form
        // unboxed that null and turned one character of config into an NPE that
        // failed the whole turn.
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(10);
        var perToolLimits = new HashMap<String, Integer>();
        perToolLimits.put("myTool", null);
        limits.setPerToolLimits(perToolLimits);

        String result = truncator.truncateIfNeeded("myTool", "a".repeat(100), limits, TASK_TYPE, TASK_PARAMS);

        assertTrue(result.startsWith("a".repeat(10)), result);
        assertTrue(result.contains("limit is 10"),
                "the default ceiling must be what applies, not a limit derived from the null entry: " + result);
    }

    @Test
    @DisplayName("a null entry for one tool leaves another tool's own override intact")
    void truncateIfNeeded_nullPerToolEntry_doesNotDisturbOtherTools() {
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(1000);
        var perToolLimits = new HashMap<String, Integer>();
        perToolLimits.put("brokenEntry", null);
        perToolLimits.put("verboseTool", 5);
        limits.setPerToolLimits(perToolLimits);
        String input = "a".repeat(50);

        assertTrue(truncator.truncateIfNeeded("verboseTool", input, limits, TASK_TYPE, TASK_PARAMS).contains("limit is 5"),
                "the sibling override must still apply");
        assertEquals(input, truncator.truncateIfNeeded("brokenEntry", input, limits, TASK_TYPE, TASK_PARAMS),
                "and the null entry must read as 'no limit configured', leaving the 1000-char default in force");
    }
}
