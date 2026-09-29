/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Turn N is conversation step N: turn 0 is the opening (CONVERSATION_START)
 * step, turn N the user's N-th message. Ranges are inclusive. The fixture
 * mirrors a real conversation — output 0 is the greeting with no user input.
 */
class ConversationRecallToolTest {

    /** Output 0 is the greeting; output n (n ≥ 1) holds "User message n". */
    private List<ConversationOutput> createOutputs(int count) {
        var outputs = new ArrayList<ConversationOutput>();
        var greeting = new ConversationOutput();
        greeting.put("input", "");
        greeting.put("output", List.of(Map.of("text", "Welcome!")));
        outputs.add(greeting);
        for (int i = 1; i < count; i++) {
            var output = new ConversationOutput();
            output.put("input", "User message " + i);
            output.put("output", List.of(Map.of("text", "Agent reply " + i)));
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

    /**
     * Live-reproduced: the user's first message was labelled "Turn 2", because
     * labels were step + 1 and step 0 is the greeting.
     */
    @Test
    void recallConversationDetail_theUsersFirstMessageIsTurnOne() {
        var tool = new ConversationRecallTool(createOutputs(10), 8, 20);

        String result = tool.recallConversationDetail("turn 1");

        assertTrue(result.contains("**Turn 1 — User:** User message 1"), result);
    }

    @Test
    void recallConversationDetail_greetingHasNoEmptyUserLine() {
        var tool = new ConversationRecallTool(createOutputs(5), 3, 20);

        String result = tool.recallConversationDetail("turn 0");

        assertTrue(result.contains("Turn 0 — Agent:** Welcome!"), result);
        assertFalse(result.contains("Turn 0 — User:"), "the opening step has no user input: " + result);
    }

    @Test
    void recallConversationDetail_withTurnRange_returnsTurns() {
        var tool = new ConversationRecallTool(createOutputs(10), 8, 20);

        String result = tool.recallConversationDetail("turns 3-5");

        assertTrue(result.contains("Turn 3 — User:** User message 3"), "Should include turn 3");
        assertTrue(result.contains("Turn 4 — User:"), "Should include turn 4");
        assertTrue(result.contains("Turn 5 — User:** User message 5"), "Should include turn 5 — ranges are inclusive");
        assertFalse(result.contains("Turn 2 — User:"), "Should NOT include turn 2");
        assertFalse(result.contains("Turn 6 — User:"), "Should NOT include turn 6");
        assertTrue(result.contains("turns 3-5"), "the header names the inclusive range: " + result);
    }

    @Test
    void recallConversationDetail_rangeWithDash_parsed() {
        var tool = new ConversationRecallTool(createOutputs(10), 8, 20);

        String result = tool.recallConversationDetail("3-5");

        assertTrue(result.contains("Turn 3"));
        assertTrue(result.contains("Turn 5"));
    }

    @Test
    void recallConversationDetail_rangeWithEnDash_parsed() {
        var tool = new ConversationRecallTool(createOutputs(10), 8, 20);

        String result = tool.recallConversationDetail("3–5"); // en-dash

        assertTrue(result.contains("Turn 3"));
        assertTrue(result.contains("Turn 5"));
    }

    @Test
    void recallConversationDetail_rangeWithTo_parsed() {
        var tool = new ConversationRecallTool(createOutputs(10), 8, 20);

        String result = tool.recallConversationDetail("turns 3 to 5");

        assertTrue(result.contains("Turn 3"));
        assertTrue(result.contains("Turn 5"));
    }

    @Test
    void recallConversationDetail_reversedRange_isNormalised() {
        var tool = new ConversationRecallTool(createOutputs(10), 8, 20);

        String result = tool.recallConversationDetail("turns 5-3");

        assertTrue(result.contains("Turn 3 — User:"));
        assertTrue(result.contains("Turn 5 — User:"));
    }

    @Test
    void recallConversationDetail_clampedToSummarizedSection() {
        // summaryThroughStep=5 → steps 0-4 are summarized; turns 3-9 clamps to 3-4
        var tool = new ConversationRecallTool(createOutputs(10), 5, 20);

        String result = tool.recallConversationDetail("turns 3-9");

        assertTrue(result.contains("Turn 3"));
        assertTrue(result.contains("Turn 4"));
        assertFalse(result.contains("Turn 5"), "Should not include turns beyond summary boundary");
    }

    @Test
    void recallConversationDetail_turnBeyondInt_isTheLastSummarizedTurn() {
        // Integer.parseInt threw NumberFormatException out of the tool for this
        var tool = new ConversationRecallTool(createOutputs(10), 5, 20);

        String result = tool.recallConversationDetail("turn 99999999999");

        assertTrue(result.contains("Turn 4"));
        assertFalse(result.contains("Turn 5"));
    }

    @Test
    void recallConversationDetail_rangeEndingAtIntMax_doesNotOverflow() {
        // last + 1 overflowed to a negative bound and recalled nothing
        var tool = new ConversationRecallTool(createOutputs(10), 5, 20);

        String result = tool.recallConversationDetail("turns 3-2147483647");

        assertTrue(result.contains("Turn 3"));
        assertTrue(result.contains("Turn 4"));
        assertFalse(result.contains("Turn 5"));
    }

    @Test
    void recallConversationDetail_enforcesMaxRecallLimit() {
        // maxRecallTurns=5, summaryThroughStep=25
        var tool = new ConversationRecallTool(createOutputs(30), 25, 5);

        String result = tool.recallConversationDetail("turns 1-20");

        // Only 5 turns (1-5) due to maxRecallTurns
        assertTrue(result.contains("Turn 1"));
        assertTrue(result.contains("Turn 5"));
        assertFalse(result.contains("Turn 6"));
    }

    @Test
    void recallConversationDetail_noRangeSpecified_returnsLastSummarizedTurns() {
        // summaryThroughStep=10 → steps 0-9, maxRecallTurns=20
        var tool = new ConversationRecallTool(createOutputs(15), 10, 20);

        String result = tool.recallConversationDetail("What did we discuss about pricing?");

        assertTrue(result.contains("Turn 1"), "Should include earliest turns when within maxRecall");
        assertTrue(result.contains("Turn 9"), "Should include last summarized turn");
        assertFalse(result.contains("Turn 10"));
    }

    @Test
    void recallConversationDetail_noRangeWithLimitedMaxRecall() {
        // summaryThroughStep=10, maxRecallTurns=3 → the last three summarized: 7-9
        var tool = new ConversationRecallTool(createOutputs(15), 10, 3);

        String result = tool.recallConversationDetail("What happened earlier?");

        assertTrue(result.contains("Turn 7"));
        assertTrue(result.contains("Turn 9"));
        assertFalse(result.contains("Turn 6"));
    }

    @Test
    void recallConversationDetail_showsRemainingCount() {
        // summaryThroughStep=12, requesting turns 1-3 → more available
        var tool = new ConversationRecallTool(createOutputs(15), 12, 20);

        String result = tool.recallConversationDetail("turns 1-3");

        assertTrue(result.contains("more summarized turns available"));
    }

    @Test
    void recallConversationDetail_containsUserAndAgentText() {
        var tool = new ConversationRecallTool(createOutputs(5), 3, 20);

        String result = tool.recallConversationDetail("turns 1-2");

        assertTrue(result.contains("User:"));
        assertTrue(result.contains("Agent:"));
        assertTrue(result.contains("User message 1"));
        assertTrue(result.contains("Agent reply 1"));
    }

    @Test
    void recallConversationDetail_singleTurnPattern_returnsSingleTurn() {
        var tool = new ConversationRecallTool(createOutputs(10), 8, 20);

        String result = tool.recallConversationDetail("turn 5");

        assertTrue(result.contains("Turn 5 — User:"), "Should include turn 5");
        assertTrue(result.contains("User message 5"));
        assertFalse(result.contains("Turn 4 — User:"), "Should NOT include turn 4");
        assertFalse(result.contains("Turn 6 — User:"), "Should NOT include turn 6");
    }

    @Test
    void recallConversationDetail_singleNumber_returnsSingleTurn() {
        var tool = new ConversationRecallTool(createOutputs(10), 8, 20);

        // Edge case: LLM just sends "3" — should recall turn 3
        String result = tool.recallConversationDetail("3");

        assertTrue(result.contains("Turn 3 — User:"), "Should include turn 3");
        assertFalse(result.contains("Turn 2 — User:"), "Should NOT include turn 2");
        assertFalse(result.contains("Turn 4 — User:"), "Should NOT include turn 4");
    }

    @Test
    void recallConversationDetail_singleTurnBeyondSummary_clampedToSummaryBoundary() {
        // summary covers steps 0-4
        var tool = new ConversationRecallTool(createOutputs(10), 5, 20);

        // Request turn 8 — beyond summarized section → clamps to the last summarized
        // turn (4)
        String result = tool.recallConversationDetail("turn 8");

        assertTrue(result.contains("Turn 4"), "Should include the clamped turn");
        assertFalse(result.contains("Turn 8"), "Should NOT include turn beyond summary boundary");
    }
}
