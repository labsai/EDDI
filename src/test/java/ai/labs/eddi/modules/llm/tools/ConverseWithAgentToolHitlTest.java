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
}
