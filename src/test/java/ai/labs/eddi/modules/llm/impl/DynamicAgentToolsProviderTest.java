/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.modules.llm.tools.ConverseWithAgentTool;
import ai.labs.eddi.modules.llm.tools.CreateSubAgentTool;
import ai.labs.eddi.modules.llm.tools.FindAgentsByCapabilityTool;
import ai.labs.eddi.modules.llm.tools.TeardownAgentTool;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link DynamicAgentToolsProvider}, extracted from the
 * anonymous block inside {@code AgentOrchestrator#collectAllBuiltInTools}
 * during the R2 (step 2) refactor. Covers the whitelist gating of each of the
 * four dynamic-agent tools plus {@code createDefaultDynamicConfig}'s permissive
 * defaults directly. The guardrail behavior of the tools themselves
 * ({@code allowRecruitment}, delegation depth/target bounds, the created-agent
 * cap) is covered by the unchanged
 * {@code AgentOrchestratorBuiltInToolWiringTest},
 * {@code AgentOrchestratorToolGovernanceTest} and
 * {@code ConverseWithAgentTool*Test} suites, re-verified green against this
 * class post-extraction rather than duplicated here.
 *
 * @author tests
 */
class DynamicAgentToolsProviderTest {

    private DynamicAgentToolsProvider provider() {
        return new DynamicAgentToolsProvider(mock(AgentSetupService.class), mock(CapabilityRegistryService.class),
                mock(IConversationService.class), mock(IAgentFactory.class), mock(IAgentStore.class),
                mock(IDeploymentStore.class));
    }

    private IConversationMemory memory() {
        var memory = mock(IConversationMemory.class, RETURNS_DEEP_STUBS);
        when(memory.getAgentId()).thenReturn("agent-1");
        when(memory.getUserId()).thenReturn("user-1");
        return memory;
    }

    @Test
    void source_isDynamic() {
        assertEquals("dynamic", provider().source());
    }

    @Test
    void addDynamicAgentTools_nullWhitelist_addsNothing() {
        var tools = new ArrayList<>();
        provider().addDynamicAgentTools(tools, null, memory());
        assertTrue(tools.isEmpty());
    }

    @Test
    void addDynamicAgentTools_emptyWhitelist_addsNothing() {
        var tools = new ArrayList<>();
        provider().addDynamicAgentTools(tools, List.of(), memory());
        assertTrue(tools.isEmpty());
    }

    @Test
    void addDynamicAgentTools_whitelistWithoutDynamicKeys_addsNothing() {
        var tools = new ArrayList<>();
        provider().addDynamicAgentTools(tools, List.of("calculator", "websearch"), memory());
        assertTrue(tools.isEmpty());
    }

    @Test
    void addDynamicAgentTools_createSubAgentWhitelisted_addsThatToolOnly() {
        var tools = new ArrayList<>();
        provider().addDynamicAgentTools(tools, List.of("create_sub_agent"), memory());

        assertEquals(1, tools.size());
        assertInstanceOf(CreateSubAgentTool.class, tools.get(0));
    }

    @Test
    void addDynamicAgentTools_converseWhitelisted_addsThatToolOnly() {
        var tools = new ArrayList<>();
        provider().addDynamicAgentTools(tools, List.of("converse_with_agent"), memory());

        assertEquals(1, tools.size());
        assertInstanceOf(ConverseWithAgentTool.class, tools.get(0));
    }

    @Test
    void addDynamicAgentTools_teardownWhitelisted_addsThatToolOnly() {
        var tools = new ArrayList<>();
        provider().addDynamicAgentTools(tools, List.of("teardown_agent"), memory());

        assertEquals(1, tools.size());
        assertInstanceOf(TeardownAgentTool.class, tools.get(0));
    }

    @Test
    void addDynamicAgentTools_findByCapability_addedWhenRecruitmentAllowedByDefaultConfig() {
        // No group-injected config → createDefaultDynamicConfig(), which is
        // permissive (enabled + allowRecruitment), so the tool IS added.
        var tools = new ArrayList<>();
        provider().addDynamicAgentTools(tools, List.of("find_agents_by_capability"), memory());

        assertEquals(1, tools.size());
        assertInstanceOf(FindAgentsByCapabilityTool.class, tools.get(0));
    }

    @Test
    void addDynamicAgentTools_allFourWhitelisted_addsAllFour() {
        var tools = new ArrayList<>();
        provider().addDynamicAgentTools(tools,
                List.of("create_sub_agent", "converse_with_agent", "find_agents_by_capability", "teardown_agent"),
                memory());

        assertEquals(4, tools.size());
    }

    @Test
    void createDefaultDynamicConfig_isPermissive() {
        var config = DynamicAgentToolsProvider.createDefaultDynamicConfig();

        assertTrue(config.isEnabled());
        assertTrue(config.isAllowCreation());
        assertTrue(config.isAllowRecruitment());
        assertTrue(config.isAllowDelegation());
    }

    @Test
    void constructedWithMockedCollaborators_doesNotThrow() {
        assertDoesNotThrow(this::provider);
    }
}
