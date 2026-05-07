/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.modules.llm.model.LlmConfiguration.ToolResponseLimits;
import ai.labs.eddi.modules.llm.tools.PaginatedResponseStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("ToolResponseTruncator Extended Tests")
class ToolResponseTruncatorExtendedTest {

    private ToolResponseTruncator truncator;
    private PaginatedResponseStore paginatedResponseStore;

    @BeforeEach
    void setUp() {
        truncator = new ToolResponseTruncator(new SimpleMeterRegistry());
        paginatedResponseStore = mock(PaginatedResponseStore.class);

        // Inject mocked store
        try {
            var f = ToolResponseTruncator.class.getDeclaredField("paginatedResponseStore");
            f.setAccessible(true);
            f.set(truncator, paginatedResponseStore);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("Truncate Strategy (default)")
    class TruncateStrategyTests {

        @Test
        @DisplayName("Should not truncate when within limit")
        void testWithinLimit() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(100);

            String result = truncator.truncateIfNeeded("testTool", "short response", limits);
            assertEquals("short response", result);
        }

        @Test
        @DisplayName("Should truncate and append note when exceeding limit")
        void testExceedsLimit() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(10);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(100), limits);
            assertTrue(result.startsWith("AAAAAAAAAA"));
            assertTrue(result.contains("[TRUNCATED:"));
            assertTrue(result.contains("100 characters"));
        }

        @Test
        @DisplayName("Should return null input unchanged")
        void testNullInput() {
            var limits = new ToolResponseLimits();
            assertNull(truncator.truncateIfNeeded("testTool", null, limits));
        }

        @Test
        @DisplayName("Should return result unchanged when limits is null")
        void testNullLimits() {
            assertEquals("content", truncator.truncateIfNeeded("testTool", "content", null));
        }

        @Test
        @DisplayName("Should use per-tool limits when configured")
        void testPerToolLimits() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(1000);
            limits.setPerToolLimits(Map.of("specialTool", 5));

            String result = truncator.truncateIfNeeded("specialTool", "A".repeat(20), limits);
            assertTrue(result.startsWith("AAAAA"));
            assertTrue(result.contains("[TRUNCATED:"));
        }
    }

    @Nested
    @DisplayName("Paginate Strategy")
    class PaginateStrategyTests {

        @Test
        @DisplayName("Should paginate and return first page with responseId")
        void testPaginateResponse() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("paginate");

            when(paginatedResponseStore.store("testTool", "A".repeat(200), 50)).thenReturn("resp-123");
            when(paginatedResponseStore.getPageCount("resp-123")).thenReturn(4);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits);
            assertTrue(result.contains("[PAGINATED:"));
            assertTrue(result.contains("resp-123"));
            assertTrue(result.contains("page 1 of 4"));
        }

        @Test
        @DisplayName("Should fallback to truncate when store returns null")
        void testPaginateFallback() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("paginate");

            when(paginatedResponseStore.store(anyString(), anyString(), anyInt())).thenReturn(null);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback to truncate when store is null")
        void testPaginateNoStore() {
            // Create truncator without store
            var truncatorNoStore = new ToolResponseTruncator(new SimpleMeterRegistry());

            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("paginate");

            String result = truncatorNoStore.truncateIfNeeded("testTool", "A".repeat(200), limits);
            assertTrue(result.contains("[TRUNCATED:"));
        }
    }

    @Nested
    @DisplayName("Summarize Strategy")
    class SummarizeStrategyTests {

        @Test
        @DisplayName("Should fallback to truncate when no summarizer model configured")
        void testSummarizeNoModel() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("summarize");
            // No summarizerModel set

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback when response too large for summarization")
        void testSummarizeTooLarge() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("claude-haiku-4.6");

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(300_000), limits);
            // Should fallback to truncation due to cost ceiling
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should fallback when summarizer model configured but not wired")
        void testSummarizeNotWired() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("summarize");
            limits.setSummarizerModel("claude-haiku-4.6");

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits);
            // Should fallback since summarizer is not yet wired
            assertTrue(result.contains("[TRUNCATED:"));
        }
    }

    @Nested
    @DisplayName("Strategy Selection")
    class StrategySelectionTests {

        @Test
        @DisplayName("Should default to truncate when strategy is null")
        void testNullStrategy() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            // truncationStrategy is null (default)

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Should default to truncate for unknown strategy")
        void testUnknownStrategy() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("unknown_strategy");

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits);
            assertTrue(result.contains("[TRUNCATED:"));
        }

        @Test
        @DisplayName("Strategy selection should be case-insensitive")
        void testCaseInsensitiveStrategy() {
            var limits = new ToolResponseLimits();
            limits.setDefaultMaxChars(50);
            limits.setTruncationStrategy("PAGINATE");

            when(paginatedResponseStore.store(anyString(), anyString(), anyInt())).thenReturn("id-1");
            when(paginatedResponseStore.getPageCount("id-1")).thenReturn(2);

            String result = truncator.truncateIfNeeded("testTool", "A".repeat(200), limits);
            assertTrue(result.contains("[PAGINATED:"));
        }
    }
}
