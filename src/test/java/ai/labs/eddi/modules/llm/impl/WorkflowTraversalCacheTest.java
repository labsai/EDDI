/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding F12 — a single conversation turn traversed the same agent and
 * workflows three to four times (httpcall tools, mcpcall tools, vector RAG,
 * httpCall RAG), each re-reading the agent config from the store.
 */
@DisplayName("WorkflowTraversal — one traversal per turn (F12)")
class WorkflowTraversalCacheTest {

    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private IResourceClientLibrary resourceClientLibrary;
    private IConversationMemory memory;
    private String agentId;

    @BeforeEach
    void setUp() throws Exception {
        WorkflowTraversal.clearCache();
        agentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        memory = mock(IConversationMemory.class);

        // Unique per test so tests cannot share cache entries with each other
        agentId = UUID.randomUUID().toString().replace("-", "");
        when(memory.getAgentId()).thenReturn(agentId);
        when(memory.getAgentVersion()).thenReturn(1);

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaabbbbccccddddeeeeffff?version=1")));
        when(agentStore.readAgent(anyString(), anyInt())).thenReturn(agentConfig);

        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(List.of());
        when(workflowStore.readWorkflow(anyString(), anyInt())).thenReturn(workflowConfig);
    }

    @Test
    @DisplayName("four discoveries of the same step type within a turn read the agent once")
    void repeatedDiscoveryHitsTheStoreOnce() throws Exception {
        for (int i = 0; i < 4; i++) {
            WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                    agentStore, workflowStore, resourceClientLibrary);
        }

        verify(agentStore, times(1)).readAgent(anyString(), anyInt());
        verify(workflowStore, times(1)).readWorkflow(anyString(), anyInt());
    }

    @Test
    @DisplayName("different step types are cached independently, not conflated")
    void differentStepTypesAreSeparateEntries() throws Exception {
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.mcpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);

        verify(agentStore, times(2)).readAgent(anyString(), anyInt());
    }

    @Test
    @DisplayName("clearing the cache forces a fresh traversal")
    void clearCacheForcesReload() throws Exception {
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);
        WorkflowTraversal.clearCache();
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);

        verify(agentStore, times(2)).readAgent(anyString(), anyInt());
    }

    @Test
    @DisplayName("a different agent version is a separate cache entry")
    void differentAgentVersionIsSeparate() throws Exception {
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);
        when(memory.getAgentVersion()).thenReturn(2);
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);

        verify(agentStore, times(2)).readAgent(anyString(), anyInt());
    }

    @Test
    @DisplayName("the TTL is short enough that a redeploy between turns is picked up")
    void ttlIsShort() {
        assertEquals(2_000L, WorkflowTraversal.CACHE_TTL_MILLIS,
                "a long TTL would trade the F12 fix for stale agent configuration");
    }
}
