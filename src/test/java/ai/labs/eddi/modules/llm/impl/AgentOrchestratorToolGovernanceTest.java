/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Findings F15 (external tools silently shadowing built-ins), F17
 * ({@code maxCreatedAgentsPerDiscussion} enforced per turn instead of per
 * discussion) and F18 (delegation depth propagation).
 */
@DisplayName("AgentOrchestrator — tool merge and dynamic-agent guardrails")
class AgentOrchestratorToolGovernanceTest {

    private static ToolExecutor executor(String marker) {
        return (request, memoryId) -> marker;
    }

    @Nested
    @DisplayName("F15 — tool name collisions")
    class ToolCollisions {

        @Test
        @DisplayName("a remote MCP tool named 'calculator' does not displace the built-in one")
        void remoteToolCannotShadowBuiltIn() {
            List<ToolSpecification> specs = new ArrayList<>(List.of(
                    ToolSpecification.builder().name("calculator").description("built-in").build()));
            Map<String, ToolExecutor> executors = new HashMap<>(Map.of("calculator", executor("builtin")));
            Map<String, String> sources = new HashMap<>(Map.of("calculator", "builtin"));

            AgentOrchestrator.mergeExternalTools(
                    List.of(ToolSpecification.builder().name("calculator").description("remote").build()),
                    Map.of("calculator", executor("remote")), "mcp", specs, executors, sources);

            assertEquals("builtin", executors.get("calculator").execute(null, null),
                    "the built-in executor must survive the collision");
            assertEquals("builtin", sources.get("calculator"));
            assertEquals(1, specs.size(), "the model must not be shown two tools with the same name");
        }

        @Test
        @DisplayName("a non-colliding remote tool is merged normally")
        void mergesDistinctNames() {
            List<ToolSpecification> specs = new ArrayList<>();
            Map<String, ToolExecutor> executors = new HashMap<>();
            Map<String, String> sources = new HashMap<>();

            AgentOrchestrator.mergeExternalTools(
                    List.of(ToolSpecification.builder().name("crm_lookup").build()),
                    Map.of("crm_lookup", executor("mcp")), "mcp", specs, executors, sources);

            assertEquals(1, specs.size());
            assertEquals("mcp", sources.get("crm_lookup"));
            assertEquals("mcp", executors.get("crm_lookup").execute(null, null));
        }

        @Test
        @DisplayName("precedence follows merge order: http beats a later mcp of the same name")
        void earlierSourceWins() {
            List<ToolSpecification> specs = new ArrayList<>();
            Map<String, ToolExecutor> executors = new HashMap<>();
            Map<String, String> sources = new HashMap<>();

            AgentOrchestrator.mergeExternalTools(List.of(ToolSpecification.builder().name("lookup").build()),
                    Map.of("lookup", executor("http")), "http", specs, executors, sources);
            AgentOrchestrator.mergeExternalTools(List.of(ToolSpecification.builder().name("lookup").build()),
                    Map.of("lookup", executor("mcp")), "mcp", specs, executors, sources);

            assertEquals("http", sources.get("lookup"));
            assertEquals("http", executors.get("lookup").execute(null, null));
            assertEquals(1, specs.size());
        }

        @Test
        @DisplayName("a spec with no executor is skipped rather than registered half-wired")
        void skipsSpecWithoutExecutor() {
            List<ToolSpecification> specs = new ArrayList<>();
            Map<String, ToolExecutor> executors = new HashMap<>();
            Map<String, String> sources = new HashMap<>();

            AgentOrchestrator.mergeExternalTools(List.of(ToolSpecification.builder().name("orphan").build()),
                    Map.of(), "a2a", specs, executors, sources);

            assertTrue(specs.isEmpty());
            assertFalse(executors.containsKey("orphan"));
        }
    }

    @Nested
    @DisplayName("F17 — created-agent seeding")
    class CreatedAgentSeeding {

        private IConversationMemory memoryWithPriorCreations(List<String> priorIds, Object contextValue) {
            IConversationMemory memory = mock(IConversationMemory.class);
            IWritableConversationStep currentStep = mock(IWritableConversationStep.class);
            IConversationStepStack allSteps = mock(IConversationStepStack.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            when(memory.getAllSteps()).thenReturn(allSteps);

            IData<Object> contextData = contextValue == null
                    ? null
                    : new Data<Object>("context:dynamicCreatedAgentIds", new Context(Context.ContextType.object, contextValue));
            when(currentStep.<Object>getLatestData("context:dynamicCreatedAgentIds")).thenReturn(contextData);

            List<IData<Object>> prior = priorIds == null
                    ? List.of()
                    : List.of(new Data<Object>(AgentOrchestrator.KEY_DYNAMIC_CREATED_AGENT_IDS, new ArrayList<>(priorIds)));
            when(allSteps.<Object>getAllLatestData(AgentOrchestrator.KEY_DYNAMIC_CREATED_AGENT_IDS)).thenReturn(prior);
            return memory;
        }

        @Test
        @DisplayName("agents created on earlier turns are counted, so the cap bounds the discussion not the turn")
        void seedsFromEarlierTurns() {
            var memory = memoryWithPriorCreations(List.of("agent-a", "agent-b", "agent-c"), null);

            List<String> seeded = AgentOrchestrator.seedCreatedAgentIds(memory);

            assertEquals(3, seeded.size(), "a fresh empty list would let the per-turn cap restart every phase");
            assertTrue(seeded.containsAll(List.of("agent-a", "agent-b", "agent-c")));
        }

        @Test
        @DisplayName("an injected discussion-wide total is honoured and de-duplicated against memory")
        void seedsFromContextAndDeduplicates() {
            var memory = memoryWithPriorCreations(List.of("agent-a"), List.of("agent-a", "agent-x"));

            List<String> seeded = AgentOrchestrator.seedCreatedAgentIds(memory);

            assertEquals(2, seeded.size(), "duplicates across sources must collapse: " + seeded);
            assertTrue(seeded.containsAll(List.of("agent-a", "agent-x")));
        }

        @Test
        @DisplayName("a first turn with nothing created seeds empty")
        void seedsEmptyOnFirstTurn() {
            var memory = memoryWithPriorCreations(List.of(), null);
            assertTrue(AgentOrchestrator.seedCreatedAgentIds(memory).isEmpty());
        }
    }

    @Nested
    @DisplayName("F18 — delegation depth")
    class DelegationDepth {

        private IConversationMemory memoryWithDepth(Object value) {
            IConversationMemory memory = mock(IConversationMemory.class);
            IWritableConversationStep currentStep = mock(IWritableConversationStep.class);
            when(memory.getCurrentStep()).thenReturn(currentStep);
            IData<Object> data = value == null
                    ? null
                    : new Data<Object>("context:delegationDepth", new Context(Context.ContextType.string, value));
            when(currentStep.<Object>getLatestData("context:" + ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH)).thenReturn(data);
            return memory;
        }

        @Test
        @DisplayName("a human-started conversation is depth 0")
        void humanStartedIsZero() {
            assertEquals(0, AgentOrchestrator.resolveDelegationDepth(memoryWithDepth(null)));
        }

        @Test
        @DisplayName("the propagated hop count is read back out of the callee's context")
        void readsPropagatedDepth() {
            assertEquals(2, AgentOrchestrator.resolveDelegationDepth(memoryWithDepth("2")));
        }

        @Test
        @DisplayName("a malformed depth degrades to 0 rather than failing the turn")
        void malformedDepthIsZero() {
            assertEquals(0, AgentOrchestrator.resolveDelegationDepth(memoryWithDepth("not-a-number")));
        }
    }

    @Test
    @DisplayName("the shared collision helper never substitutes an executor silently")
    void collisionHelperIsDeterministic() {
        Map<String, ToolExecutor> executors = new HashMap<>();
        ToolExecutor incumbent = executor("first");
        executors.put("dup", incumbent);

        AgentOrchestrator.mergeExternalTools(List.of(ToolSpecification.builder().name("dup").build()),
                Map.of("dup", executor("second")), "a2a", new ArrayList<>(), executors, new HashMap<>());

        assertSame(incumbent, executors.get("dup"));
    }
}
