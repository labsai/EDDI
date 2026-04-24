/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ToolResponseLimits;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolResponseTruncator}.
 */
class ToolResponseTruncatorTest {

    private ToolResponseTruncator truncator;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        truncator = new ToolResponseTruncator(meterRegistry);
    }

    @Test
    void truncateIfNeeded_nullLimits_returnsOriginal() {
        String result = truncator.truncateIfNeeded("myTool", "some response", null);
        assertEquals("some response", result);
    }

    @Test
    void truncateIfNeeded_nullResult_returnsNull() {
        var limits = new ToolResponseLimits();
        assertNull(truncator.truncateIfNeeded("myTool", null, limits));
    }

    @Test
    void truncateIfNeeded_withinLimit_returnsOriginal() {
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(100);
        String input = "short response";
        assertEquals(input, truncator.truncateIfNeeded("myTool", input, limits));
    }

    @Test
    void truncateIfNeeded_exceedsLimit_truncates() {
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(10);
        String input = "a".repeat(100);

        String result = truncator.truncateIfNeeded("myTool", input, limits);

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
        String result = truncator.truncateIfNeeded("verboseTool", input, limits);
        assertTrue(result.contains("[TRUNCATED"));

        // Tool without override uses default (1000) — not truncated
        String result2 = truncator.truncateIfNeeded("otherTool", input, limits);
        assertEquals(input, result2);
    }

    @Test
    void truncateIfNeeded_exactlyAtLimit_noTruncation() {
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(10);
        String input = "a".repeat(10);
        assertEquals(input, truncator.truncateIfNeeded("myTool", input, limits));
    }

    @Test
    void truncateIfNeeded_incrementsMetric() {
        var limits = new ToolResponseLimits();
        limits.setDefaultMaxChars(5);
        String input = "a".repeat(100);

        truncator.truncateIfNeeded("webscraper", input, limits);

        var counter = meterRegistry.find("eddi.mcp.response.truncation.count").tag("tool", "webscraper").counter();
        assertNotNull(counter);
        assertEquals(1.0, counter.count());
    }

    @Test
    void truncateIfNeeded_defaultMaxChars_is50000() {
        var limits = new ToolResponseLimits();
        assertEquals(50000, limits.getDefaultMaxChars());
    }
}
