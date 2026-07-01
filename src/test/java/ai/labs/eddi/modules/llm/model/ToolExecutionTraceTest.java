/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.model;

import ai.labs.eddi.modules.llm.model.ToolExecutionTrace.ToolCall;
import ai.labs.eddi.modules.llm.model.ToolExecutionTrace.ToolMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ToolExecutionTrace Tests")
class ToolExecutionTraceTest {

    @Nested
    @DisplayName("ToolMetrics")
    class ToolMetricsTests {

        @Test
        @DisplayName("getSuccessRate returns 0 when totalCalls is 0")
        void successRateZeroCalls() {
            var metrics = new ToolMetrics();
            assertEquals(0.0, metrics.getSuccessRate());
        }

        @Test
        @DisplayName("getSuccessRate calculates correctly with calls")
        void successRateWithCalls() {
            var metrics = new ToolMetrics("tool1", 10, 8, 2, 1000, 50, 200, 0.5, 3);
            assertEquals(80.0, metrics.getSuccessRate(), 0.01);
        }

        @Test
        @DisplayName("getAverageExecutionTimeMs returns 0 when totalCalls is 0")
        void avgExecTimeZeroCalls() {
            var metrics = new ToolMetrics();
            assertEquals(0.0, metrics.getAverageExecutionTimeMs());
        }

        @Test
        @DisplayName("getAverageExecutionTimeMs calculates correctly")
        void avgExecTimeWithCalls() {
            var metrics = new ToolMetrics("tool1", 4, 4, 0, 400, 50, 200, 0.0, 0);
            assertEquals(100.0, metrics.getAverageExecutionTimeMs(), 0.01);
        }

        @Test
        @DisplayName("getCacheHitRate returns 0 when totalCalls is 0")
        void cacheHitRateZeroCalls() {
            var metrics = new ToolMetrics();
            assertEquals(0.0, metrics.getCacheHitRate());
        }

        @Test
        @DisplayName("getCacheHitRate calculates correctly")
        void cacheHitRateWithCalls() {
            var metrics = new ToolMetrics("tool1", 10, 10, 0, 1000, 50, 200, 0.0, 5);
            assertEquals(50.0, metrics.getCacheHitRate(), 0.01);
        }

        @Test
        @DisplayName("equals returns true for same instance")
        void equalsSameInstance() {
            var m = new ToolMetrics("t", 1, 1, 0, 100, 100, 100, 0.0, 0);
            assertEquals(m, m);
        }

        @Test
        @DisplayName("equals returns false for null")
        void equalsNull() {
            var m = new ToolMetrics("t", 1, 1, 0, 100, 100, 100, 0.0, 0);
            assertNotEquals(null, m);
        }

        @Test
        @DisplayName("equals returns false for different type")
        void equalsDifferentType() {
            var m = new ToolMetrics("t", 1, 1, 0, 100, 100, 100, 0.0, 0);
            assertNotEquals("string", m);
        }

        @Test
        @DisplayName("equals returns true for equal objects")
        void equalsEqual() {
            var m1 = new ToolMetrics("t", 1, 1, 0, 100, 50, 200, 0.5, 1);
            var m2 = new ToolMetrics("t", 1, 1, 0, 100, 50, 200, 0.5, 1);
            assertEquals(m1, m2);
            assertEquals(m1.hashCode(), m2.hashCode());
        }

        @Test
        @DisplayName("equals returns false when fields differ")
        void equalsFieldsDiffer() {
            var m1 = new ToolMetrics("t", 1, 1, 0, 100, 50, 200, 0.5, 1);
            var m2 = new ToolMetrics("t", 2, 1, 0, 100, 50, 200, 0.5, 1);
            assertNotEquals(m1, m2);
        }

        @Test
        @DisplayName("toString contains all field values")
        void toStringContainsFields() {
            var m = new ToolMetrics("myTool", 5, 3, 2, 500, 50, 200, 1.5, 2);
            String str = m.toString();
            assertTrue(str.contains("myTool"));
            assertTrue(str.contains("5"));
            assertTrue(str.contains("500"));
        }

        @Test
        @DisplayName("getters and setters work correctly")
        void gettersAndSetters() {
            var m = new ToolMetrics();
            m.setToolName("tool");
            m.setTotalCalls(10);
            m.setSuccessfulCalls(8);
            m.setFailedCalls(2);
            m.setTotalExecutionTimeMs(1000);
            m.setMinExecutionTimeMs(50);
            m.setMaxExecutionTimeMs(200);
            m.setTotalCost(5.0);
            m.setCacheHits(3);

            assertEquals("tool", m.getToolName());
            assertEquals(10, m.getTotalCalls());
            assertEquals(8, m.getSuccessfulCalls());
            assertEquals(2, m.getFailedCalls());
            assertEquals(1000, m.getTotalExecutionTimeMs());
            assertEquals(50, m.getMinExecutionTimeMs());
            assertEquals(200, m.getMaxExecutionTimeMs());
            assertEquals(5.0, m.getTotalCost());
            assertEquals(3, m.getCacheHits());
        }
    }

    @Nested
    @DisplayName("ToolCall")
    class ToolCallTests {

        @Test
        @DisplayName("equals returns true for same instance")
        void equalsSameInstance() {
            var tc = new ToolCall("t", "{}", "ok", 100, null, true, 0.0, false, 12345);
            assertEquals(tc, tc);
        }

        @Test
        @DisplayName("equals returns false for null")
        void equalsNull() {
            var tc = new ToolCall("t", "{}", "ok", 100, null, true, 0.0, false, 12345);
            assertNotEquals(null, tc);
        }

        @Test
        @DisplayName("equals returns false for different class")
        void equalsDifferentClass() {
            var tc = new ToolCall("t", "{}", "ok", 100, null, true, 0.0, false, 12345);
            assertNotEquals("string", tc);
        }

        @Test
        @DisplayName("equals returns true for equal objects")
        void equalsEqual() {
            var tc1 = new ToolCall("t", "{}", "ok", 100, null, true, 0.1, true, 12345);
            var tc2 = new ToolCall("t", "{}", "ok", 100, null, true, 0.1, true, 12345);
            assertEquals(tc1, tc2);
            assertEquals(tc1.hashCode(), tc2.hashCode());
        }

        @Test
        @DisplayName("equals returns false when fields differ")
        void equalsFieldsDiffer() {
            var tc1 = new ToolCall("t", "{}", "ok", 100, null, true, 0.1, true, 12345);
            var tc2 = new ToolCall("t", "{}", "fail", 100, null, true, 0.1, true, 12345);
            assertNotEquals(tc1, tc2);
        }

        @Test
        @DisplayName("toString contains tool name")
        void toStringContainsName() {
            var tc = new ToolCall("myTool", "{}", "result", 100, null, true, 0.0, false, 0);
            assertTrue(tc.toString().contains("myTool"));
        }

        @Test
        @DisplayName("getters and setters work correctly")
        void gettersAndSetters() {
            var tc = new ToolCall();
            tc.setToolName("tool1");
            tc.setArguments("{}");
            tc.setResult("ok");
            tc.setExecutionTimeMs(100);
            tc.setError("error");
            tc.setSuccess(false);
            tc.setCost(1.5);
            tc.setFromCache(true);
            tc.setTimestamp(99999);

            assertEquals("tool1", tc.getToolName());
            assertEquals("{}", tc.getArguments());
            assertEquals("ok", tc.getResult());
            assertEquals(100, tc.getExecutionTimeMs());
            assertEquals("error", tc.getError());
            assertFalse(tc.isSuccess());
            assertEquals(1.5, tc.getCost());
            assertTrue(tc.isFromCache());
            assertEquals(99999, tc.getTimestamp());
        }
    }

    @Nested
    @DisplayName("ToolExecutionTrace main")
    class TraceTests {

        private ToolExecutionTrace trace;

        @BeforeEach
        void setUp() {
            trace = new ToolExecutionTrace();
        }

        @Test
        @DisplayName("addToolCall increments cache hits when fromCache=true")
        void addToolCallCacheHit() {
            trace.addToolCall("tool1", "{}", "result", 100, 0.5, true);

            assertEquals(1, trace.getToolCalls().size());
            assertTrue(trace.getToolCalls().get(0).isSuccess());
            assertTrue(trace.getToolCalls().get(0).isFromCache());
            assertEquals(0, trace.getCacheMisses());
        }

        @Test
        @DisplayName("addToolCall increments cache misses when fromCache=false")
        void addToolCallCacheMiss() {
            trace.addToolCall("tool1", "{}", "result", 100, 0.5, false);

            assertEquals(1, trace.getCacheMisses());
        }

        @Test
        @DisplayName("addFailedToolCall sets hasErrors to true")
        void addFailedToolCallSetsErrors() {
            trace.addFailedToolCall("tool1", "{}", "error msg", 50, 0.1);

            assertTrue(trace.isHasErrors());
            assertEquals(1, trace.getToolCalls().size());
            assertFalse(trace.getToolCalls().get(0).isSuccess());
            assertEquals(1, trace.getCacheMisses());
        }

        @Test
        @DisplayName("addToolCall updates metrics with min/max")
        void addToolCallUpdatesMetrics() {
            trace.addToolCall("tool1", "{}", "r1", 100, 0.1, false);
            trace.addToolCall("tool1", "{}", "r2", 200, 0.2, true);

            var metrics = trace.getToolMetrics().get("tool1");
            assertNotNull(metrics);
            assertEquals(2, metrics.getTotalCalls());
            assertEquals(2, metrics.getSuccessfulCalls());
            assertEquals(0, metrics.getFailedCalls());
            assertEquals(100, metrics.getMinExecutionTimeMs());
            assertEquals(200, metrics.getMaxExecutionTimeMs());
            assertEquals(300, metrics.getTotalExecutionTimeMs());
            assertEquals(1, metrics.getCacheHits());
        }

        @Test
        @DisplayName("addFailedToolCall increments failedCalls in metrics")
        void addFailedToolCallMetrics() {
            trace.addFailedToolCall("tool1", "{}", "err", 50, 0.0);

            var metrics = trace.getToolMetrics().get("tool1");
            assertNotNull(metrics);
            assertEquals(1, metrics.getFailedCalls());
            assertEquals(0, metrics.getSuccessfulCalls());
        }

        @Test
        @DisplayName("getSummary with no calls")
        void getSummaryNoCalls() {
            String summary = trace.getSummary();
            assertTrue(summary.contains("Total Calls: 0"));
            assertTrue(summary.contains("Errors: No"));
            assertTrue(summary.contains("0%"));
        }

        @Test
        @DisplayName("getSummary with calls includes per-tool metrics")
        void getSummaryWithCalls() {
            trace.addToolCall("tool1", "{}", "ok", 100, 0.5, false);
            trace.addFailedToolCall("tool2", "{}", "err", 50, 0.0);

            String summary = trace.getSummary();
            assertTrue(summary.contains("Total Calls: 2"));
            assertTrue(summary.contains("Errors: Yes"));
            assertTrue(summary.contains("Per-Tool Metrics:"));
            assertTrue(summary.contains("tool1"));
            assertTrue(summary.contains("tool2"));
        }

        @Test
        @DisplayName("equals returns true for same instance")
        void equalsSame() {
            assertEquals(trace, trace);
        }

        @Test
        @DisplayName("equals returns false for null")
        void equalsNull() {
            assertNotEquals(null, trace);
        }

        @Test
        @DisplayName("equals returns false for different type")
        void equalsDiffType() {
            assertNotEquals("string", trace);
        }

        @Test
        @DisplayName("equals and hashCode for identical traces")
        void equalsIdentical() {
            var t1 = new ToolExecutionTrace();
            var t2 = new ToolExecutionTrace();
            assertEquals(t1, t2);
            assertEquals(t1.hashCode(), t2.hashCode());
        }

        @Test
        @DisplayName("toString contains field info")
        void toStringTest() {
            var str = trace.toString();
            assertTrue(str.contains("ToolExecutionTrace"));
            assertTrue(str.contains("toolCalls="));
        }

        @Test
        @DisplayName("setters work correctly")
        void setters() {
            trace.setTotalExecutionTimeMs(500);
            trace.setHasErrors(true);
            trace.setTotalCost(3.0);
            trace.setCacheHits(5);
            trace.setCacheMisses(2);
            trace.setToolMetrics(new HashMap<>());
            trace.setToolCalls(List.of());

            assertTrue(trace.isHasErrors());
            assertEquals(5, trace.getCacheMisses() + 3); // cacheMisses=2
            assertEquals(0, trace.getToolCalls().size());
        }
    }
}
