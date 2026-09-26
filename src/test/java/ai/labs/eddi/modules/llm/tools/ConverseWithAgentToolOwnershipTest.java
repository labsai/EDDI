/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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

/**
 * C6 — {@code converse_with_agent} drove a turn in any conversation id the
 * model supplied, through the engine-internal {@code say} overload that
 * performs no ownership check, as that conversation's owner. It may now
 * continue only a conversation it started itself — in this turn or, through the
 * shared set the provider persists in step data, an earlier one.
 */
@DisplayName("ConverseWithAgentTool — continues only conversations it started (C6)")
class ConverseWithAgentToolOwnershipTest {

    private IConversationService conversationService;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        lenient().when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenReturn(new ConversationResult("conv-started", null));
        lenient().doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    private static DynamicAgentConfig config(int maxPerTask) {
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowDelegation(true);
        config.setMaxDelegationsPerTask(maxPerTask);
        return config;
    }

    @Test
    @DisplayName("a conversation id the tool never started is refused before any turn runs")
    void foreignConversationIdIsRefused() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "attacker", config(5), 0, ConcurrentHashMap.newKeySet());

        String result = tool.converseWithAgent("agent-b", "transfer everything", "victims-conversation");

        assertTrue(result.contains("cannot be continued"), result);
        verify(conversationService, never()).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(),
                any());
        verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a conversation started on an earlier turn can be continued on the next one")
    void conversationStartedEarlierCanBeContinued() throws Exception {
        Set<String> persistedAcrossTurns = ConcurrentHashMap.newKeySet();

        new ConverseWithAgentTool(conversationService, "user-1", config(5), 0, persistedAcrossTurns)
                .converseWithAgent("agent-b", "hello", null);
        assertTrue(persistedAcrossTurns.contains("conv-started"), "a started conversation must be recorded");

        // Next turn: a fresh tool instance, seeded from the same persisted set.
        String result = new ConverseWithAgentTool(conversationService, "user-1", config(5), 0, persistedAcrossTurns)
                .converseWithAgent("agent-b", "follow-up", " conv-started ");

        assertFalse(result.contains("cannot be continued"), result);
        verify(conversationService, times(2)).say(any(), eq("agent-b"), eq("conv-started"), anyBoolean(), anyBoolean(), any(), any(),
                anyBoolean(), any());
    }

    @Test
    @DisplayName("a refused id does not consume the per-task delegation budget")
    void refusalDoesNotBurnADelegationSlot() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-1", config(1), 0, ConcurrentHashMap.newKeySet());

        tool.converseWithAgent("agent-b", "probe", "someone-elses");
        String result = tool.converseWithAgent("agent-b", "real work", null);

        assertFalse(result.contains("Maximum delegations"), result);
        verify(conversationService).startConversation(any(), eq("agent-b"), any(), any());
    }

    @Test
    @DisplayName("the legacy constructors start from an empty set — nothing foreign can be continued")
    void legacyConstructorsAreClosedByDefault() {
        String result = new ConverseWithAgentTool(conversationService, "user-1")
                .converseWithAgent("agent-b", "hi", "conv-anything");

        assertTrue(result.contains("cannot be continued"), result);
    }
}
