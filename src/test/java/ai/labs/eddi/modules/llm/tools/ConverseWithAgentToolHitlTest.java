/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Finding 25: {@link ConverseWithAgentTool} must return a structured
 * PAUSED_FOR_APPROVAL result — not "[no response]" or a hang — when a delegated
 * conversation pauses for human approval.
 */
class ConverseWithAgentToolHitlTest {

    private IConversationService conversationService;
    private ConverseWithAgentTool tool;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        tool = new ConverseWithAgentTool(conversationService, "user-1");
    }

    @Test
    void turnPausesForApproval_returnsStructuredResult() throws Exception {
        // say completes normally but the snapshot state is AWAITING_HUMAN
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);

        Answer<Void> deliver = invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(snapshot);
            return null;
        };
        doAnswer(deliver).when(conversationService).say(any(), eq("agent-b"), eq("conv-x"),
                any(), any(), any(), any(), anyBoolean(), any());

        String result = tool.converseWithAgent("agent-b", "please delete prod", "conv-x");

        assertTrue(result.startsWith("PAUSED_FOR_APPROVAL"), result);
        assertTrue(result.contains("conv-x"));
        assertTrue(result.contains("/resume"));
        assertFalse(result.contains("[no response]"));
    }

    @Test
    void toolCallPause_envelopeCarriesPauseTypeAndToolNames() throws Exception {
        // Task 13: a nested TOOL_CALL pause must additively surface pauseType and
        // the gated tool NAMES (names only — never arguments) in the delegated-tool
        // text message.
        var batch = new ai.labs.eddi.engine.memory.model.PendingToolCallBatch();
        var call1 = new ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall();
        call1.setToolName("delete_production_db");
        call1.setArgumentsRaw("{\"secret\":\"nuke\"}");
        var call2 = new ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall();
        call2.setToolName("wire_transfer");
        batch.setCalls(java.util.List.of(call1, call2));

        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPauseType("TOOL_CALL");
        snapshot.setHitlPendingToolCalls(batch);

        Answer<Void> deliver = invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(snapshot);
            return null;
        };
        doAnswer(deliver).when(conversationService).say(any(), eq("agent-b"), eq("conv-x"),
                any(), any(), any(), any(), anyBoolean(), any());

        String result = tool.converseWithAgent("agent-b", "please delete prod", "conv-x");

        assertTrue(result.startsWith("PAUSED_FOR_APPROVAL"), result);
        assertTrue(result.contains("TOOL_CALL"), result);
        assertTrue(result.contains("delete_production_db"), result);
        assertTrue(result.contains("wire_transfer"), result);
        // Privacy: NEVER leak arguments (raw or redacted) — names only.
        assertFalse(result.contains("nuke"), result);
        assertFalse(result.contains("secret"), result);
    }

    @Test
    void rulePause_envelopeUnchanged_noToolCallFields() throws Exception {
        // Task 13 regression: a nested RULE pause must NOT gain pauseType/tools text —
        // the previously-present message shape stays intact.
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPauseType("RULE");
        // no pending tool calls for a RULE pause

        Answer<Void> deliver = invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(snapshot);
            return null;
        };
        doAnswer(deliver).when(conversationService).say(any(), eq("agent-b"), eq("conv-x"),
                any(), any(), any(), any(), anyBoolean(), any());

        String result = tool.converseWithAgent("agent-b", "please delete prod", "conv-x");

        assertTrue(result.startsWith("PAUSED_FOR_APPROVAL"), result);
        assertTrue(result.contains("conv-x"));
        assertTrue(result.contains("/resume"));
        assertFalse(result.contains("TOOL_CALL"), result);
    }

    @Test
    void alreadyPaused_sayThrows_returnsStructuredResult() throws Exception {
        doThrow(new IConversationService.ConversationAwaitingApprovalException("paused"))
                .when(conversationService).say(any(), eq("agent-b"), eq("conv-x"),
                        any(), any(), any(), any(), anyBoolean(), any());

        String result = tool.converseWithAgent("agent-b", "any follow-up", "conv-x");

        assertTrue(result.startsWith("PAUSED_FOR_APPROVAL"), result);
        assertTrue(result.contains("conv-x"));
        // Must NOT surface a generic error
        assertFalse(result.startsWith("❌"));
    }

    @Test
    void busySkip_inProgress_returnsRetry_notStaleReply() throws Exception {
        // H7: onSkipped with IN_PROGRESS — the input was dropped (busy). Must return
        // a "busy — retry" result, NOT the previous turn's output.
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.IN_PROGRESS);

        Answer<Void> skip = invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onSkipped(snapshot);
            return null;
        };
        doAnswer(skip).when(conversationService).say(any(), eq("agent-b"), eq("conv-x"),
                any(), any(), any(), any(), anyBoolean(), any());

        String result = tool.converseWithAgent("agent-b", "another message", "conv-x");

        assertTrue(result.contains("busy"), result);
        assertTrue(result.contains("conv-x"));
        assertFalse(result.startsWith("✅"), "must not report a fresh agent response");
    }

    @Test
    void skip_endedConversation_returnsNotActive() throws Exception {
        // H7: onSkipped with ENDED — the conversation is no longer active.
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.ENDED);

        Answer<Void> skip = invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onSkipped(snapshot);
            return null;
        };
        doAnswer(skip).when(conversationService).say(any(), eq("agent-b"), eq("conv-x"),
                any(), any(), any(), any(), anyBoolean(), any());

        String result = tool.converseWithAgent("agent-b", "another message", "conv-x");

        assertTrue(result.contains("no longer active"), result);
        assertFalse(result.startsWith("✅"));
    }
}
