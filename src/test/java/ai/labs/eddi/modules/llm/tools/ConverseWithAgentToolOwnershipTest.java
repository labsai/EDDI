/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code converse_with_agent} tool must not let the LLM drive a
 * conversation owned by a user other than the one it is bound to (the parent
 * conversation's owner). A model-supplied {@code conversationId} is request
 * payload, not proof of ownership — the agent-id {@code say(...)} overload the
 * tool drives checks only that the agentId matches the conversation, not who
 * owns it.
 */
@DisplayName("ConverseWithAgentTool — cross-user conversation access (IDOR)")
class ConverseWithAgentToolOwnershipTest {

    private IConversationService conversationService;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        lenient().when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenReturn(new ConversationResult("conv-new", null));
        lenient().doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    private static ConversationMemorySnapshot snapshotOwnedBy(String userId) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setUserId(userId);
        return snapshot;
    }

    @Test
    @DisplayName("refuses to continue a conversation owned by another user, without driving it")
    void suppliedConversationId_ownedByAnotherUser_refused() throws Exception {
        when(conversationService.getConversationMemorySnapshot("conv-victim")).thenReturn(snapshotOwnedBy("user-B"));
        var tool = new ConverseWithAgentTool(conversationService, "user-A");

        String result = tool.converseWithAgent("agent-2", "hello", "conv-victim");

        assertTrue(result.contains("does not belong to you"), "expected an ownership refusal, got: " + result);
        verify(conversationService, never()).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("refuses to continue a conversation that records no owner, without driving it")
    void suppliedConversationId_ownerless_refused() throws Exception {
        when(conversationService.getConversationMemorySnapshot("conv-legacy")).thenReturn(snapshotOwnedBy(null));
        var tool = new ConverseWithAgentTool(conversationService, "user-A");

        String result = tool.converseWithAgent("agent-2", "hello", "conv-legacy");

        assertTrue(result.contains("ownership could not be verified"), "expected a fail-closed refusal, got: " + result);
        verify(conversationService, never()).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("with no bound user, refuses a supplied conversation id — even one owned by someone — without driving it")
    void suppliedConversationId_noBoundUser_refused() throws Exception {
        lenient().when(conversationService.getConversationMemorySnapshot("conv-victim")).thenReturn(snapshotOwnedBy("user-B"));

        for (String unbound : new String[]{null, "", "  "}) {
            var tool = new ConverseWithAgentTool(conversationService, unbound);

            String result = tool.converseWithAgent("agent-2", "hello", "conv-victim");

            assertTrue(result.contains("ownership could not be verified"), "expected a fail-closed refusal, got: " + result);
        }
        verify(conversationService, never()).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("with no bound user, a new conversation (no id) is still started")
    void newConversation_noBoundUser_allowed() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, null);

        String result = tool.converseWithAgent("agent-2", "hello", null);

        assertFalse(result.contains("ownership could not be verified"), "a new conversation must not be refused: " + result);
        verify(conversationService, times(1))
                .say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("continues a conversation the bound user owns")
    void suppliedConversationId_ownedBySameUser_allowed() throws Exception {
        when(conversationService.getConversationMemorySnapshot("conv-own")).thenReturn(snapshotOwnedBy("user-A"));
        var tool = new ConverseWithAgentTool(conversationService, "user-A");

        String result = tool.converseWithAgent("agent-2", "hello", "conv-own");

        assertFalse(result.contains("does not belong to you"), "own conversation must not be refused: " + result);
        verify(conversationService, times(1))
                .say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("a new conversation (no id) needs no ownership check")
    void newConversation_noOwnershipCheck() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-A");

        tool.converseWithAgent("agent-2", "hello", null);

        verify(conversationService, never()).getConversationMemorySnapshot(anyString());
        verify(conversationService, times(1))
                .say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    // M-A1: the target agent must be one the bound user may use — for a new
    // conversation and for a continuation of one the user owns alike.

    @Test
    @DisplayName("a new conversation with an agent the user may not use is refused before it starts")
    void newConversationRequiresUseAccess() throws Exception {
        List<String> asked = new ArrayList<>();
        var tool = new ConverseWithAgentTool(conversationService, "user-A", null, 0, (agentId, principal) -> {
            asked.add(agentId + "@" + principal);
            return false;
        });

        String result = tool.converseWithAgent("other-teams-private-agent", "what do you know?", null);

        assertTrue(result.contains("not available to this user"), result);
        assertEquals(List.of("other-teams-private-agent@user-A"), asked);
        verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("an agent the user may use is started as before")
    void newConversationWithUsableAgentStarts() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-A", null, 0, (agentId, principal) -> true);

        tool.converseWithAgent("agent-2", "hi", null);

        verify(conversationService).startConversation(any(), eq("agent-2"), eq("user-A"), any());
    }

    @Test
    @DisplayName("continuing the user's own conversation with an agent they may no longer use is refused, without driving it")
    void continuationRequiresUseAccessToo() throws Exception {
        lenient().when(conversationService.getConversationMemorySnapshot("conv-own")).thenReturn(snapshotOwnedBy("user-A"));
        var tool = new ConverseWithAgentTool(conversationService, "user-A", null, 0, (agentId, principal) -> false);

        String result = tool.converseWithAgent("agent-2", "follow-up", "conv-own");

        assertTrue(result.contains("not available to this user"), result);
        verify(conversationService, never()).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }
}
