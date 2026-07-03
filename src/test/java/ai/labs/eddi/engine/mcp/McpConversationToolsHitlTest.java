/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
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

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Finding 25 / O3 / O4: the direct-conversation MCP tools (talk_to_agent,
 * chat_with_agent) and the managed variant (chat_managed) must return a
 * structured PAUSED_FOR_APPROVAL result — not a 60s hang, a generic error, or a
 * BUSY misreport — when a conversation is deliberately paused for human
 * approval; and chat_with_agent must preserve an auto-created conversation id
 * on a skip.
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
                new JsonSerialization(ai.labs.eddi.datastore.serialization.SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false)),
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
        // The paused payload names the MCP tool that resolves the gate, so an LLM
        // client can
        // chain the approval over MCP instead of dropping to REST (loop
        // discoverability).
        assertTrue(result.contains("\"suggestNextTool\":\"resume_conversation\""), result);
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

    @Test
    void chatManaged_busySkip_returnsBusy_notStaleReply() throws Exception {
        var existing = new UserConversation("customer_support", "U1",
                Deployment.Environment.production, "agent-1", "conv-1");
        when(userConversationStore.readUserConversation("customer_support", "U1")).thenReturn(existing);
        when(agentTriggerStore.readAgentTrigger("customer_support"))
                .thenReturn(mock(ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration.class));
        when(restAgentEngine.getConversationState("conv-1")).thenReturn(ConversationState.READY);

        // H7: say skips the queued turn (IN_PROGRESS busy) — must NOT be reported as
        // a fresh response.
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.IN_PROGRESS);
        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(6);
            handler.onSkipped(snapshot);
            return null;
        }).when(conversationService).say(eq("conv-1"), any(), any(), any(), any(), anyBoolean(), any());

        String result = tools.chatManaged("customer_support", "U1", "another message", "production");

        assertTrue(result.contains("BUSY"), result);
        assertTrue(result.contains("conv-1"));
        assertFalse(result.contains("PAUSED_FOR_APPROVAL"));
    }

    @Test
    void chatManaged_skipWhilePaused_returnsPausedForApproval() throws Exception {
        var existing = new UserConversation("customer_support", "U1",
                Deployment.Environment.production, "agent-1", "conv-1");
        when(userConversationStore.readUserConversation("customer_support", "U1")).thenReturn(existing);
        when(agentTriggerStore.readAgentTrigger("customer_support"))
                .thenReturn(mock(ai.labs.eddi.engine.triggermanagement.model.AgentTriggerConfiguration.class));
        when(restAgentEngine.getConversationState("conv-1")).thenReturn(ConversationState.AWAITING_HUMAN);

        // H7: a skip whose state is AWAITING_HUMAN → pending approval, not busy.
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(6);
            handler.onSkipped(snapshot);
            return null;
        }).when(conversationService).say(eq("conv-1"), any(), any(), any(), any(), anyBoolean(), any());

        String result = tools.chatManaged("customer_support", "U1", "another message", "production");

        assertTrue(result.contains("PAUSED_FOR_APPROVAL"), result);
        verify(userConversationStore, never()).deleteUserConversation("customer_support", "U1");
    }

    // ==================== O3: talk_to_agent ====================

    @Test
    void talkToAgent_alreadyPaused_returnsPausedForApproval() throws Exception {
        // Already-paused at submit: say() rejects the input synchronously.
        doThrow(new IConversationService.ConversationAwaitingApprovalException("paused"))
                .when(conversationService).say(eq("conv-1"), any(), any(), any(), any(), anyBoolean(), any());

        String result = tools.talkToAgent("agent-1", "conv-1", "hi", "production");

        assertTrue(result.contains("PAUSED_FOR_APPROVAL"), result);
        assertTrue(result.contains("conv-1"));
        assertTrue(result.contains("/resume"));
        assertTrue(result.contains("AWAITING_HUMAN"));
        assertTrue(result.contains("\"suggestNextTool\":\"resume_conversation\""), result);
    }

    @Test
    void talkToAgent_turnPauses_returnsPausedForApproval() throws Exception {
        // This turn pauses: say() completes with an AWAITING_HUMAN snapshot.
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(6);
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(eq("conv-1"), any(), any(), any(), any(), anyBoolean(), any());

        String result = tools.talkToAgent("agent-1", "conv-1", "please delete prod", "production");

        assertTrue(result.contains("PAUSED_FOR_APPROVAL"), result);
        assertTrue(result.contains("conv-1"));
        assertTrue(result.contains("AWAITING_HUMAN"));
    }

    @Test
    void talkToAgent_skipWhilePaused_returnsPausedForApproval() throws Exception {
        // Queued-then-paused: the turn is skipped with state AWAITING_HUMAN — a
        // deliberate pause, must NOT be reported as BUSY.
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(6);
            handler.onSkipped(snapshot);
            return null;
        }).when(conversationService).say(eq("conv-1"), any(), any(), any(), any(), anyBoolean(), any());

        String result = tools.talkToAgent("agent-1", "conv-1", "another message", "production");

        assertTrue(result.contains("PAUSED_FOR_APPROVAL"), result);
        assertFalse(result.contains("\"BUSY\""), result);
    }

    // ==================== O3 / O4: chat_with_agent ====================

    @Test
    void chatWithAgent_alreadyPaused_returnsPausedForApproval() throws Exception {
        // Already-paused at submit on an existing conversation.
        doThrow(new IConversationService.ConversationAwaitingApprovalException("paused"))
                .when(conversationService).say(eq("conv-1"), any(), any(), any(), any(), anyBoolean(), any());

        String result = tools.chatWithAgent("agent-1", "hi", "conv-1", "production");

        assertTrue(result.contains("PAUSED_FOR_APPROVAL"), result);
        assertTrue(result.contains("conv-1"));
        assertTrue(result.contains("/resume"));
    }

    @Test
    void chatWithAgent_turnPauses_returnsPausedForApproval() throws Exception {
        // This turn pauses on an existing conversation.
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(6);
            handler.onComplete(snapshot);
            return null;
        }).when(conversationService).say(eq("conv-1"), any(), any(), any(), any(), anyBoolean(), any());

        String result = tools.chatWithAgent("agent-1", "please delete prod", "conv-1", "production");

        assertTrue(result.contains("PAUSED_FOR_APPROVAL"), result);
        assertTrue(result.contains("conv-1"));
        assertTrue(result.contains("AWAITING_HUMAN"));
    }

    @Test
    void chatWithAgent_autoCreatedId_preservedOnSkip() throws Exception {
        // O4: no conversationId provided → chat_with_agent auto-creates one. When the
        // turn is then skipped (busy), the freshly-created id must be reported, NOT
        // the (null) method arg.
        when(conversationService.startConversation(any(), eq("agent-1"), any(), any()))
                .thenReturn(new ConversationResult("auto-created-conv", URI.create("eddi://conv/auto-created-conv")));

        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.IN_PROGRESS);
        doAnswer(inv -> {
            ConversationResponseHandler handler = inv.getArgument(6);
            handler.onSkipped(snapshot);
            return null;
        }).when(conversationService).say(eq("auto-created-conv"), any(), any(), any(), any(), anyBoolean(), any());

        String result = tools.chatWithAgent("agent-1", "hi", null, "production");

        assertTrue(result.contains("BUSY"), result);
        // The real auto-created id must be preserved (O4) — never a stale null.
        assertTrue(result.contains("auto-created-conv"), result);
        assertFalse(result.contains("null"), result);
    }
}
