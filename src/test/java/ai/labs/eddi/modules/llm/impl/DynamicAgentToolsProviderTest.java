/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
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
        return providerWith(new LiveDiscussionRegistry());
    }

    private DynamicAgentToolsProvider providerWith(LiveDiscussionRegistry registry) {
        return new DynamicAgentToolsProvider(mock(AgentSetupService.class), mock(CapabilityRegistryService.class),
                mock(IConversationService.class), mock(IAgentFactory.class), mock(IAgentStore.class),
                mock(IDeploymentStore.class), registry, mock(IAgentGroupStore.class));
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

    // =================================================================
    // contribute() — the enableBuiltInTools gate (branch review)
    // =================================================================

    private ToolAssemblyContext assemblyCtx(Boolean enableBuiltInTools, List<String> whitelist) {
        var task = new LlmConfiguration.Task();
        task.setEnableBuiltInTools(enableBuiltInTools);
        var memory = mock(IConversationMemory.class);
        lenient().when(memory.getCurrentStep()).thenReturn(mock(IWritableConversationStep.class));
        lenient().when(memory.getConversationId()).thenReturn("conv-1");
        return new ToolAssemblyContext(memory, task, whitelist, null, "user-1", "agent-1", null);
    }

    @Test
    void contribute_builtInToolsDisabled_contributesNothing() {
        // This class's own Javadoc calls it "the highest-blast-radius gate in this
        // class", and nothing called contribute() at all — every test drove
        // addDynamicAgentTools directly. Deleting the gate would hand an agent
        // configured enableBuiltInTools:false, but carrying a stale whitelist that
        // still names create_sub_agent / teardown_agent, tools that deploy and
        // delete real agents in production.
        var whitelist = List.of("create_sub_agent", "converse_with_agent", "teardown_agent");

        assertTrue(provider().contribute(assemblyCtx(false, whitelist)).specs().isEmpty());
        assertTrue(provider().contribute(assemblyCtx(null, whitelist)).specs().isEmpty(),
                "unset means off — that is the documented default");
    }

    @Test
    void contribute_builtInToolsEnabled_contributesTheWhitelistedTools() {
        // The positive case, so the gate test above cannot pass by the provider
        // simply never contributing anything.
        var contribution = provider().contribute(assemblyCtx(true, List.of("converse_with_agent")));

        assertFalse(contribution.specs().isEmpty(), "an enabled agent must still get its whitelisted tools");
        assertTrue(contribution.specs().stream().anyMatch(spec -> spec.name().equals("converseWithAgent")));
    }

    @Test
    void contribute_emptyWhitelist_contributesNothing() {
        assertTrue(provider().contribute(assemblyCtx(true, List.of())).specs().isEmpty());
    }
}
