/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import ai.labs.eddi.modules.llm.tools.CreateSubAgentTool;
import ai.labs.eddi.modules.llm.tools.FindAgentsByCapabilityTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Drives {@code collectEnabledTools} — the real tool-list build — instead of
 * the extracted helpers only. Before this, F17 (created-agent seeding), F18
 * (delegation depth / guardrail config handed to {@code ConverseWithAgentTool})
 * and I4 ({@code allowRecruitment} gating the capability lookup) could all be
 * reverted with every test still green.
 */
@DisplayName("AgentOrchestrator — dynamic-agent tool wiring in collectEnabledTools")
class AgentOrchestratorBuiltInToolWiringTest {

    private AgentSetupService agentSetupService;
    private CapabilityRegistryService capabilityRegistryService;
    private IConversationService conversationService;
    private AgentOrchestrator orchestrator;

    @BeforeEach
    void setUp() throws Exception {
        agentSetupService = mock(AgentSetupService.class);
        capabilityRegistryService = mock(CapabilityRegistryService.class);
        conversationService = mock(IConversationService.class);
        lenient().when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenReturn(new ConversationResult("conv-callee", null));
        lenient().doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());

        orchestrator = new AgentOrchestrator(
                null, null, null, null,
                null, null, null, null,
                null, null,
                null, null,
                null, null, null,
                null, null, null,
                null, null, null,
                null,
                agentSetupService, capabilityRegistryService,
                conversationService, null, null,
                mock(IHitlToolJournalStore.class), new ConversationHistoryBuilder(), new TokenCounterFactory());
    }

    private static LlmConfiguration.Task taskWith(String... whitelist) {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(true);
        task.setBuiltInToolsWhitelist(List.of(whitelist));
        return task;
    }

    private static ConversationMemory memory() {
        return new ConversationMemory("conv-a", "agent-a", 1, "user-1");
    }

    private static void putContext(ConversationMemory memory, String key, Context value) {
        memory.getCurrentStep().storeData(new Data<>("context:" + key, value));
    }

    private static <T> T findTool(List<Object> tools, Class<T> type) {
        return tools.stream().filter(type::isInstance).map(type::cast).findFirst().orElse(null);
    }

    @Nested
    @DisplayName("F17 — created-agent cap bounds the discussion, not the turn")
    class CreatedAgentSeeding {

        @Test
        @DisplayName("the CreateSubAgentTool's shared list starts pre-seeded from earlier turns")
        void sharedListIsSeededFromPriorTurns() {
            var memory = memory();
            memory.getCurrentStep().storeData(new Data<Object>(AgentOrchestrator.KEY_DYNAMIC_CREATED_AGENT_IDS,
                    new ArrayList<>(List.of("agent-x", "agent-y", "agent-z"))));
            memory.startNextStep();

            List<Object> tools = orchestrator.collectEnabledTools(taskWith("create_sub_agent"), memory);

            assertNotNull(findTool(tools, CreateSubAgentTool.class), "create_sub_agent must be built");

            Object shared = memory.getCurrentStep()
                    .getLatestData(AgentOrchestrator.KEY_DYNAMIC_CREATED_AGENT_IDS).getResult();
            assertTrue(shared instanceof List, "tracking list must be published to the step");
            List<?> sharedIds = (List<?>) shared;
            assertEquals(3, sharedIds.size(),
                    "an empty list would restart maxCreatedAgentsPerDiscussion every turn: " + sharedIds);
            assertTrue(sharedIds.containsAll(List.of("agent-x", "agent-y", "agent-z")));
        }
    }

    @Nested
    @DisplayName("I4 — allowRecruitment gates the capability lookup")
    class RecruitmentGate {

        private DynamicAgentConfig groupConfig(boolean allowRecruitment) {
            var config = new DynamicAgentConfig();
            config.setEnabled(true);
            config.setAllowRecruitment(allowRecruitment);
            return config;
        }

        @Test
        @DisplayName("allowRecruitment=false suppresses find_agents_by_capability even when whitelisted")
        void suppressedWhenRecruitmentOff() {
            var memory = memory();
            putContext(memory, "dynamicAgentConfig", new Context(Context.ContextType.object, groupConfig(false)));

            List<Object> tools = orchestrator.collectEnabledTools(taskWith("find_agents_by_capability"), memory);

            assertFalse(tools.stream().anyMatch(FindAgentsByCapabilityTool.class::isInstance),
                    "recruitment is off — the discovery entry point must not be offered to the model");
        }

        @Test
        @DisplayName("allowRecruitment=true keeps find_agents_by_capability available")
        void presentWhenRecruitmentOn() {
            var memory = memory();
            putContext(memory, "dynamicAgentConfig", new Context(Context.ContextType.object, groupConfig(true)));

            List<Object> tools = orchestrator.collectEnabledTools(taskWith("find_agents_by_capability"), memory);

            assertNotNull(findTool(tools, FindAgentsByCapabilityTool.class));
        }
    }

    @Nested
    @DisplayName("F18 — the delegation tool is built with the resolved depth and the group's guardrails")
    class DelegationWiring {

        @Test
        @DisplayName("at the resolved depth the built tool refuses to delegate any further")
        void builtToolRefusesAtResolvedDepth() throws Exception {
            var memory = memory();
            // Three hops already: A→B→C→this agent. maxDelegationDepth defaults to 3.
            putContext(memory, ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH,
                    new Context(Context.ContextType.string, "3"));

            var tool = findTool(orchestrator.collectEnabledTools(taskWith("converse_with_agent"), memory),
                    ConverseWithAgentTool.class);
            assertNotNull(tool, "converse_with_agent must be built");

            String result = tool.converseWithAgent("agent-d", "keep going", null);

            assertTrue(result.contains("Maximum delegation depth"),
                    "a tool built at depth 0 would happily delegate a fourth hop: " + result);
            verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
        }

        @Test
        @DisplayName("a human-started conversation is depth 0 and may delegate")
        void builtToolDelegatesAtDepthZero() throws Exception {
            var tool = findTool(orchestrator.collectEnabledTools(taskWith("converse_with_agent"), memory()),
                    ConverseWithAgentTool.class);
            assertNotNull(tool);

            String result = tool.converseWithAgent("agent-b", "hi", null);

            assertTrue(result.contains("conv-callee"), result);
            verify(conversationService).startConversation(any(), eq("agent-b"), eq("user-1"), any());
        }

        @Test
        @DisplayName("the group's allowDelegation=false reaches the built tool")
        void groupConfigIsHandedToTheTool() throws Exception {
            var config = new DynamicAgentConfig();
            config.setEnabled(true);
            config.setAllowDelegation(false);
            var memory = memory();
            putContext(memory, "dynamicAgentConfig", new Context(Context.ContextType.object, config));

            var tool = findTool(orchestrator.collectEnabledTools(taskWith("converse_with_agent"), memory),
                    ConverseWithAgentTool.class);
            assertNotNull(tool);

            String result = tool.converseWithAgent("agent-b", "hi", null);

            assertTrue(result.contains("not enabled"),
                    "a tool built without the group config would delegate anyway: " + result);
            verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
        }
    }

    @Nested
    @DisplayName("F18 — end-to-end: the propagated depth survives to the callee's message step")
    class DepthPropagation {

        /**
         * Replays what {@code Conversation.prepareLifecycleData} does with a turn's
         * context map: one {@code context:<key>} Data entry on the step current at the
         * time.
         */
        private void materialize(ConversationMemory memory, Map<String, Context> context) {
            if (context != null) {
                context.forEach((key, value) -> memory.getCurrentStep().storeData(new Data<>("context:" + key, value)));
            }
        }

        private InputData capturedInputData() throws Exception {
            ArgumentCaptor<InputData> captor = ArgumentCaptor.forClass(InputData.class);
            verify(conversationService).say(any(), anyString(), anyString(),
                    anyBoolean(), anyBoolean(), any(), captor.capture(), anyBoolean(), any());
            return captor.getValue();
        }

        @Test
        @DisplayName("the callee resolves depth 1 on the step that answers the delegated message")
        void depthSurvivesTheStepBoundary() throws Exception {
            var tool = findTool(orchestrator.collectEnabledTools(taskWith("converse_with_agent"), memory()),
                    ConverseWithAgentTool.class);
            tool.converseWithAgent("agent-b", "hi", null);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Context>> startContext = ArgumentCaptor.forClass(Map.class);
            verify(conversationService).startConversation(any(), eq("agent-b"), eq("user-1"), startContext.capture());
            Map<String, Context> turnContext = capturedInputData().getContext();

            // Callee side: init() materializes the start context on step 0, then the
            // delegated message is processed on a FRESH step — which is the step the
            // orchestrator reads when it builds the callee's own tool list.
            var callee = new ConversationMemory("conv-b", "agent-b", 1, "user-1");
            materialize(callee, startContext.getValue());
            callee.startNextStep();
            materialize(callee, turnContext);

            assertEquals(1, AgentOrchestrator.resolveDelegationDepth(callee),
                    "the delegated agent must know it is one hop deep on the step it answers on");
        }

        @Test
        @DisplayName("a delegated follow-up into a conversation the tool did not start still resolves its depth")
        void depthTravelsOnTheFollowUpTurnAlone() throws Exception {
            var memory = memory();
            putContext(memory, ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH,
                    new Context(Context.ContextType.string, "1"));
            var tool = findTool(orchestrator.collectEnabledTools(taskWith("converse_with_agent"), memory),
                    ConverseWithAgentTool.class);

            tool.converseWithAgent("agent-b", "follow-up", "conv-b");

            // No start context exists anywhere in the callee's memory — the per-turn
            // context is the only carrier of the hop count.
            var callee = new ConversationMemory("conv-b", "agent-b", 1, "user-1");
            callee.startNextStep();
            materialize(callee, capturedInputData().getContext());

            assertEquals(2, AgentOrchestrator.resolveDelegationDepth(callee));
        }

        @Test
        @DisplayName("a turn without the delegation context does not reset an already-delegated conversation to 0")
        void depthFallsBackToEarlierSteps() {
            var callee = new ConversationMemory("conv-b", "agent-b", 1, "user-1");
            callee.getCurrentStep().storeData(new Data<Object>(
                    "context:" + ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH,
                    new Context(Context.ContextType.string, "2")));
            callee.startNextStep();

            assertEquals(2, AgentOrchestrator.resolveDelegationDepth(callee),
                    "reading only the current step makes the guard inert on every later turn");
        }
    }
}
