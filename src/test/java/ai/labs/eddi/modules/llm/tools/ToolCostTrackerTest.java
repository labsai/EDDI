/*
 * Copyright EDDI contributors
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

    /**
     * The invocation the production dispatch loop actually builds for a
     * {@code WebSearchTool#searchWeb} call: dispatched as {@code searchWeb},
     * configured (and priced) as {@code websearch}.
     */
    private static final ToolInvocation SEARCH_WEB = new ToolInvocation("searchWeb", "websearch", null);

    /** Same, for {@code WebScraperTool#extractWebPageText}. */
    private static final ToolInvocation SCRAPE = new ToolInvocation("extractWebPageText", "webscraper", null);

    @Nested
    @DisplayName("trackToolCall")
    class TrackToolCall {

        /**
         * The shipped defect in one assertion. Production has only ever passed the
         * {@code @Tool} method name, and no built-in declares
         * {@code @Tool(name = "websearch")} — so the price table, which is keyed on
         * whitelist slugs, never matched a single live call. Asserting with the string
         * {@code "websearch"} alone (as this test used to) proves the table's contents
         * and nothing about the lookup that consumes it.
         */
        @Test
        @DisplayName("prices a real dispatch name via its canonical slug")
        void dispatchNamePricedThroughSlug() {
            double cost = tracker.trackToolCall(SEARCH_WEB, "conv-1");
            assertEquals(0.001, cost, 0.0001,
                    "searchWeb must be priced as websearch; $0.00 here means maxBudgetPerConversation can never bind");
        }

        @Test
        @DisplayName("a dispatch name with no slug mapping prices at zero")
        void unmappedDispatchNameIsFree() {
            assertEquals(0.0, tracker.trackToolCall(ToolInvocation.of("searchWeb"), "conv-1"), 0.0001,
                    "without the canonical mapping there is nothing to price against — this is the pre-fix behaviour");
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
            double cost = tracker.trackToolCall(new ToolInvocation("calculate", "calculator", null), "conv-1");
            assertEquals(0.0, cost, 0.0001);
        }

        @Test
        @DisplayName("should accumulate total cost")
        void accumulateTotalCost() {
            tracker.trackToolCall(SEARCH_WEB, "conv-1");
            tracker.trackToolCall(SEARCH_WEB, "conv-1");
            tracker.trackToolCall(SCRAPE, "conv-1");
            // 0.001 + 0.001 + 0.002 = 0.004
            assertEquals(0.004, tracker.getTotalCost(), 0.0001);
        }

        @Test
        @DisplayName("the legacy String overload prices the name as its own slug")
        void legacyOverloadDelegates() {
            assertEquals(0.001, tracker.trackToolCall("websearch", "conv-legacy"), 0.0001);
            assertEquals(0.001, tracker.getConversationCosts("conv-legacy").getTotalCost(), 0.0001);
        }
    }

    @Nested
    @DisplayName("operator price overrides")
    class PriceOverrides {

        @Test
        @DisplayName("an override wins over the default price")
        void overrideWins() {
            var priced = new ToolInvocation("searchWeb", "websearch", 0.05);
            assertEquals(0.05, tracker.trackToolCall(priced, "conv-1"), 0.0001);
        }

        @Test
        @DisplayName("an override prices a tool the default table does not know")
        void overridePricesUnknownTool() {
            var priced = new ToolInvocation("myHttpTool", "myHttpTool", 0.25);
            assertEquals(0.25, tracker.trackToolCall(priced, "conv-1"), 0.0001);
        }

        /**
         * {@code toolPricing} values come straight from an agent JSON config. A
         * negative price would <em>credit</em> the conversation, so a config could
         * drive the running total downwards and make a maxBudgetPerConversation ceiling
         * unreachable no matter how many paid calls it makes.
         */
        @Test
        @DisplayName("a negative override is clamped to zero, never credited")
        void negativeOverrideIsClamped() {
            var negative = new ToolInvocation("searchWeb", "websearch", -10.0);

            assertEquals(0.0, tracker.trackToolCall(negative, "conv-neg"), 0.0001);
            assertEquals(0.0, tracker.getConversationCosts("conv-neg").getTotalCost(), 0.0001);
            assertEquals(0.0, tracker.getTotalCost(), 0.0001);
        }

        @Test
        @DisplayName("a zero override suppresses the default price")
        void zeroOverrideSuppressesDefault() {
            var free = new ToolInvocation("searchWeb", "websearch", 0.0);
            assertEquals(0.0, tracker.trackToolCall(free, "conv-1"), 0.0001);
        }
    }

    /**
     * Accounting keys stay on the dispatch name even though the price comes from
     * the slug. Every other {@code tool}-tagged meter in this package reports the
     * dispatched method name, so flipping these two to slugs would split the tag
     * vocabulary in half and break existing dashboards for no analytical gain.
     */
    @Nested
    @DisplayName("metric tags and accounting keys")
    class AccountingKeys {

        @Test
        @DisplayName("tags eddi.tool.calls and eddi.tool.costs with the dispatch name")
        void metricsTaggedWithDispatchName() {
            tracker.trackToolCall(SEARCH_WEB, "conv-1");

            var registry = tracker.meterRegistry;
            assertNotNull(registry.find("eddi.tool.calls").tag("tool", "searchWeb").counter());
            assertNull(registry.find("eddi.tool.calls").tag("tool", "websearch").counter());

            var costs = registry.find("eddi.tool.costs").tag("tool", "searchWeb").counter();
            assertNotNull(costs, "a priced built-in must now produce a non-zero eddi.tool.costs series");
            assertEquals(0.001, costs.count(), 0.0001);
        }

        @Test
        @DisplayName("breaks per-tool and per-conversation usage down by dispatch name")
        void usageKeyedByDispatchName() {
            tracker.trackToolCall(SEARCH_WEB, "conv-keys");

            assertNotNull(tracker.getToolCosts("searchWeb"));
            assertNull(tracker.getToolCosts("websearch"));
            assertEquals(1, tracker.getConversationCosts("conv-keys").getToolUsage().get("searchWeb"));
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
