/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.A2AAgentConfig;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import dev.langchain4j.agent.tool.ToolSpecification;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link A2AToolsProvider}, extracted from the inline
 * A2A-discovery block in {@code AgentOrchestrator#buildToolSetup} during the R2
 * (step 2) refactor. Covers {@code contribute} directly; the provider's
 * assembly through {@code ToolSourceRegistry} in {@code buildToolSetup} is
 * covered by {@code AgentOrchestratorLocalToolAssemblyTest}, and
 * {@code A2AToolProviderManager}'s own discovery logic is untouched by this
 * move and remains covered by its existing test suites.
 *
 * @author tests
 */
class A2AToolsProviderTest {

    private ToolAssemblyContext context(List<A2AAgentConfig> a2aAgents) {
        var task = new LlmConfiguration.Task();
        task.setA2aAgents(a2aAgents);
        return new ToolAssemblyContext(mock(IConversationMemory.class), task, null, null, "user-1", "agent-1", null);
    }

    @Test
    void source_isA2a() {
        assertEquals("a2a", new A2AToolsProvider(mock(A2AToolProviderManager.class)).source());
    }

    @Test
    void contribute_noConfiguredAgents_returnsEmptyWithoutCallingManager() {
        var manager = mock(A2AToolProviderManager.class);
        var provider = new A2AToolsProvider(manager);

        var contribution = provider.contribute(context(null));

        assertTrue(contribution.specs().isEmpty());
        verifyNoInteractions(manager);
    }

    @Test
    void contribute_emptyAgentList_returnsEmptyWithoutCallingManager() {
        var manager = mock(A2AToolProviderManager.class);
        var provider = new A2AToolsProvider(manager);

        var contribution = provider.contribute(context(List.of()));

        assertTrue(contribution.specs().isEmpty());
        verifyNoInteractions(manager);
    }

    @Test
    void contribute_configuredAgents_delegatesToManagerAndWrapsResult() {
        var manager = mock(A2AToolProviderManager.class);
        var agentConfig = new A2AAgentConfig();
        var spec = ToolSpecification.builder().name("peer_tool").build();
        var result = new A2AToolProviderManager.A2AToolsResult(List.of(spec), Map.of());
        when(manager.discoverTools(List.of(agentConfig))).thenReturn(result);
        var provider = new A2AToolsProvider(manager);

        var contribution = provider.contribute(context(List.of(agentConfig)));

        assertEquals(List.of(spec), contribution.specs());
        verify(manager).discoverTools(List.of(agentConfig));
    }
}
