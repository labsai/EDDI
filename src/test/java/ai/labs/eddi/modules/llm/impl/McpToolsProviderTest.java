/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link McpToolsProvider}, extracted from {@code
 * AgentOrchestrator} during the R2 (step 2) refactor. Covers {@code
 * contribute}'s enable/disable gate — new surface introduced by the extraction
 * (the check moved down from {@code buildToolSetup}). Discovery itself is
 * already covered indirectly by {@code AgentOrchestratorExtendedTest} and
 * directly by the {@code McpToolProviderManager*Test} suites (unchanged by this
 * move), re-verified green through the new delegator.
 *
 * @author tests
 */
class McpToolsProviderTest {

    private McpToolsProvider provider(McpToolProviderManager manager) {
        return new McpToolsProvider(mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                mock(IResourceClientLibrary.class), manager);
    }

    private ToolAssemblyContext context(Boolean enableMcpCallTools) {
        var task = new LlmConfiguration.Task();
        task.setEnableMcpCallTools(enableMcpCallTools);
        return new ToolAssemblyContext(mock(IConversationMemory.class), task, null, null, "user-1", "agent-1", null);
    }

    @Test
    void source_isMcp() {
        assertEquals("mcp", provider(mock(McpToolProviderManager.class)).source());
    }

    @Test
    void contribute_explicitlyDisabled_returnsEmptyWithoutDiscovering() {
        var restAgentStore = mock(IRestAgentStore.class);
        var provider = new McpToolsProvider(restAgentStore, mock(IRestWorkflowStore.class),
                mock(IResourceClientLibrary.class), mock(McpToolProviderManager.class));

        var contribution = provider.contribute(context(false));

        assertTrue(contribution.specs().isEmpty());
        assertTrue(contribution.executors().isEmpty());
        verifyNoInteractions(restAgentStore);
    }

    @Test
    void contribute_nullFlag_defaultsToEnabled() {
        var memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn("agent-1");
        var task = new LlmConfiguration.Task();
        task.setEnableMcpCallTools(null);
        var ctx = new ToolAssemblyContext(memory, task, null, null, "user-1", "agent-1", null);

        var contribution = provider(mock(McpToolProviderManager.class)).contribute(ctx);

        assertNotNull(contribution);
    }

    @Test
    void constructedWithMockedCollaborators_doesNotThrow() {
        assertDoesNotThrow(() -> provider(mock(McpToolProviderManager.class)));
    }
}
