/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IRestAgentEngine;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.IRestAgentTriggerStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Finding 25: {@code chat_managed} must return a structured PAUSED_FOR_APPROVAL
 * result (not a 60s hang + generic error) when the managed conversation is
 * awaiting human approval, and must NOT recreate the mapping.
 */
class McpConversationToolsHitlTest {

    private IConversationService conversationService;
    private IRestAgentEngine restAgentEngine;
    private IRestAgentTriggerStore agentTriggerStore;
    private IUserConversationStore userConversationStore;
    private McpConversationTools tools;

    @BeforeEach
    void setUp() {
        conversationService = mock(IConversationService.class);
        restAgentEngine = mock(IRestAgentEngine.class);
        agentTriggerStore = mock(IRestAgentTriggerStore.class);
        userConversationStore = mock(IUserConversationStore.class);

        tools = new McpConversationTools(
                conversationService, mock(ai.labs.eddi.engine.api.IRestAgentAdministration.class),
                mock(ai.labs.eddi.configs.agents.IRestAgentStore.class),
                mock(ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory.class),
                new JsonSerialization(new ObjectMapper()),
                mock(ai.labs.eddi.engine.runtime.BoundedLogStore.class),
                mock(ai.labs.eddi.engine.audit.rest.IRestAuditStore.class),
                agentTriggerStore, userConversationStore, restAgentEngine,
                mock(io.quarkus.security.identity.SecurityIdentity.class), false);
    }

    @Test
    void chatManaged_alreadyPaused_returnsStructuredResult_andKeepsMapping() throws Exception {
        var existing = new UserConversation("customer_support", "U1",
                Deployment.Environment.production, "agent-1", "conv-1");
        when(userConversationStore.readUserConversation("customer_support", "U1")).thenReturn(existing);
        // Trigger still exists (no exception), conversation not ENDED → existing reused
        when(agentTriggerStore.readAgentTrigger("customer_support"))
                .thenReturn(mock(ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration.class));
        when(restAgentEngine.getConversationState("conv-1")).thenReturn(ConversationState.AWAITING_HUMAN);
        // say throws — conversation already paused
        doThrow(new IConversationService.ConversationAwaitingApprovalException("paused"))
                .when(conversationService).say(eq("conv-1"), any(), any(), any(), any(), anyBoolean(), any());

        String result = tools.chatManaged("customer_support", "U1", "hi", "production");

        assertTrue(result.contains("PAUSED_FOR_APPROVAL"), result);
        assertTrue(result.contains("conv-1"));
        assertTrue(result.contains("/resume"));
        // The stale mapping must NOT be deleted — re-invoking after approval reuses it
        verify(userConversationStore, never()).deleteUserConversation("customer_support", "U1");
    }

    @Test
    void chatManaged_turnPauses_returnsStructuredResult() throws Exception {
        var existing = new UserConversation("customer_support", "U1",
                Deployment.Environment.production, "agent-1", "conv-1");
        when(userConversationStore.readUserConversation("customer_support", "U1")).thenReturn(existing);
        when(agentTriggerStore.readAgentTrigger("customer_support"))
                .thenReturn(mock(ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration.class));
        when(restAgentEngine.getConversationState("conv-1")).thenReturn(ConversationState.READY);

        // say completes with an AWAITING_HUMAN snapshot (this turn paused)
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(6);
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(eq("conv-1"), any(), any(), any(), any(), anyBoolean(), any());

        String result = tools.chatManaged("customer_support", "U1", "please delete prod", "production");

        assertTrue(result.contains("PAUSED_FOR_APPROVAL"), result);
        assertTrue(result.contains("AWAITING_HUMAN"));
    }
}
