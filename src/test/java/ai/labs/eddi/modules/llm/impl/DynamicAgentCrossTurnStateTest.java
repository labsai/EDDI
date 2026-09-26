/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import ai.labs.eddi.modules.llm.tools.TeardownAgentTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The dynamic-agent state that has to survive the turn boundary, on a real
 * {@link ConversationMemory}: the retain flags (M-T2) and the conversations
 * {@code converse_with_agent} started (C6). Both are rebuilt from step data on
 * every turn, because the tool instances themselves are built per turn.
 */
class DynamicAgentCrossTurnStateTest {

    private IConversationService conversationService;
    private IAgentFactory agentFactory;
    private DynamicAgentToolsProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        agentFactory = mock(IAgentFactory.class);
        provider = new DynamicAgentToolsProvider(mock(AgentSetupService.class), mock(CapabilityRegistryService.class), conversationService,
                agentFactory, mock(IAgentStore.class), mock(IDeploymentStore.class), new LiveDiscussionRegistry(),
                mock(IAgentGroupStore.class));
        doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    private static ConversationMemory memory() {
        return new ConversationMemory("conv-1", "agent-1", 1, "user-1");
    }

    private <T> T build(ConversationMemory memory, String toolName, Class<T> type) {
        List<Object> tools = new ArrayList<>();
        provider.addDynamicAgentTools(tools, List.of(toolName), memory);
        assertEquals(1, tools.size());
        return assertInstanceOf(type, tools.get(0));
    }

    @Test
    @DisplayName("M-T2: an agent retained on turn 1 is still protected from teardown on turn 2")
    void retainFlagSurvivesTheTurn() {
        var memory = memory();
        memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_CREATED_AGENT_IDS, List.of("sub-1")));
        memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_RETAINED_AGENT_IDS, Set.of("sub-1")));
        memory.startNextStep();

        String result = build(memory, "teardown_agent", TeardownAgentTool.class).teardownAgent("sub-1", true);

        assertTrue(result.contains("retained"), result);
        verifyNoInteractions(agentFactory);
    }

    @Test
    @DisplayName("M-T2: an unretain on a later turn wins — the latest recorded set is the state, not the union")
    void unretainIsNotResurrected() {
        var memory = memory();
        memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_CREATED_AGENT_IDS, List.of("sub-1")));
        memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_RETAINED_AGENT_IDS, Set.of("sub-1")));
        memory.startNextStep();
        memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_RETAINED_AGENT_IDS, Set.of()));
        memory.startNextStep();

        Collection<String> seeded = DynamicAgentToolsProvider.seedRetainedAgentIds(memory, List.of("sub-1"));

        assertTrue(seeded.isEmpty(), "retained: " + seeded);
    }

    @Test
    @DisplayName("M-T2: a retain flag on an agent no longer tracked as created is dropped")
    void retainOfUntrackedAgentIsDropped() {
        var memory = memory();
        memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_RETAINED_AGENT_IDS, Set.of("gone", "kept")));
        memory.startNextStep();

        assertEquals(Set.of("kept"), Set.copyOf(DynamicAgentToolsProvider.seedRetainedAgentIds(memory, List.of("kept"))));
    }

    @Test
    @DisplayName("C6: a conversation started through the tool on turn 1 can be continued on turn 2")
    void delegatedConversationSurvivesTheTurn() throws Exception {
        var memory = memory();
        memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_DELEGATED_CONVERSATION_IDS, Set.of("conv-b")));
        memory.startNextStep();

        String result = build(memory, "converse_with_agent", ConverseWithAgentTool.class).converseWithAgent("agent-b", "follow-up",
                "conv-b");

        assertFalse(result.contains("cannot be continued"), result);
        verify(conversationService).say(any(), eq("agent-b"), eq("conv-b"), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("C6: the started set is written back to this turn's step, so the next turn can seed from it")
    void delegatedConversationsArePersistedOnTheCurrentStep() {
        var memory = memory();
        memory.getCurrentStep().storeData(new Data<Object>(MemoryKeys.DYNAMIC_DELEGATED_CONVERSATION_IDS, Set.of("conv-b")));
        memory.startNextStep();

        build(memory, "converse_with_agent", ConverseWithAgentTool.class);

        var stored = memory.getCurrentStep().getLatestData(MemoryKeys.DYNAMIC_DELEGATED_CONVERSATION_IDS);
        assertTrue(stored != null && stored.getResult() instanceof Collection<?> ids && ids.contains("conv-b"),
                "the current step must carry the cumulative set");
    }

    @Test
    @DisplayName("C6: a conversation id this conversation never started is refused")
    void foreignConversationRefused() throws Exception {
        String result = build(memory(), "converse_with_agent", ConverseWithAgentTool.class).converseWithAgent("agent-b", "hi",
                "someone-elses-conversation");

        assertTrue(result.contains("cannot be continued"), result);
    }
}
