/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
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
 * (the check moved down from {@code buildToolSetup}).
 * <p>
 * This comment used to claim discovery was "already covered indirectly by
 * {@code AgentOrchestratorExtendedTest}". It was not: those suites pass a
 * mocked memory whose {@code getAgentVersion()} is null, so
 * {@code WorkflowTraversal} returns before the per-server loop, and the
 * {@code McpToolProviderManager*Test} suites cover the manager rather than this
 * class. The measured result was 31% instruction coverage on
 * {@link McpToolsProvider}. Discovery — filtering, collisions, the resource
 * bridge and failure mapping — is now covered directly by
 * {@code McpToolsProviderDiscoveryTest}.
 *
 * @author tests
 */
class McpToolsProviderTest {

    private McpToolsProvider provider(McpToolProviderManager manager) {
        return new McpToolsProvider(mock(IAgentStore.class), mock(IWorkflowStore.class),
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
        var restAgentStore = mock(IAgentStore.class);
        var provider = new McpToolsProvider(restAgentStore, mock(IWorkflowStore.class),
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
        when(memory.getAgentVersion()).thenReturn(7);
        var task = new LlmConfiguration.Task();
        task.setEnableMcpCallTools(null);
        var ctx = new ToolAssemblyContext(memory, task, null, null, "user-1", "agent-1", null);

        var restAgentStore = mock(IRestAgentStore.class);
        var contribution = new McpToolsProvider(restAgentStore, mock(IRestWorkflowStore.class),
                mock(IResourceClientLibrary.class), mock(McpToolProviderManager.class)).contribute(ctx);

        // The point of the test is that a null flag does NOT short-circuit: discovery
        // has to be attempted. assertNotNull alone passed either way, because the
        // disabled path also returns a non-null empty contribution.
        assertNotNull(contribution);
        verify(restAgentStore).readAgent("agent-1", 7);
    }

    @Test
    void constructedWithMockedCollaborators_doesNotThrow() {
        assertDoesNotThrow(() -> provider(mock(McpToolProviderManager.class)));
    }
}
