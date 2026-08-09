/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.model.Context;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code dynamicAgentConfig} context is the only typed POJO the system
 * injects into conversation context, and it gates the tools that deploy real
 * agents to production. These tests pin what it must resolve to on each of the
 * four occasions the value can be observed: the injecting turn, a turn read
 * back from the store, a turn with no injection at all, and a genuinely
 * standalone conversation.
 */
@DisplayName("dynamicAgentConfig — context serialization boundary")
class DynamicAgentConfigContextBoundaryTest {

    private static final String CONTEXT_KEY = "context:dynamicAgentConfig";
    private static final String GROUP_KEY = "context:groupConversationId";

    /** Guardrails a cautious operator would write: everything off. */
    private static DynamicAgentConfig restrictiveConfig() {
        var config = new DynamicAgentConfig();
        config.setEnabled(false);
        config.setAllowCreation(false);
        config.setAllowRecruitment(false);
        config.setAllowDelegation(false);
        config.setMaxCreatedAgentsPerDiscussion(1);
        return config;
    }

    /**
     * Exactly what conversation memory does to a stored context: the typed value
     * becomes a plain map, and is rebuilt as {@code new Context(type, map)}.
     *
     * @see ai.labs.eddi.engine.memory.ConversationMemoryStore
     */
    private static Context afterStoreRoundTrip(DynamicAgentConfig config) {
        Map<?, ?> asDocument = new ObjectMapper().convertValue(config, Map.class);
        return new Context(Context.ContextType.object, asDocument);
    }

    /**
     * A memory whose current step holds {@code currentStepData} and whose earlier
     * steps hold {@code priorStepData} — the shape {@code getAllLatestData}
     * returns.
     */
    private static IConversationMemory memoryWith(Map<String, Object> currentStepData, Map<String, Object> priorStepData) {
        var memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn("member-agent");
        when(memory.getUserId()).thenReturn("user-1");

        var currentStep = mock(IWritableConversationStep.class);
        for (var entry : currentStepData.entrySet()) {
            when(currentStep.getLatestData(entry.getKey())).thenReturn(new Data<>(entry.getKey(), entry.getValue()));
        }
        when(memory.getCurrentStep()).thenReturn(currentStep);

        var allSteps = mock(IConversationStepStack.class);
        for (var entry : priorStepData.entrySet()) {
            List<IData<Object>> entries = List.of(new Data<>(entry.getKey(), entry.getValue()));
            when(allSteps.getAllLatestData(entry.getKey())).thenReturn(entries);
        }
        when(memory.getAllSteps()).thenReturn(allSteps);
        return memory;
    }

    @Nested
    @DisplayName("(a) on the injecting turn")
    class InjectingTurn {

        @Test
        @DisplayName("the live POJO is used as-is")
        void livePojoIsUsed() {
            var injected = restrictiveConfig();
            var memory = memoryWith(Map.of(CONTEXT_KEY, new Context(Context.ContextType.object, injected)), Map.of());

            assertEquals(injected, DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory));
        }
    }

    @Nested
    @DisplayName("(b) after a store round-trip")
    class AfterStoreReload {

        /**
         * The defect: conversation memory rebuilds a stored context with a Map value,
         * so an {@code instanceof DynamicAgentConfig} check failed even though the key
         * was present — and the resolver fell through to the fully permissive
         * standalone default.
         */
        @Test
        @DisplayName("the map form still resolves to the configured guardrails, not the permissive default")
        void mapFormIsCoerced() {
            var memory = memoryWith(Map.of(CONTEXT_KEY, afterStoreRoundTrip(restrictiveConfig())), Map.of());

            DynamicAgentConfig resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertFalse(resolved.isEnabled(), "a reloaded turn must not silently become enabled");
            assertFalse(resolved.isAllowCreation(), "a reloaded turn must not silently gain agent creation");
            assertFalse(resolved.isAllowRecruitment());
            assertFalse(resolved.isAllowDelegation());
            assertEquals(1, resolved.getMaxCreatedAgentsPerDiscussion(), "numeric caps must survive the round-trip too");
        }

        @Test
        @DisplayName("a permissive group config still round-trips as permissive")
        void permissiveConfigSurvivesToo() {
            var permissive = new DynamicAgentConfig();
            permissive.setEnabled(true);
            permissive.setAllowCreation(true);
            permissive.setMaxCreatedAgentsPerDiscussion(7);

            var memory = memoryWith(Map.of(CONTEXT_KEY, afterStoreRoundTrip(permissive)), Map.of());

            DynamicAgentConfig resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);
            assertTrue(resolved.isEnabled());
            assertTrue(resolved.isAllowCreation());
            assertEquals(7, resolved.getMaxCreatedAgentsPerDiscussion(), "coercion must not flatten values back to defaults");
        }
    }

    @Nested
    @DisplayName("(c) on a turn with no fresh injection — resume, crash recovery, follow-up")
    class NoFreshInjection {

        /**
         * A resumed turn re-enters without the original context map. The sibling
         * resolvers ({@code resolveGroupIds}, {@code resolveDelegationDepth}) already
         * fall back to earlier steps for exactly this reason; this one did not.
         */
        @Test
        @DisplayName("falls back to the guardrails carried by an earlier step")
        void fallsBackToEarlierStep() {
            var memory = memoryWith(Map.of(), Map.of(CONTEXT_KEY, afterStoreRoundTrip(restrictiveConfig())));

            DynamicAgentConfig resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertFalse(resolved.isAllowCreation(), "the group's guardrails must survive a turn that carries no context");
        }

        /**
         * The fail-closed rule. A conversation that is demonstrably part of a group but
         * whose guardrails cannot be resolved must not inherit the permissive
         * standalone default — that would hand agent-deploying tools to a member of a
         * discussion whose operator never opted in.
         */
        @Test
        @DisplayName("group context with unresolvable guardrails fails CLOSED")
        void groupContextWithoutConfigFailsClosed() {
            var memory = memoryWith(Map.of(GROUP_KEY, new Context(Context.ContextType.string, "gc-1")), Map.of());

            DynamicAgentConfig resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertFalse(resolved.isEnabled(), "a group turn with no resolvable config must not be enabled");
            assertFalse(resolved.isAllowCreation(), "a group turn with no resolvable config must not permit agent creation");
            assertFalse(resolved.isAllowRecruitment());
        }

        @Test
        @DisplayName("an unreadable stored value also fails closed for a group turn")
        void malformedValueFailsClosed() {
            var memory = memoryWith(Map.of(CONTEXT_KEY, new Context(Context.ContextType.object, "not-a-config")), Map.of());

            DynamicAgentConfig resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertFalse(resolved.isAllowCreation());
        }
    }

    @Nested
    @DisplayName("(d) a genuinely standalone conversation")
    class Standalone {

        /**
         * The permissive default must stay reachable for the case it was written for: a
         * lone agent whose designer explicitly whitelisted these tools. Failing closed
         * everywhere would silently disable a supported configuration.
         */
        @Test
        @DisplayName("keeps the permissive default")
        void permissiveDefaultPreserved() {
            var memory = memoryWith(Map.of(), Map.of());

            DynamicAgentConfig resolved = DynamicAgentToolsProvider.resolveDynamicAgentConfig(memory);

            assertTrue(resolved.isEnabled());
            assertTrue(resolved.isAllowCreation());
            assertTrue(resolved.isAllowRecruitment());
            assertTrue(resolved.isAllowDelegation());
        }
    }
}
