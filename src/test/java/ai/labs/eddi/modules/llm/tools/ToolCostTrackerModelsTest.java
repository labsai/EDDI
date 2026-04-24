/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolCostTrackerModelsTest {

    // --- ToolCostMetrics ---

    @Nested
    class ToolCostMetricsTests {

        @Test
        void initial_zeroCounts() {
            var metrics = new ToolCostTracker.ToolCostMetrics("weather");
            assertEquals(0, metrics.getCallCount());
            assertEquals(0.0, metrics.getTotalCost());
            assertEquals(0.0, metrics.getAverageCost());
        }

        @Test
        void addCost_accumulates() {
            var metrics = new ToolCostTracker.ToolCostMetrics("weather");
            metrics.addCost(0.01);
            metrics.addCost(0.02);

            assertEquals(2, metrics.getCallCount());
            assertEquals(0.03, metrics.getTotalCost(), 0.0001);
            assertEquals(0.015, metrics.getAverageCost(), 0.0001);
        }

        @Test
        void addCost_zeroCost_stillCounts() {
            var metrics = new ToolCostTracker.ToolCostMetrics("calculator");
            metrics.addCost(0.0);
            assertEquals(1, metrics.getCallCount());
            assertEquals(0.0, metrics.getTotalCost());
        }
    }

    // --- ConversationCostMetrics ---

    @Nested
    class ConversationCostMetricsTests {

        @Test
        void initial_zeroCounts() {
            var metrics = new ToolCostTracker.ConversationCostMetrics("conv-1");
            assertEquals(0, metrics.getToolCallCount());
            assertEquals(0.0, metrics.getTotalCost());
            assertTrue(metrics.getToolUsage().isEmpty());
        }

        @Test
        void addToolCost_accumulates() {
            var metrics = new ToolCostTracker.ConversationCostMetrics("conv-1");
            metrics.addToolCost("weather", 0.01);
            metrics.addToolCost("search", 0.02);
            metrics.addToolCost("weather", 0.01);

            assertEquals(3, metrics.getToolCallCount());
            assertEquals(0.04, metrics.getTotalCost(), 0.0001);
        }

        @Test
        void toolUsage_tracksPerTool() {
            var metrics = new ToolCostTracker.ConversationCostMetrics("conv-1");
            metrics.addToolCost("weather", 0.01);
            metrics.addToolCost("weather", 0.01);
            metrics.addToolCost("search", 0.02);

            var usage = metrics.getToolUsage();
            assertEquals(2, usage.get("weather"));
            assertEquals(1, usage.get("search"));
        }

        @Test
        void toolUsage_returnsImmutableCopy() {
            var metrics = new ToolCostTracker.ConversationCostMetrics("conv-1");
            metrics.addToolCost("weather", 0.01);

            var usage = metrics.getToolUsage();
            assertThrows(UnsupportedOperationException.class, () -> usage.put("test", 1));
        }
    }

    // --- RateLimitInfo ---

    @Nested
    class RateLimitInfoTests {

        @Test
        void construction() {
            var info = new ToolRateLimiter.RateLimitInfo(100, 95, System.currentTimeMillis() + 60000);
            assertEquals(100, info.limit);
            assertEquals(95, info.remaining);
        }

        @Test
        void toString_containsRateInfo() {
            var info = new ToolRateLimiter.RateLimitInfo(100, 50, System.currentTimeMillis() + 30000);
            String str = info.toString();
            assertTrue(str.contains("50"));
            assertTrue(str.contains("100"));
        }
    }
}
