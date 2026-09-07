/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    private IAgentStore agentStore;
    private IWorkflowStore workflowStore;
    private IResourceClientLibrary resourceClientLibrary;
    private IConversationMemory memory;
    private String agentId;

    @BeforeEach
    void setUp() throws Exception {
        WorkflowTraversal.clearCache();
        agentStore = mock(IAgentStore.class);
        workflowStore = mock(IWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
        memory = mock(IConversationMemory.class);

        // Unique per test so tests cannot share cache entries with each other
        agentId = UUID.randomUUID().toString().replace("-", "");
        when(memory.getAgentId()).thenReturn(agentId);
        when(memory.getAgentVersion()).thenReturn(1);

        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaabbbbccccddddeeeeffff?version=1")));
        when(agentStore.read(anyString(), anyInt())).thenReturn(agentConfig);

        var workflowConfig = new WorkflowConfiguration();
        workflowConfig.setWorkflowSteps(List.of());
        when(workflowStore.read(anyString(), anyInt())).thenReturn(workflowConfig);
    }

    /**
     * Every other malformed-URI branch here warns, marks the traversal degraded and
     * moves on. The version parse did not: {@code String.replaceAll} returns the
     * input UNCHANGED when the pattern does not match, so a URI carrying
     * {@code ?version=abc} passed the {@code contains("version=")} guard and
     * reached {@code parseInt} as the literal {@code "version=abc"}. The
     * NumberFormatException escaped discovery entirely, so one malformed workflow
     * URI aborted tool discovery for the whole turn instead of skipping that one
     * workflow.
     */
    @Test
    @DisplayName("a non-numeric version degrades that workflow instead of aborting the turn")
    void nonNumericVersionDoesNotAbortDiscovery() throws Exception {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaabbbbccccddddeeeeffff?version=abc")));
        when(agentStore.read(anyString(), anyInt())).thenReturn(agentConfig);

        var configs = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);

        assertNotNull(configs, "discovery must return a result, not blow up the turn");
        assertTrue(configs.isEmpty(), "the unusable workflow contributes nothing");
        verify(workflowStore, never()).read(anyString(), anyInt());
    }

    /**
     * An unanchored {@code version=} also matches inside {@code subversion=123}, so
     * a query carrying no version at all would have parsed as version 123 and gone
     * on to read a workflow that was never asked for. Caught independently by both
     * CodeRabbit and Copilot on the first review of this fix.
     */
    @Test
    @DisplayName("a lookalike query param is not mistaken for the version")
    void lookalikeParamIsNotReadAsVersion() throws Exception {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaabbbbccccddddeeeeffff?subversion=123")));
        when(agentStore.read(anyString(), anyInt())).thenReturn(agentConfig);

        var configs = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);

        assertNotNull(configs);
        assertTrue(configs.isEmpty());
        verify(workflowStore, never()).read(anyString(), anyInt());
    }

    @Test
    @DisplayName("the version is still read when it is not the first query param")
    void versionAfterAnotherParamIsStillRead() throws Exception {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaabbbbccccddddeeeeffff?foo=x&version=1")));
        when(agentStore.read(anyString(), anyInt())).thenReturn(agentConfig);

        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);

        // Anchoring must not break the ordinary multi-param case.
        verify(workflowStore).read(anyString(), eq(1));
    }

    @Test
    @DisplayName("a version too large for an int degrades the same way")
    void overflowingVersionDoesNotAbortDiscovery() throws Exception {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(
                List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaabbbbccccddddeeeeffff?version=99999999999999")));
        when(agentStore.read(anyString(), anyInt())).thenReturn(agentConfig);

        var configs = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);

        assertNotNull(configs);
        assertTrue(configs.isEmpty());
        verify(workflowStore, never()).read(anyString(), anyInt());
    }

    @Test
    @DisplayName("four discoveries of the same step type within a turn read the agent once")
    void repeatedDiscoveryHitsTheStoreOnce() throws Exception {
        for (int i = 0; i < 4; i++) {
            WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                    agentStore, workflowStore, resourceClientLibrary);
        }

        verify(agentStore, times(1)).read(anyString(), anyInt());
        verify(workflowStore, times(1)).read(anyString(), anyInt());
    }

    @Test
    @DisplayName("different step types are cached independently, not conflated")
    void differentStepTypesAreSeparateEntries() throws Exception {
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.mcpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);

        verify(agentStore, times(2)).read(anyString(), anyInt());
    }

    @Test
    @DisplayName("clearing the cache forces a fresh traversal")
    void clearCacheForcesReload() throws Exception {
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);
        WorkflowTraversal.clearCache();
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);

        verify(agentStore, times(2)).read(anyString(), anyInt());
    }

    @Test
    @DisplayName("a different agent version is a separate cache entry")
    void differentAgentVersionIsSeparate() throws Exception {
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);
        when(memory.getAgentVersion()).thenReturn(2);
        WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls", Object.class,
                agentStore, workflowStore, resourceClientLibrary);

        verify(agentStore, times(2)).read(anyString(), anyInt());
    }

    @Test
    @DisplayName("the TTL is short enough that a redeploy between turns is picked up")
    void ttlIsShort() {
        assertEquals(2_000L, WorkflowTraversal.CACHE_TTL_MILLIS,
                "a long TTL would trade the F12 fix for stale agent configuration");
    }
}
