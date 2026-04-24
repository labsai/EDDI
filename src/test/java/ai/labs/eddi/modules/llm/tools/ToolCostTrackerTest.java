/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link ToolCostTracker} covering cost tracking, budget checks,
 * eviction logic, and summary formatting.
 */
class ToolCostTrackerTest {

    private ToolCostTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new ToolCostTracker();
        tracker.meterRegistry = new SimpleMeterRegistry();
        tracker.init();
    }

    @Nested
    @DisplayName("trackToolCall")
    class TrackToolCall {

        @Test
        @DisplayName("should return known cost for websearch")
        void knownToolCost() {
            double cost = tracker.trackToolCall("websearch", "conv-1");
            assertEquals(0.001, cost, 0.0001);
        }

        @Test
        @DisplayName("should return 0 for unknown tool")
        void unknownToolCost() {
            double cost = tracker.trackToolCall("myCustomTool", "conv-1");
            assertEquals(0.0, cost, 0.0001);
        }

        @Test
        @DisplayName("should return 0 for free tools like calculator")
        void freeToolCost() {
            double cost = tracker.trackToolCall("calculator", "conv-1");
            assertEquals(0.0, cost, 0.0001);
        }

        @Test
        @DisplayName("should accumulate total cost")
        void accumulateTotalCost() {
            tracker.trackToolCall("websearch", "conv-1");
            tracker.trackToolCall("websearch", "conv-1");
            tracker.trackToolCall("webscraper", "conv-1");
            // 0.001 + 0.001 + 0.002 = 0.004
            assertEquals(0.004, tracker.getTotalCost(), 0.0001);
        }
    }

    @Nested
    @DisplayName("per-tool metrics")
    class PerToolMetrics {

        @Test
        @DisplayName("should track call count per tool")
        void callCount() {
            tracker.trackToolCall("websearch", "conv-1");
            tracker.trackToolCall("websearch", "conv-2");
            tracker.trackToolCall("websearch", "conv-3");

            var metrics = tracker.getToolCosts("websearch");
            assertNotNull(metrics);
            assertEquals(3, metrics.getCallCount());
            assertEquals(0.003, metrics.getTotalCost(), 0.0001);
            assertEquals(0.001, metrics.getAverageCost(), 0.0001);
        }

        @Test
        @DisplayName("should return null for untracked tool")
        void untrackedTool() {
            assertNull(tracker.getToolCosts("neverCalled"));
        }
    }

    @Nested
    @DisplayName("per-conversation metrics")
    class PerConversationMetrics {

        @Test
        @DisplayName("should track costs per conversation")
        void conversationCosts() {
            tracker.trackToolCall("websearch", "conv-A");
            tracker.trackToolCall("calculator", "conv-A");
            tracker.trackToolCall("websearch", "conv-B");

            var convA = tracker.getConversationCosts("conv-A");
            assertNotNull(convA);
            assertEquals(2, convA.getToolCallCount());
            assertEquals(0.001, convA.getTotalCost(), 0.0001); // websearch=$0.001 + calculator=$0

            var convB = tracker.getConversationCosts("conv-B");
            assertNotNull(convB);
            assertEquals(1, convB.getToolCallCount());
        }

        @Test
        @DisplayName("should track tool usage map per conversation")
        void toolUsageMap() {
            tracker.trackToolCall("websearch", "conv-usage");
            tracker.trackToolCall("websearch", "conv-usage");
            tracker.trackToolCall("calculator", "conv-usage");

            var usage = tracker.getConversationCosts("conv-usage").getToolUsage();
            assertEquals(2, usage.get("websearch"));
            assertEquals(1, usage.get("calculator"));
        }

        @Test
        @DisplayName("should return null for unknown conversation")
        void unknownConversation() {
            assertNull(tracker.getConversationCosts("never-existed"));
        }
    }

    @Nested
    @DisplayName("isWithinBudget")
    class IsWithinBudget {

        @Test
        @DisplayName("should return true for unknown conversation")
        void unknownConvWithinBudget() {
            assertTrue(tracker.isWithinBudget("new-conv", 1.0));
        }

        @Test
        @DisplayName("should return true when under budget")
        void underBudget() {
            tracker.trackToolCall("websearch", "budget-conv"); // $0.001
            assertTrue(tracker.isWithinBudget("budget-conv", 0.01));
        }

        @Test
        @DisplayName("should return false when over budget")
        void overBudget() {
            // Each websearch costs $0.001
            for (int i = 0; i < 10; i++) {
                tracker.trackToolCall("websearch", "expensive-conv");
            }
            // Total: $0.01 — budget is $0.005
            assertFalse(tracker.isWithinBudget("expensive-conv", 0.005));
        }
    }

    @Nested
    @DisplayName("eviction")
    class Eviction {

        @Test
        @DisplayName("should not evict when under limit")
        void noEvictionUnderLimit() {
            tracker.trackToolCall("tool", "conv-1");
            tracker.evictIfNeeded();
            assertNotNull(tracker.getConversationCosts("conv-1"));
        }
    }

    @Nested
    @DisplayName("reset")
    class ResetTests {

        @Test
        @DisplayName("resetConversation should remove conversation entry")
        void resetConversation() {
            tracker.trackToolCall("websearch", "conv-reset");
            assertNotNull(tracker.getConversationCosts("conv-reset"));

            tracker.resetConversation("conv-reset");
            assertNull(tracker.getConversationCosts("conv-reset"));
        }

        @Test
        @DisplayName("resetAll should clear everything")
        void resetAll() {
            tracker.trackToolCall("websearch", "conv-all");
            tracker.resetAll();

            assertEquals(0.0, tracker.getTotalCost(), 0.0001);
            assertNull(tracker.getToolCosts("websearch"));
            assertNull(tracker.getConversationCosts("conv-all"));
        }
    }

    @Nested
    @DisplayName("getCostSummary")
    class CostSummary {

        @Test
        @DisplayName("should produce formatted summary string")
        void formattedSummary() {
            tracker.trackToolCall("websearch", "conv-1");
            String summary = tracker.getCostSummary();
            assertTrue(summary.contains("Tool Cost Summary"));
            assertTrue(summary.contains("Total Cost"));
            assertTrue(summary.contains("websearch"));
        }
    }
}
