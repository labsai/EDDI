package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConversationRecallToolTest {

    private List<ConversationOutput> createOutputs(int count) {
        var outputs = new ArrayList<ConversationOutput>();
        for (int i = 0; i < count; i++) {
            var output = new ConversationOutput();
            output.put("input", "User message " + (i + 1));
            output.put("output", List.of(Map.of("text", "Agent reply " + (i + 1))));
            outputs.add(output);
        }
        return outputs;
    }

    @Test
    void recallConversationDetail_noSummaryActive_returnsMessage() {
        var tool = new ConversationRecallTool(List.of(), 0, 20);
        String result = tool.recallConversationDetail("anything");
        assertTrue(result.contains("No conversation summary is active"));
    }

    @Test
    void recallConversationDetail_withTurnRange_returnsTurns() {
        var outputs = createOutputs(10);
        var tool = new ConversationRecallTool(outputs, 8, 20);

        String result = tool.recallConversationDetail("turns 3-5");

        assertTrue(result.contains("Turn 3 — User:"), "Should include turn 3");
        assertTrue(result.contains("Turn 4 — User:"), "Should include turn 4");
        assertTrue(result.contains("Turn 5 — User:"), "Should include turn 5");
        assertFalse(result.contains("Turn 2 — User:"), "Should NOT include turn 2");
        assertFalse(result.contains("Turn 6 — User:"), "Should NOT include turn 6");
    }

    @Test
    void recallConversationDetail_rangeWithDash_parsed() {
        var outputs = createOutputs(10);
        var tool = new ConversationRecallTool(outputs, 8, 20);

        String result = tool.recallConversationDetail("3-5");

        assertTrue(result.contains("Turn 3"));
        assertTrue(result.contains("Turn 5"));
    }

    @Test
    void recallConversationDetail_rangeWithEnDash_parsed() {
        var outputs = createOutputs(10);
        var tool = new ConversationRecallTool(outputs, 8, 20);

        String result = tool.recallConversationDetail("3\u20135"); // en-dash

        assertTrue(result.contains("Turn 3"));
        assertTrue(result.contains("Turn 5"));
    }

    @Test
    void recallConversationDetail_rangeWithTo_parsed() {
        var outputs = createOutputs(10);
        var tool = new ConversationRecallTool(outputs, 8, 20);

        String result = tool.recallConversationDetail("turns 3 to 5");

        assertTrue(result.contains("Turn 3"));
        assertTrue(result.contains("Turn 5"));
    }

    @Test
    void recallConversationDetail_clampedToSummarizedSection() {
        // summaryThroughStep=5, requesting turns 3-9 → should clamp to 3-5
        var outputs = createOutputs(10);
        var tool = new ConversationRecallTool(outputs, 5, 20);

        String result = tool.recallConversationDetail("turns 3-9");

        assertTrue(result.contains("Turn 3"));
        assertTrue(result.contains("Turn 5"));
        assertFalse(result.contains("Turn 6"), "Should not include turns beyond summary boundary");
    }

    @Test
    void recallConversationDetail_enforcesMaxRecallLimit() {
        var outputs = createOutputs(30);
        // maxRecallTurns=5, summaryThroughStep=25
        var tool = new ConversationRecallTool(outputs, 25, 5);

        String result = tool.recallConversationDetail("turns 1-20");

        // Should only show 5 turns (1-5) due to maxRecallTurns limit
        assertTrue(result.contains("Turn 1"));
        assertTrue(result.contains("Turn 5"));
        assertFalse(result.contains("Turn 6"));
    }

    @Test
    void recallConversationDetail_noRangeSpecified_returnsLastSummarizedTurns() {
        var outputs = createOutputs(15);
        // summaryThroughStep=10, maxRecallTurns=20
        var tool = new ConversationRecallTool(outputs, 10, 20);

        String result = tool.recallConversationDetail("What did we discuss about pricing?");

        // Should return last N turns from summarized section (0 to 10)
        assertTrue(result.contains("Turn 1"), "Should include earliest turns when within maxRecall");
        assertTrue(result.contains("Turn 10"), "Should include last summarized turn");
    }

    @Test
    void recallConversationDetail_noRangeWithLimitedMaxRecall() {
        var outputs = createOutputs(15);
        // summaryThroughStep=10, maxRecallTurns=3
        var tool = new ConversationRecallTool(outputs, 10, 3);

        String result = tool.recallConversationDetail("What happened earlier?");

        // fromTurn = max(0, 10-3) = 7, so turns 8-10
        assertTrue(result.contains("Turn 8"));
        assertTrue(result.contains("Turn 10"));
        assertFalse(result.contains("Turn 7"));
    }

    @Test
    void recallConversationDetail_showsRemainingCount() {
        var outputs = createOutputs(15);
        // summaryThroughStep=12, requesting turns 1-3 → should show "more available"
        var tool = new ConversationRecallTool(outputs, 12, 20);

        String result = tool.recallConversationDetail("turns 1-3");

        assertTrue(result.contains("more summarized turns available"));
    }

    @Test
    void recallConversationDetail_containsUserAndAgentText() {
        var outputs = createOutputs(5);
        var tool = new ConversationRecallTool(outputs, 3, 20);

        String result = tool.recallConversationDetail("turns 1-3");

        assertTrue(result.contains("User:"));
        assertTrue(result.contains("Agent:"));
        assertTrue(result.contains("User message 1"));
        assertTrue(result.contains("Agent reply 1"));
    }

    @Test
    void recallConversationDetail_singleTurnPattern_returnsSingleTurn() {
        var outputs = createOutputs(10);
        var tool = new ConversationRecallTool(outputs, 8, 20);

        String result = tool.recallConversationDetail("turn 5");

        assertTrue(result.contains("Turn 5 — User:"), "Should include turn 5");
        assertTrue(result.contains("User message 5"));
        assertFalse(result.contains("Turn 4 — User:"), "Should NOT include turn 4");
        assertFalse(result.contains("Turn 6 — User:"), "Should NOT include turn 6");
    }

    @Test
    void recallConversationDetail_singleNumber_returnsSingleTurn() {
        var outputs = createOutputs(10);
        var tool = new ConversationRecallTool(outputs, 8, 20);

        // Edge case: LLM just sends "3" — should recall turn 3
        String result = tool.recallConversationDetail("3");

        assertTrue(result.contains("Turn 3 — User:"), "Should include turn 3");
        assertFalse(result.contains("Turn 2 — User:"), "Should NOT include turn 2");
        assertFalse(result.contains("Turn 4 — User:"), "Should NOT include turn 4");
    }

    @Test
    void recallConversationDetail_singleTurnBeyondSummary_clampedToSummaryBoundary() {
        var outputs = createOutputs(10);
        // summary covers turns 1-5
        var tool = new ConversationRecallTool(outputs, 5, 20);

        // Request turn 8 — beyond summarized section → should clamp to last summarized
        // turn (5)
        String result = tool.recallConversationDetail("turn 8");

        assertTrue(result.contains("Turn 5"), "Should include the clamped turn");
        assertFalse(result.contains("Turn 8"), "Should NOT include turn beyond summary boundary");
    }
}
