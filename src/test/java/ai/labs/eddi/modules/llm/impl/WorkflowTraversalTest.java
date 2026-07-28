/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link WorkflowTraversal}.
 * <p>
 * {@code WorkflowTraversal} memoizes traversals in a process-wide static cache
 * (finding F12), so every test here starts and ends with an empty cache: each
 * one drives a <em>different</em> agent configuration through the <em>same</em>
 * (agentId, version, step type) coordinates, which is exactly the shape a
 * leaked entry serves a stale answer for.
 */
class WorkflowTraversalTest {

    private IConversationMemory memory;
    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private IResourceClientLibrary resourceClientLibrary;

    @BeforeEach
    void setUp() {
        WorkflowTraversal.clearCache();
        memory = mock(IConversationMemory.class);
        agentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
    }

    @AfterEach
    void tearDown() {
        // Do not leak entries into test classes that share this JVM fork.
        WorkflowTraversal.clearCache();
    }

    @Nested
    @DisplayName("discoverConfigs")
    class DiscoverConfigs {

        @Test
        @DisplayName("should return empty when agentId is null")
        void emptyWhenNoAgentId() {
            when(memory.getAgentId()).thenReturn(null);
            when(memory.getAgentVersion()).thenReturn(1);

            var result = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls",
                    String.class, agentStore, workflowStore, resourceClientLibrary);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty when agentVersion is null")
        void emptyWhenNoAgentVersion() {
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getAgentVersion()).thenReturn(null);

            var result = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls",
                    String.class, agentStore, workflowStore, resourceClientLibrary);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty when agent config fails to load")
        void emptyWhenAgentConfigFails() throws Exception {
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getAgentVersion()).thenReturn(1);
            when(agentStore.readAgent("agent-1", 1)).thenThrow(new RuntimeException("DB error"));

            var result = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls",
                    String.class, agentStore, workflowStore, resourceClientLibrary);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should return empty when agent has no workflows")
        void emptyWhenNoWorkflows() throws Exception {
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getAgentVersion()).thenReturn(1);
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of());
            when(agentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);

            var result = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls",
                    String.class, agentStore, workflowStore, resourceClientLibrary);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should skip workflow URIs with no path")
        void skipsWorkflowsWithNoPath() throws Exception {
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getAgentVersion()).thenReturn(1);
            var agentConfig = new AgentConfiguration();
            // URI with no path — opaque URI
            agentConfig.setWorkflows(List.of(URI.create("mailto:test")));
            when(agentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);

            var result = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls",
                    String.class, agentStore, workflowStore, resourceClientLibrary);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should skip workflow URIs with no version query")
        void skipsWorkflowsWithNoVersion() throws Exception {
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getAgentVersion()).thenReturn(1);
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1")));
            when(agentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);

            var result = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls",
                    String.class, agentStore, workflowStore, resourceClientLibrary);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should discover matching step configs")
        void discoversMatchingSteps() throws Exception {
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getAgentVersion()).thenReturn(1);
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));
            when(agentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);

            var wfConfig = new WorkflowConfiguration();
            var step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.httpcalls"));
            step.setConfig(Map.of("uri", "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/hc-1?version=1"));
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.readWorkflow("wf-1", 1)).thenReturn(wfConfig);
            when(resourceClientLibrary.getResource(any(URI.class), eq(String.class)))
                    .thenReturn("mockConfig");

            var result = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls",
                    String.class, agentStore, workflowStore, resourceClientLibrary);

            assertEquals(1, result.size());
            assertEquals("mockConfig", result.get(0).config());
        }

        @Test
        @DisplayName("should skip steps with null URI in config")
        void skipsStepsWithNullUri() throws Exception {
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getAgentVersion()).thenReturn(1);
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));
            when(agentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);

            var wfConfig = new WorkflowConfiguration();
            var step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.httpcalls"));
            step.setConfig(Map.of()); // no "uri" key
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.readWorkflow("wf-1", 1)).thenReturn(wfConfig);

            var result = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls",
                    String.class, agentStore, workflowStore, resourceClientLibrary);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should skip non-matching step types")
        void skipsNonMatchingSteps() throws Exception {
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getAgentVersion()).thenReturn(1);
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));
            when(agentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);

            var wfConfig = new WorkflowConfiguration();
            var step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.rules"));
            step.setConfig(Map.of("uri", "something"));
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.readWorkflow("wf-1", 1)).thenReturn(wfConfig);

            var result = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls",
                    String.class, agentStore, workflowStore, resourceClientLibrary);

            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("should handle ServiceException when loading resource gracefully")
        void handlesServiceExceptionGracefully() throws Exception {
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getAgentVersion()).thenReturn(1);
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));
            when(agentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);

            var wfConfig = new WorkflowConfiguration();
            var step = new WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.httpcalls"));
            step.setConfig(Map.of("uri", "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/hc-1?version=1"));
            wfConfig.setWorkflowSteps(List.of(step));
            when(workflowStore.readWorkflow("wf-1", 1)).thenReturn(wfConfig);
            when(resourceClientLibrary.getResource(any(URI.class), eq(String.class)))
                    .thenThrow(new ServiceException("Load failed"));

            var result = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls",
                    String.class, agentStore, workflowStore, resourceClientLibrary);

            assertTrue(result.isEmpty()); // gracefully skipped
        }

        @Test
        @DisplayName("should handle workflow load failure gracefully")
        void handlesWorkflowLoadFailure() throws Exception {
            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getAgentVersion()).thenReturn(1);
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf-1?version=1")));
            when(agentStore.readAgent("agent-1", 1)).thenReturn(agentConfig);
            when(workflowStore.readWorkflow("wf-1", 1)).thenThrow(new RuntimeException("Workflow not found"));

            var result = WorkflowTraversal.discoverConfigs(memory, "eddi://ai.labs.httpcalls",
                    String.class, agentStore, workflowStore, resourceClientLibrary);

            assertTrue(result.isEmpty()); // gracefully handled
        }
    }

    @Nested
    @DisplayName("traversal cache (F12)")
    class TraversalCache {

        private static final String HTTPCALLS = "eddi://ai.labs.httpcalls";
        private static final String MCPCALLS = "eddi://ai.labs.mcpcalls";
        private static final String HC_URI = "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/hc-1?version=1";
        private static final String MCP_URI = "eddi://ai.labs.mcpcalls/mcpcallsstore/mcpcalls/mc-1?version=1";

        @Test
        @DisplayName("hit: a repeat lookup inside the TTL reuses the traversal and re-reads nothing")
        void repeatLookupIsServedFromCache() throws Exception {
            wireAgent("agent-1", 1, step(HTTPCALLS, HC_URI));
            when(resourceClientLibrary.getResource(any(URI.class), eq(String.class))).thenReturn("hc");

            var first = discover(HTTPCALLS, String.class);
            var second = discover(HTTPCALLS, String.class);

            assertEquals(1, first.size());
            assertEquals(1, second.size(), "the cached traversal must carry the same configs, not an empty list");
            assertEquals("hc", second.get(0).config());
            verify(agentStore, times(1)).readAgent("agent-1", 1);
            verify(workflowStore, times(1)).readWorkflow(workflowIdOf("agent-1"), 1);
        }

        @Test
        @DisplayName("miss: once the TTL has elapsed the agent is traversed again")
        void expiredEntryIsReTraversed() throws Exception {
            wireAgent("agent-1", 1, step(HTTPCALLS, HC_URI));
            when(resourceClientLibrary.getResource(any(URI.class), eq(String.class))).thenReturn("hc");

            long t0 = 1_000_000L;
            WorkflowTraversal.discoverConfigs(memory, HTTPCALLS, String.class, agentStore, workflowStore, resourceClientLibrary, t0);
            WorkflowTraversal.discoverConfigs(memory, HTTPCALLS, String.class, agentStore, workflowStore, resourceClientLibrary,
                    t0 + WorkflowTraversal.CACHE_TTL_MILLIS - 1);
            verify(agentStore, times(1)).readAgent("agent-1", 1);

            var afterExpiry = WorkflowTraversal.discoverConfigs(memory, HTTPCALLS, String.class, agentStore, workflowStore,
                    resourceClientLibrary, t0 + WorkflowTraversal.CACHE_TTL_MILLIS);

            verify(agentStore, times(2)).readAgent("agent-1", 1);
            assertEquals(1, afterExpiry.size());
        }

        @Test
        @DisplayName("no collision: two step types in one workflow keep their own configs")
        void differentStepTypesDoNotCollide() throws Exception {
            wireAgent("agent-1", 1, step(HTTPCALLS, HC_URI), step(MCPCALLS, MCP_URI));
            when(resourceClientLibrary.getResource(any(URI.class), eq(String.class)))
                    .thenAnswer(inv -> inv.getArgument(0, URI.class).getPath());

            var httpcalls = discover(HTTPCALLS, String.class);
            var mcpcalls = discover(MCPCALLS, String.class);

            assertEquals(1, httpcalls.size());
            assertEquals(1, mcpcalls.size());
            assertTrue(httpcalls.get(0).config().contains("httpcalls"));
            assertTrue(mcpcalls.get(0).config().contains("mcpcalls"),
                    "the mcpcalls lookup must not be served the cached httpcalls traversal");
        }

        @Test
        @DisplayName("no collision: the same step type asked for a different config type is a separate entry")
        void differentConfigClassesDoNotCollide() throws Exception {
            wireAgent("agent-1", 1, step(HTTPCALLS, HC_URI));
            when(resourceClientLibrary.getResource(any(URI.class), eq(String.class))).thenReturn("as-string");
            when(resourceClientLibrary.getResource(any(URI.class), eq(Integer.class))).thenReturn(42);

            var asString = discover(HTTPCALLS, String.class);
            var asInteger = discover(HTTPCALLS, Integer.class);

            assertEquals("as-string", asString.get(0).config());
            assertEquals(42, asInteger.get(0).config(),
                    "a cache key without the config type hands the caller the wrong element type");
        }

        @Test
        @DisplayName("no collision: another agent, and another version of the same agent, traverse independently")
        void agentIdentityIsPartOfTheKey() throws Exception {
            wireAgent("agent-1", 1, step(HTTPCALLS, HC_URI));
            wireAgent("agent-2", 1);
            when(resourceClientLibrary.getResource(any(URI.class), eq(String.class))).thenReturn("hc");

            when(memory.getAgentId()).thenReturn("agent-1");
            assertEquals(1, discover(HTTPCALLS, String.class).size());

            when(memory.getAgentId()).thenReturn("agent-2");
            assertTrue(discover(HTTPCALLS, String.class).isEmpty(), "agent-2 has no steps — it must not inherit agent-1's");

            when(memory.getAgentId()).thenReturn("agent-1");
            when(memory.getAgentVersion()).thenReturn(2);
            discover(HTTPCALLS, String.class);
            verify(agentStore).readAgent("agent-1", 2);
        }

        @Test
        @DisplayName("a workflow that failed to load is NOT cached — the next lookup retries and recovers")
        void workflowLoadFailureIsNotCached() throws Exception {
            wireAgent("agent-1", 1, step(HTTPCALLS, HC_URI));
            when(resourceClientLibrary.getResource(any(URI.class), eq(String.class))).thenReturn("hc");
            when(workflowStore.readWorkflow(workflowIdOf("agent-1"), 1))
                    .thenThrow(new RuntimeException("transient store blip"))
                    .thenReturn(workflow(step(HTTPCALLS, HC_URI)));

            assertTrue(discover(HTTPCALLS, String.class).isEmpty());
            var retry = discover(HTTPCALLS, String.class);

            assertEquals(1, retry.size(),
                    "caching a failed traversal strips the agent of its configs for the whole TTL window");
        }

        @Test
        @DisplayName("a step config that failed to load is NOT cached — the next lookup retries and recovers")
        void stepConfigLoadFailureIsNotCached() throws Exception {
            wireAgent("agent-1", 1, step(HTTPCALLS, HC_URI));
            when(resourceClientLibrary.getResource(any(URI.class), eq(String.class)))
                    .thenThrow(new ServiceException("temporarily unavailable"))
                    .thenReturn("hc");

            assertTrue(discover(HTTPCALLS, String.class).isEmpty());

            assertEquals(1, discover(HTTPCALLS, String.class).size());
        }

        @Test
        @DisplayName("a complete traversal that legitimately found nothing IS cached")
        void completeEmptyTraversalIsCached() throws Exception {
            wireAgent("agent-1", 1, step("eddi://ai.labs.rules", "eddi://ai.labs.rules/rulestore/rulesets/r-1?version=1"));

            assertTrue(discover(HTTPCALLS, String.class).isEmpty());
            assertTrue(discover(HTTPCALLS, String.class).isEmpty());

            verify(agentStore, times(1)).readAgent("agent-1", 1);
        }

        @Test
        @DisplayName("callers get a copy — mutating the returned list cannot corrupt the cache")
        void returnedListIsADefensiveCopy() throws Exception {
            wireAgent("agent-1", 1, step(HTTPCALLS, HC_URI));
            when(resourceClientLibrary.getResource(any(URI.class), eq(String.class))).thenReturn("hc");

            discover(HTTPCALLS, String.class).clear();

            assertEquals(1, discover(HTTPCALLS, String.class).size());
        }

        // ---- helpers ----

        private <T> List<WorkflowTraversal.StepConfig<T>> discover(String stepTypeUri, Class<T> configClass) {
            return WorkflowTraversal.discoverConfigs(memory, stepTypeUri, configClass, agentStore, workflowStore, resourceClientLibrary);
        }

        private WorkflowStep step(String type, String configUri) {
            var step = new WorkflowStep();
            step.setType(URI.create(type));
            step.setConfig(Map.of("uri", configUri));
            return step;
        }

        private WorkflowConfiguration workflow(WorkflowStep... steps) {
            var wfConfig = new WorkflowConfiguration();
            wfConfig.setWorkflowSteps(List.of(steps));
            return wfConfig;
        }

        /**
         * Each agent gets its own workflow id so wiring two agents cannot cross-stub.
         */
        private String workflowIdOf(String agentId) {
            return "wf-" + agentId;
        }

        private void wireAgent(String agentId, int version, WorkflowStep... steps) throws Exception {
            lenient().when(memory.getAgentId()).thenReturn(agentId);
            lenient().when(memory.getAgentVersion()).thenReturn(version);

            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(
                    List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + workflowIdOf(agentId) + "?version=1")));
            lenient().when(agentStore.readAgent(agentId, version)).thenReturn(agentConfig);
            lenient().when(workflowStore.readWorkflow(workflowIdOf(agentId), 1)).thenReturn(workflow(steps));
        }
    }

    @Nested
    @DisplayName("StepConfig record")
    class StepConfigRecord {

        @Test
        @DisplayName("should hold config and stepConfig values")
        void holdsValues() {
            var stepConfig = new WorkflowTraversal.StepConfig<>("test", Map.of("key", "value"));

            assertEquals("test", stepConfig.config());
            assertEquals(Map.of("key", "value"), stepConfig.stepConfig());
        }
    }
}
