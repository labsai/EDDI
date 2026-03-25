package ai.labs.eddi.modules.llm.tools;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for ToolCostTracker - including unbounded memory fix.
 */
class ToolCostTrackerTest {

    private ToolCostTracker costTracker;

    @BeforeEach
    void setUp() throws Exception {
        costTracker = new ToolCostTracker();
        Field meterField = ToolCostTracker.class.getDeclaredField("meterRegistry");
        meterField.setAccessible(true);
        meterField.set(costTracker, new SimpleMeterRegistry());
        costTracker.init();
    }

    // === Basic Cost Tracking ===

    @Test
    void testTrackToolCall_ReturnsCost() {
        double cost = costTracker.trackToolCall("websearch", "conv1");
        assertEquals(0.001, cost, 0.0001);
    }

    @Test
    void testTrackToolCall_FreeTool() {
        double cost = costTracker.trackToolCall("calculator", "conv1");
        assertEquals(0.0, cost, 0.0001);
    }

    @Test
    void testTrackToolCall_UnknownTool() {
        double cost = costTracker.trackToolCall("unknownTool", "conv1");
        assertEquals(0.0, cost, 0.0001);
    }

    @Test
    void testTrackToolCall_AccumulatesTotalCost() {
        costTracker.trackToolCall("websearch", "conv1");
        costTracker.trackToolCall("websearch", "conv1");
        costTracker.trackToolCall("webscraper", "conv1");

        assertEquals(0.004, costTracker.getTotalCost(), 0.0001);
    }

    // === Per-Tool Metrics ===

    @Test
    void testGetToolCosts_TracksCallCount() {
        costTracker.trackToolCall("calculator", "conv1");
        costTracker.trackToolCall("calculator", "conv2");
        costTracker.trackToolCall("calculator", "conv3");

        var metrics = costTracker.getToolCosts("calculator");
        assertNotNull(metrics);
        assertEquals(3, metrics.getCallCount());
        assertEquals(0.0, metrics.getTotalCost(), 0.0001);
        assertEquals(0.0, metrics.getAverageCost(), 0.0001);
    }

    @Test
    void testGetToolCosts_TracksAmount() {
        costTracker.trackToolCall("websearch", "conv1");
        costTracker.trackToolCall("websearch", "conv1");

        var metrics = costTracker.getToolCosts("websearch");
        assertNotNull(metrics);
        assertEquals(2, metrics.getCallCount());
        assertEquals(0.002, metrics.getTotalCost(), 0.0001);
        assertEquals(0.001, metrics.getAverageCost(), 0.0001);
    }

    @Test
    void testGetToolCosts_NullForUntracked() {
        assertNull(costTracker.getToolCosts("neverCalled"));
    }

    // === Per-Conversation Metrics ===

    @Test
    void testGetConversationCosts_TracksPerConversation() {
        costTracker.trackToolCall("websearch", "conv1");
        costTracker.trackToolCall("calculator", "conv1");
        costTracker.trackToolCall("websearch", "conv2");

        var conv1 = costTracker.getConversationCosts("conv1");
        assertNotNull(conv1);
        assertEquals(2, conv1.getToolCallCount());
        assertEquals(0.001, conv1.getTotalCost(), 0.0001);

        var conv2 = costTracker.getConversationCosts("conv2");
        assertNotNull(conv2);
        assertEquals(1, conv2.getToolCallCount());
    }

    @Test
    void testGetConversationCosts_TracksToolUsage() {
        costTracker.trackToolCall("websearch", "conv1");
        costTracker.trackToolCall("websearch", "conv1");
        costTracker.trackToolCall("calculator", "conv1");

        var conv1 = costTracker.getConversationCosts("conv1");
        assertEquals(2, conv1.getToolUsage().get("websearch"));
        assertEquals(1, conv1.getToolUsage().get("calculator"));
    }

    // === Budget Check ===

    @Test
    void testIsWithinBudget_TrueWhenUnder() {
        costTracker.trackToolCall("websearch", "conv1"); // $0.001
        assertTrue(costTracker.isWithinBudget("conv1", 1.0));
    }

    @Test
    void testIsWithinBudget_FalseWhenOver() {
        // Track enough to exceed a small budget
        for (int i = 0; i < 100; i++) {
            costTracker.trackToolCall("webscraper", "conv1"); // $0.002 each = $0.2 total
        }
        assertFalse(costTracker.isWithinBudget("conv1", 0.1));
    }

    @Test
    void testIsWithinBudget_TrueForUnknownConversation() {
        assertTrue(costTracker.isWithinBudget("unknown", 1.0));
    }

    // === Reset ===

    @Test
    void testResetConversation() {
        costTracker.trackToolCall("websearch", "conv1");
        assertNotNull(costTracker.getConversationCosts("conv1"));

        costTracker.resetConversation("conv1");
        assertNull(costTracker.getConversationCosts("conv1"));
    }

    @Test
    void testResetAll() {
        costTracker.trackToolCall("websearch", "conv1");
        costTracker.trackToolCall("calculator", "conv2");

        costTracker.resetAll();

        assertNull(costTracker.getToolCosts("websearch"));
        assertNull(costTracker.getToolCosts("calculator"));
        assertNull(costTracker.getConversationCosts("conv1"));
        assertEquals(0.0, costTracker.getTotalCost(), 0.0001);
    }

    // === Cost Summary ===

    @Test
    void testGetCostSummary() {
        costTracker.trackToolCall("websearch", "conv1");
        costTracker.trackToolCall("calculator", "conv1");

        String summary = costTracker.getCostSummary();
        assertNotNull(summary);
        assertTrue(summary.contains("Tool Cost Summary"));
        assertTrue(summary.contains("Total Cost"));
    }

    // === Eviction (M3 fix) ===

    @Test
    void testEvictIfNeeded_DoesNotEvictUnderLimit() {
        for (int i = 0; i < 100; i++) {
            costTracker.trackToolCall("calculator", "conv-" + i);
        }
        // All 100 conversations should still exist
        for (int i = 0; i < 100; i++) {
            assertNotNull(costTracker.getConversationCosts("conv-" + i), "Conversation conv-" + i + " should still exist");
        }
    }

    @Test
    void testEvictIfNeeded_EvictsWhenOverLimit() {
        // Create more entries than MAX_CONVERSATION_ENTRIES
        int limit = ToolCostTracker.MAX_CONVERSATION_ENTRIES;
        for (int i = 0; i <= limit; i++) {
            costTracker.trackToolCall("calculator", "conv-" + i);
        }

        // After eviction, we should have roughly 90% of max entries
        // Count remaining
        int remaining = 0;
        for (int i = 0; i <= limit; i++) {
            if (costTracker.getConversationCosts("conv-" + i) != null) {
                remaining++;
            }
        }
        assertTrue(remaining <= limit, "Should have evicted some entries: remaining=" + remaining);
        assertTrue(remaining >= (int) (limit * 0.9) - 1, "Should keep about 90% of entries: remaining=" + remaining);
    }

    @Test
    void testEvictIfNeeded_DirectCall_UnderLimit() {
        costTracker.trackToolCall("calculator", "conv1");
        // Should not throw or evict
        costTracker.evictIfNeeded();
        assertNotNull(costTracker.getConversationCosts("conv1"));
    }
}
