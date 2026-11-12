/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.security.ClientContextGuard;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A delegation's spend used to escape every ceiling of the delegating
 * conversation: the callee runs in a conversation of its own, and nothing
 * rolled its cost back or passed the caller's ceiling down. A model on an agent
 * with {@code maxCostPerConversationUsd} could keep delegating, and only the
 * tenant's monthly budget stopped it.
 */
@DisplayName("Delegation — the caller's cost ceiling")
class DelegationCostCeilingTest {

    private static final String CONVERSATION = "0123456789abcdef01234567";

    private IConversationService conversationService;
    private ConversationMemory memory;
    private SimpleConversationMemorySnapshot calleeTurn;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        memory = new ConversationMemory(CONVERSATION, "agent-a", 1, "user-1");
        calleeTurn = new SimpleConversationMemorySnapshot();
        when(conversationService.startConversation(any(), anyString(), any(), anyMap())).thenReturn(new ConversationResult("conv-b", null));
        doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(calleeTurn);
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    private ConverseWithAgentTool tool() {
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowDelegation(true);
        return new ConverseWithAgentTool(conversationService, "user-1", config, 0, null, null, new MemoryDelegationBudget(memory));
    }

    private void spent(double usd) {
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, usd));
    }

    @Test
    @DisplayName("the callee's spend is charged back and counts against the caller's ceiling")
    void calleeSpendIsChargedBack() {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.2);
        calleeTurn.setConversationCost(0.5);

        tool().converseWithAgent("agent-b", "hello", null);

        assertEquals(0.5, ((Number) memory.getCurrentStep().getData(MemoryKeys.AUDIT_DELEGATED_COST).getResult()).doubleValue(), 1e-9);
        assertEquals(0.7, LlmSpendGate.conversationCost(memory), 1e-9, "the conversation total includes the delegation");
        assertEquals(0.3, new MemoryDelegationBudget(memory).remainingUsd().getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("a continued conversation is charged only what this turn added")
    void continuedConversationIsChargedTheDelta() throws Exception {
        var owned = new ConversationMemorySnapshot();
        owned.setUserId("user-1");
        when(conversationService.getConversationMemorySnapshot("conv-b")).thenReturn(owned);
        // No cost recorded before (an empty snapshot totals 0); the turn reports 0.4.
        calleeTurn.setConversationCost(0.4);

        tool().converseWithAgent("agent-b", "again", "conv-b");

        assertEquals(0.4, LlmSpendGate.conversationCost(memory), 1e-9);
    }

    @Test
    @DisplayName("the caller's remaining allowance is passed down as the callee's ceiling")
    void remainingAllowanceIsPassedDown() throws Exception {
        memory.setConversationCostCeilingUsd(1.0);
        spent(0.75);

        tool().converseWithAgent("agent-b", "hello", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Context>> start = ArgumentCaptor.forClass(Map.class);
        verify(conversationService).startConversation(any(), eq("agent-b"), eq("user-1"), start.capture());
        assertEquals("0.25", start.getValue().get(ClientContextGuard.KEY_DELEGATION_MAX_COST).getValue());
        var input = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), input.capture(), anyBoolean(), any());
        assertEquals("0.25", input.getValue().getContext().get(ClientContextGuard.KEY_DELEGATION_MAX_COST).getValue());
    }

    @Test
    @DisplayName("without a ceiling nothing is passed down, but the spend is still recorded")
    void noCeilingNoContext() throws Exception {
        calleeTurn.setConversationCost(0.3);

        tool().converseWithAgent("agent-b", "hello", null);

        var input = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), input.capture(), anyBoolean(), any());
        assertNull(input.getValue().getContext().get(ClientContextGuard.KEY_DELEGATION_MAX_COST));
        assertEquals(0.3, LlmSpendGate.conversationCost(memory), 1e-9);
    }

    @Test
    @DisplayName("a caller at its ceiling does not delegate at all")
    void noDelegationPastTheCeiling() throws Exception {
        memory.setConversationCostCeilingUsd(0.5);
        spent(0.5);

        String result = tool().converseWithAgent("agent-b", "hello", null);

        assertTrue(result.contains("cost ceiling"), result);
        verify(conversationService, never()).startConversation(any(), anyString(), any(), anyMap());
    }

    @Test
    @DisplayName("the callee's spend gate takes the passed-down allowance as its ceiling")
    void calleeIsHeldToTheAllowance() {
        var callee = new ConversationMemory("abcdef0123456789abcdef01", "agent-b", 1, "user-1");
        // Earlier turns of the callee's conversation spent 2.00; this turn carries
        // 0.25.
        callee.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 2.0));
        callee.startNextStep();
        callee.getCurrentStep().storeData(new Data<>("context:" + ClientContextGuard.KEY_DELEGATION_MAX_COST,
                new Context(Context.ContextType.string, "0.25")));

        OptionalDouble ceiling = LlmSpendGate.effectiveCeiling(callee);
        assertEquals(2.25, ceiling.getAsDouble(), 1e-9, "per turn: the spend before it plus the allowance");

        callee.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.3));
        var refusal = new LlmSpendGate(null, null).check(callee);
        assertNotNull(refusal, "the callee must stop once the caller's allowance is spent");
        assertEquals(LlmSpendGate.REASON_CONVERSATION_CEILING, refusal.reason());
    }

    @Test
    @DisplayName("the callee's own, tighter ceiling still wins")
    void tighterOwnCeilingWins() {
        var callee = new ConversationMemory("abcdef0123456789abcdef01", "agent-b", 1, "user-1");
        callee.setConversationCostCeilingUsd(0.1);
        callee.getCurrentStep().storeData(new Data<>("context:" + ClientContextGuard.KEY_DELEGATION_MAX_COST,
                new Context(Context.ContextType.string, "5.0")));

        assertEquals(0.1, LlmSpendGate.effectiveCeiling(callee).getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("non-finite or non-positive charges are ignored")
    void badChargesIgnored() {
        var budget = new MemoryDelegationBudget(memory);
        budget.charge(Double.NaN);
        budget.charge(-1.0);
        budget.charge(0.0);

        assertNull(memory.getCurrentStep().getData(MemoryKeys.AUDIT_DELEGATED_COST));
        assertFalse(budget.remainingUsd().isPresent(), "no ceiling configured");
    }
}
