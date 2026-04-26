/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolExecutionTrace} covering tool call tracking,
 * metrics accumulation, and summary formatting. Zero external dependencies.
 */
class ToolExecutionTraceTest {

    private ToolExecutionTrace trace;

    @BeforeEach
    void setUp() {
        trace = new ToolExecutionTrace();
    }

    @Nested
    @DisplayName("addToolCall")
    class AddToolCall {

        @Test
        @DisplayName("should track successful call with cost and timing")
        void successfulCall() {
            trace.addToolCall("websearch", "{\"q\":\"test\"}", "results", 150, 0.001, false);

            assertEquals(1, trace.getToolCalls().size());
            assertTrue(trace.getToolCalls().getFirst().isSuccess());
            assertEquals(150, trace.getToolCalls().getFirst().getExecutionTimeMs());
            assertFalse(trace.isHasErrors());
        }

        @Test
        @DisplayName("should accumulate total execution time")
        void accumulatesTime() {
            trace.addToolCall("tool1", "{}", "r1", 100, 0.0, false);
            trace.addToolCall("tool2", "{}", "r2", 200, 0.0, false);

            assertEquals(300, trace.getToolCalls().stream().mapToLong(ToolExecutionTrace.ToolCall::getExecutionTimeMs).sum());
        }

        @Test
        @DisplayName("should count cache hits")
        void cacheHits() {
            trace.addToolCall("calc", "{}", "42", 5, 0.0, true);
            trace.addToolCall("calc", "{}", "42", 3, 0.0, true);
            trace.addToolCall("search", "{}", "results", 200, 0.001, false);

            assertEquals(2, trace.getToolCalls().stream().filter(ToolExecutionTrace.ToolCall::isFromCache).count());
            assertEquals(1, trace.getCacheMisses());
        }

        @Test
        @DisplayName("should accumulate total cost")
        void accumulatesCost() {
            trace.addToolCall("websearch", "{}", "r1", 100, 0.001, false);
            trace.addToolCall("webscraper", "{}", "r2", 200, 0.002, false);

            assertEquals(0.003, trace.getToolCalls().stream().mapToDouble(ToolExecutionTrace.ToolCall::getCost).sum(), 0.0001);
        }
    }

    @Nested
    @DisplayName("addFailedToolCall")
    class AddFailedToolCall {

        @Test
        @DisplayName("should mark trace as having errors")
        void setsHasErrors() {
            trace.addFailedToolCall("badTool", "{}", "Connection refused", 50, 0.0);

            assertTrue(trace.isHasErrors());
            assertEquals(1, trace.getToolCalls().size());
            assertFalse(trace.getToolCalls().getFirst().isSuccess());
            assertEquals("Connection refused", trace.getToolCalls().getFirst().getError());
        }

        @Test
        @DisplayName("should increment cache misses for failed calls")
        void incrementsCacheMisses() {
            trace.addFailedToolCall("tool", "{}", "error", 10, 0.0);

            assertEquals(1, trace.getCacheMisses());
        }
    }

    @Nested
    @DisplayName("ToolMetrics tracking")
    class MetricsTracking {

        @Test
        @DisplayName("should compute per-tool metrics")
        void perToolMetrics() {
            trace.addToolCall("websearch", "{q:1}", "r1", 100, 0.001, false);
            trace.addToolCall("websearch", "{q:2}", "r2", 200, 0.001, false);
            trace.addFailedToolCall("websearch", "{q:3}", "timeout", 300, 0.001);

            var metrics = trace.getToolMetrics().get("websearch");
            assertNotNull(metrics);
            assertEquals(3, metrics.getTotalCalls());
            assertEquals(2, metrics.getSuccessfulCalls());
            assertEquals(1, metrics.getFailedCalls());
        }

        @Test
        @DisplayName("should track min/max execution times")
        void minMaxTimes() {
            trace.addToolCall("tool", "a1", "r1", 50, 0.0, false);
            trace.addToolCall("tool", "a2", "r2", 300, 0.0, false);
            trace.addToolCall("tool", "a3", "r3", 100, 0.0, false);

            var metrics = trace.getToolMetrics().get("tool");
            assertEquals(50, metrics.getMinExecutionTimeMs());
            assertEquals(300, metrics.getMaxExecutionTimeMs());
        }

        @Test
        @DisplayName("should compute success rate")
        void successRate() {
            trace.addToolCall("tool", "a", "r", 10, 0.0, false);
            trace.addToolCall("tool", "a", "r", 10, 0.0, false);
            trace.addFailedToolCall("tool", "a", "err", 10, 0.0);

            var metrics = trace.getToolMetrics().get("tool");
            assertEquals(66.6, metrics.getSuccessRate(), 1.0);
        }

        @Test
        @DisplayName("should compute average execution time")
        void avgTime() {
            trace.addToolCall("tool", "a", "r", 100, 0.0, false);
            trace.addToolCall("tool", "a", "r", 200, 0.0, false);

            var metrics = trace.getToolMetrics().get("tool");
            assertEquals(150.0, metrics.getAverageExecutionTimeMs(), 0.1);
        }

        @Test
        @DisplayName("should count cache hit rate")
        void cacheHitRate() {
            trace.addToolCall("tool", "a", "r", 10, 0.0, true);
            trace.addToolCall("tool", "a", "r", 10, 0.0, false);

            var metrics = trace.getToolMetrics().get("tool");
            assertEquals(50.0, metrics.getCacheHitRate(), 0.1);
        }

        @Test
        @DisplayName("zero calls should return zero rates")
        void zeroRates() {
            var metrics = new ToolExecutionTrace.ToolMetrics();
            assertEquals(0.0, metrics.getSuccessRate());
            assertEquals(0.0, metrics.getAverageExecutionTimeMs());
            assertEquals(0.0, metrics.getCacheHitRate());
        }
    }

    @Nested
    @DisplayName("getSummary")
    class GetSummary {

        @Test
        @DisplayName("should produce formatted summary with metrics")
        void formattedSummary() {
            trace.addToolCall("websearch", "{q:1}", "results", 100, 0.001, false);
            trace.addToolCall("calculator", "{}", "42", 5, 0.0, true);
            trace.addFailedToolCall("badTool", "{}", "error", 50, 0.0);

            String summary = trace.getSummary();
            assertTrue(summary.contains("Tool Execution Summary"));
            assertTrue(summary.contains("Total Calls: 3"));
            assertTrue(summary.contains("Errors: Yes"));
            assertTrue(summary.contains("websearch"));
        }

        @Test
        @DisplayName("should handle empty trace gracefully")
        void emptySummary() {
            String summary = trace.getSummary();
            assertTrue(summary.contains("Total Calls: 0"));
            assertTrue(summary.contains("Errors: No"));
        }
    }

    @Nested
    @DisplayName("ToolCall equality")
    class ToolCallEquality {

        @Test
        @DisplayName("equals and hashCode should work correctly")
        void equalsAndHashCode() {
            var call1 = new ToolExecutionTrace.ToolCall("t", "{}", "r", 10, null, true, 0.0, false, 1000);
            var call2 = new ToolExecutionTrace.ToolCall("t", "{}", "r", 10, null, true, 0.0, false, 1000);

            assertEquals(call1, call2);
            assertEquals(call1.hashCode(), call2.hashCode());
        }

        @Test
        @DisplayName("toString should contain tool name")
        void toStringContainsName() {
            var call = new ToolExecutionTrace.ToolCall("myTool", "{}", "r", 10, null, true, 0.0, false, 1000);
            assertTrue(call.toString().contains("myTool"));
        }
    }

    @Nested
    @DisplayName("ToolExecutionTrace equality")
    class TraceEquality {

        @Test
        @DisplayName("equals should compare field-by-field")
        void equalsWorks() {
            var t1 = new ToolExecutionTrace();
            var t2 = new ToolExecutionTrace();
            assertEquals(t1, t2);

            t1.addToolCall("tool", "{}", "r", 10, 0.0, false);
            assertNotEquals(t1, t2);
        }

        @Test
        @DisplayName("toString should contain trace info")
        void toStringWorks() {
            trace.addToolCall("tool", "{}", "r", 10, 0.0, false);
            assertTrue(trace.toString().contains("ToolExecutionTrace"));
        }
    }
}
