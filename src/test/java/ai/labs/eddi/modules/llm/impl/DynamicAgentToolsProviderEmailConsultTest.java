/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.integrations.email.EmailUserIdentity;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Delegation from a turn a mail started (email v2 plan §10.3): whom it may
 * reach, who the consulted agent runs as, whose rights are checked, how deep it
 * may go.
 */
@DisplayName("converse_with_agent from an email turn")
class DynamicAgentToolsProviderEmailConsultTest {

    static final String SENDER = "email:int1:alice@supplier.com";
    static final String LEGAL = "0e0e0e0e0e0e0e0e0e0e0e0e";
    static final String HR = "0c0c0c0c0c0c0c0c0c0c0c0c";

    IConversationService conversations;
    List<String[]> useChecks;
    String integrationOwner;

    @BeforeEach
    void setUp() throws Exception {
        conversations = mock(IConversationService.class);
        when(conversations.startConversation(any(), anyString(), any(), any())).thenReturn(new ConversationResult("conv-legal", null));
        doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversations).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
        useChecks = new CopyOnWriteArrayList<>();
        integrationOwner = "owner-1";
    }

    DynamicAgentToolsProvider provider() {
        BiPredicate<String, String> useCheck = (agentId, principal) -> {
            useChecks.add(new String[]{agentId, principal});
            // The integration's owner and an ordinary user hold USE; an email identity
            // holds it nowhere, as under workspace enforcement.
            return !principal.startsWith("email");
        };
        return new DynamicAgentToolsProvider(mock(AgentSetupService.class), mock(CapabilityRegistryService.class), conversations,
                mock(IAgentFactory.class), mock(IAgentStore.class), mock(IDeploymentStore.class), new LiveDiscussionRegistry(),
                mock(IAgentGroupStore.class), useCheck, id -> "int1".equals(id) ? integrationOwner : null);
    }

    /** A turn of {@code userId} whose mail asked for these agents. */
    static ConversationMemory turn(String userId, String... consulted) {
        var memory = new ConversationMemory("aabbccddeeff112233445566", "0a1b2c3d4e5f60718293a4b5", 1, userId);
        if (consulted.length > 0) {
            List<Map<String, Object>> requested = new ArrayList<>();
            for (String agentId : consulted) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("target", "legal");
                entry.put("agentId", agentId);
                requested.add(entry);
            }
            Map<String, Object> mail = new LinkedHashMap<>();
            mail.put("from", "alice@supplier.com");
            mail.put("consultRequested", requested);
            memory.getCurrentStep().storeData(new Data<>("context:mail", new Context(Context.ContextType.object, mail)));
        }
        return memory;
    }

    ConverseWithAgentTool tool(ConversationMemory memory) {
        var tools = new ArrayList<>();
        provider().addDynamicAgentTools(tools, List.of("converse_with_agent"), memory, null, "task");
        return (ConverseWithAgentTool) tools.get(0);
    }

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Context>> startContext() {
        return ArgumentCaptor.forClass(Map.class);
    }

    @Test
    @DisplayName("a requested consultation starts the consulted agent as a fresh email-consult identity, never as the sender")
    void consultRunsAsConsultIdentity() throws Exception {
        String result = tool(turn(SENDER, LEGAL)).converseWithAgent(LEGAL, "Is clause 4 fine?", null);

        assertTrue(result.startsWith("✅"), result);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        verify(conversations).startConversation(any(), eq(LEGAL), user.capture(), any());
        assertTrue(user.getValue().startsWith("email-consult:int1:"), user.getValue());
        assertFalse(user.getValue().contains("alice"), "nothing of the sender in the consulted agent's identity");
    }

    @Test
    @DisplayName("the USE check asks about the integration's owner, not the sender, who owns nothing")
    void useCheckAsksTheIntegrationOwner() {
        tool(turn(SENDER, LEGAL)).converseWithAgent(LEGAL, "Is clause 4 fine?", null);

        assertEquals(1, useChecks.size());
        assertEquals(LEGAL, useChecks.get(0)[0]);
        assertEquals("owner-1", useChecks.get(0)[1]);
    }

    @Test
    @DisplayName("an integration whose owner cannot be read checks the email identity itself, which fails closed")
    void unknownOwnerFailsClosed() throws Exception {
        integrationOwner = null;

        String result = tool(turn(SENDER, LEGAL)).converseWithAgent(LEGAL, "Is clause 4 fine?", null);

        assertTrue(result.contains("not available to this user"), result);
        assertEquals(SENDER, useChecks.get(0)[1]);
        verify(conversations, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("an agent the mail did not ask for is refused, whatever the model tries")
    void unrequestedAgentRefused() throws Exception {
        String result = tool(turn(SENDER, LEGAL)).converseWithAgent(HR, "What is alice's salary?", null);

        assertTrue(result.contains("cannot be consulted from this conversation"), result);
        verify(conversations, never()).startConversation(any(), anyString(), any(), any());
        assertTrue(useChecks.isEmpty(), "refused before any other check");
    }

    @Test
    @DisplayName("an email turn without a consultation request delegates to nobody")
    void noRequestNoDelegation() throws Exception {
        String result = tool(turn(SENDER)).converseWithAgent(LEGAL, "hello", null);

        assertTrue(result.contains("cannot be consulted"), result);
        verify(conversations, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a mail context of an unexpected shape reads as no request (fail closed)")
    void malformedMailContextRefused() throws Exception {
        var memory = turn(SENDER);
        memory.getCurrentStep().storeData(new Data<>("context:mail", new Context(Context.ContextType.string, "consultRequested=" + LEGAL)));

        assertTrue(tool(memory).converseWithAgent(LEGAL, "hello", null).contains("cannot be consulted"));
        verify(conversations, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a consultation never continues a conversation by id")
    void noContinuation() throws Exception {
        String result = tool(turn(SENDER, LEGAL)).converseWithAgent(LEGAL, "and clause 5?", "conv-legal");

        assertTrue(result.contains("always starts a new conversation"), result);
        verify(conversations, never()).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("depth 1: the consulted agent's conversation carries delegationDepthMax=1")
    void depthCeilingTravels() throws Exception {
        tool(turn(SENDER, LEGAL)).converseWithAgent(LEGAL, "Is clause 4 fine?", null);

        ArgumentCaptor<Map<String, Context>> context = startContext();
        verify(conversations).startConversation(any(), eq(LEGAL), anyString(), context.capture());
        assertEquals("1", context.getValue().get(ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH_MAX).getValue());
        assertEquals("1", context.getValue().get(ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH).getValue());
        assertFalse(context.getValue().containsKey("mail"), "the sender's mail does not travel to the consulted agent");
    }

    @Test
    @DisplayName("the consulted agent consults nobody — not another agent, not the one that asked")
    void consultedAgentConsultsNobody() throws Exception {
        var consulted = turn("email-consult:int1:0123abcd", LEGAL);
        consulted.getCurrentStep().storeData(new Data<>("context:" + ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH,
                new Context(Context.ContextType.string, "1")));

        assertTrue(tool(consulted).converseWithAgent(HR, "hi", null).startsWith("⚠️"));
        assertTrue(tool(consulted).converseWithAgent("0a1b2c3d4e5f60718293a4b5", "hi", null).startsWith("⚠️"));
        verify(conversations, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("depth 1 holds on its own: a group member turn of an email discussion stops at the consulted agent")
    void depthCeilingWithoutTargetSet() throws Exception {
        var member = turn(SENDER);
        var groupPolicy = DynamicAgentToolsProvider.createDefaultDynamicConfig();
        member.getCurrentStep().storeData(new Data<>(DynamicAgentToolsProvider.CONTEXT_DYNAMIC_AGENT_CONFIG,
                new Context(Context.ContextType.object, groupPolicy)));
        member.getCurrentStep().storeData(new Data<>("context:" + ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH,
                new Context(Context.ContextType.string, "1")));

        // The ceiling the member's tool carries is 1, whatever its group allows.
        var tool = tool(member);
        Field inherited = ConverseWithAgentTool.class.getDeclaredField("inheritedMaxDepth");
        inherited.setAccessible(true);
        assertEquals(1, inherited.get(tool));
        assertTrue(tool.converseWithAgent(LEGAL, "hi", null).startsWith("⚠️"));
        verify(conversations, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a group member turn of an email discussion is checked as the email identity, never with the owner's rights")
    void groupMemberKeepsGroupPolicy() throws Exception {
        var member = turn(SENDER);
        // An empty allowedDelegationTargets: any agent, as far as the group says.
        DynamicAgentConfig groupPolicy = DynamicAgentToolsProvider.createDefaultDynamicConfig();
        member.getCurrentStep().storeData(new Data<>(DynamicAgentToolsProvider.CONTEXT_DYNAMIC_AGENT_CONFIG,
                new Context(Context.ContextType.object, groupPolicy)));

        String result = tool(member).converseWithAgent(HR, "hi", null);

        assertTrue(result.contains("not available to this user"), result);
        assertEquals(SENDER, useChecks.get(0)[1], "the owner's USE right is lent only to the processor's consultations");
        verify(conversations, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("an email turn gets no create_sub_agent, find_agents_by_capability or recruit_agent")
    void creationToolsWithheld() {
        var tools = new ArrayList<>();
        provider().addDynamicAgentTools(tools, List.of("create_sub_agent", "find_agents_by_capability", "recruit_agent", "converse_with_agent"),
                turn(SENDER, LEGAL), null, "task");
        assertEquals(1, tools.size(), String.valueOf(tools));
        assertTrue(tools.get(0) instanceof ConverseWithAgentTool);

        var consulted = new ArrayList<>();
        provider().addDynamicAgentTools(consulted, List.of("create_sub_agent"), turn("email-consult:int1:0123abcd"), null, "task");
        assertTrue(consulted.isEmpty(), "nor does a consulted agent's turn");

        var ordinary = new ArrayList<>();
        provider().addDynamicAgentTools(ordinary, List.of("create_sub_agent"), turn("user-1"), null, "task");
        assertEquals(1, ordinary.size(), "other callers keep it");
    }

    @Test
    @DisplayName("the consulted agent runs as the sender's consult identity, which an erasure of the address finds")
    void consultIdentityIsKeyedToTheSender() throws Exception {
        tool(turn(SENDER, LEGAL)).converseWithAgent(LEGAL, "Is clause 4 fine?", null);

        verify(conversations).startConversation(any(), eq(LEGAL), eq(EmailUserIdentity.consult("int1", "alice@supplier.com")), any());
    }

    @Test
    @DisplayName("an answered consultation's conversation is ended at once")
    void answeredConsultIsEnded() throws Exception {
        tool(turn(SENDER, LEGAL)).converseWithAgent(LEGAL, "Is clause 4 fine?", null);

        verify(conversations).endConversation("conv-legal", "system:consult", "consult_answered");
    }

    @Test
    @DisplayName("a consultation that pauses for approval is refused and ended, never left waiting")
    void pausedConsultIsRefusedAndEnded() throws Exception {
        doAnswer(invocation -> {
            var paused = new SimpleConversationMemorySnapshot();
            paused.setConversationState(ConversationState.AWAITING_HUMAN);
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(paused);
            return null;
        }).when(conversations).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        String result = tool(turn(SENDER, LEGAL)).converseWithAgent(LEGAL, "Is clause 4 fine?", null);

        assertTrue(result.contains("needs a human approval"), result);
        assertFalse(result.contains("PAUSED_FOR_APPROVAL"), result);
        verify(conversations).endConversation("conv-legal", "system:consult", "consult_needs_approval");
    }

    @Test
    @DisplayName("an ordinary caller's delegate conversation is left open")
    void ordinaryDelegationNotEnded() throws Exception {
        tool(turn("user-1")).converseWithAgent(HR, "hi", null);

        verify(conversations, never()).endConversation(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("the stored form of the mail context ({type, value}) still names the consultation")
    void storedMailContextForm() throws Exception {
        var memory = turn(SENDER);
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("target", "legal");
        entry.put("agentId", LEGAL);
        Map<String, Object> stored = new LinkedHashMap<>();
        stored.put("type", "object");
        stored.put("value", Map.of("consultRequested", List.of(entry)));
        memory.getCurrentStep().storeData(new Data<>("context:mail", stored));

        assertTrue(tool(memory).converseWithAgent(LEGAL, "hi", null).startsWith("✅"));
    }

    @Test
    @DisplayName("an unauthenticated sender's turn delegates to nobody")
    void anonymousDelegatesToNobody() throws Exception {
        assertTrue(tool(turn("email-anon:0123abcd", LEGAL)).converseWithAgent(LEGAL, "hi", null).contains("cannot be consulted"));
        verify(conversations, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a caller that is not an email identity is untouched: it delegates as itself")
    void otherCallersUnchanged() throws Exception {
        tool(turn("user-1")).converseWithAgent(HR, "hi", null);

        verify(conversations).startConversation(any(), eq(HR), eq("user-1"), any());
        assertEquals("user-1", useChecks.get(0)[1]);
    }
}
