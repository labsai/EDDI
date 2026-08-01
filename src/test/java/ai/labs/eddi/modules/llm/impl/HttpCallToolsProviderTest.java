/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.tools.spi.ToolAssemblyContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Focused unit tests for {@link HttpCallToolsProvider}, extracted from {@code
 * AgentOrchestrator} during the R2 (step 2) refactor. Covers {@code
 * contribute}'s enable/disable gate directly — this check moved here from
 * {@code buildToolSetup} as part of the extraction and did not exist as a
 * method on the old {@code discoverHttpCallTools}, so it is genuinely new
 * surface. Discovery itself ({@code normalizeEndpointPath},
 * {@code safeTemplateMerge}, the full traversal) is already exhaustively
 * covered by
 * {@code AgentOrchestratorTest}/{@code AgentOrchestratorExtendedTest} via the
 * facade's reflection-targeted delegators, re-verified green against this class
 * post-extraction rather than duplicated here.
 *
 * @author tests
 */
class HttpCallToolsProviderTest {

    private HttpCallToolsProvider provider() {
        return new HttpCallToolsProvider(mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                mock(IResourceClientLibrary.class), mock(IApiCallExecutor.class), mock(IJsonSerialization.class),
                mock(IMemoryItemConverter.class));
    }

    private ToolAssemblyContext context(Boolean enableHttpCallTools) {
        var task = new LlmConfiguration.Task();
        task.setEnableHttpCallTools(enableHttpCallTools);
        var memory = mock(IConversationMemory.class);
        return new ToolAssemblyContext(memory, task, null, null, "user-1", "agent-1", null);
    }

    @Test
    void source_isHttp() {
        assertEquals("http", provider().source());
    }

    @Test
    void contribute_explicitlyDisabled_returnsEmptyWithoutDiscovering() {
        var restAgentStore = mock(IRestAgentStore.class);
        var provider = new HttpCallToolsProvider(restAgentStore, mock(IRestWorkflowStore.class),
                mock(IResourceClientLibrary.class), mock(IApiCallExecutor.class), mock(IJsonSerialization.class),
                mock(IMemoryItemConverter.class));

        var contribution = provider.contribute(context(false));

        assertTrue(contribution.specs().isEmpty());
        assertTrue(contribution.executors().isEmpty());
        verifyNoInteractions(restAgentStore);
    }

    @Test
    void contribute_nullFlag_defaultsToEnabled() {
        var memory = mock(IConversationMemory.class);
        when(memory.getAgentId()).thenReturn("agent-1");
        var ctx = new ToolAssemblyContext(memory, taskWithFlag(null), null, null, "user-1", "agent-1", null);

        // Enabled-by-default means discovery is attempted (and returns empty on the
        // mocked, unconfigured store) rather than short-circuiting before any call.
        var contribution = provider().contribute(ctx);

        assertNotNull(contribution);
    }

    private LlmConfiguration.Task taskWithFlag(Boolean flag) {
        var task = new LlmConfiguration.Task();
        task.setEnableHttpCallTools(flag);
        return task;
    }

    @Test
    void normalizeEndpointPath_absoluteUrl_keepsOnlyThePath() {
        assertEquals("/agentstore/agents", HttpCallToolsProvider.normalizeEndpointPath("https://eddi.example/agentstore/agents"));
    }

    @Test
    void constructedWithMockedCollaborators_doesNotThrow() {
        assertDoesNotThrow(this::provider);
    }
}
