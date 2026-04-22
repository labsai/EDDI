package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration.WorkflowStep;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
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
 */
class WorkflowTraversalTest {

    private IConversationMemory memory;
    private IRestAgentStore agentStore;
    private IRestWorkflowStore workflowStore;
    private IResourceClientLibrary resourceClientLibrary;

    @BeforeEach
    void setUp() {
        memory = mock(IConversationMemory.class);
        agentStore = mock(IRestAgentStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        resourceClientLibrary = mock(IResourceClientLibrary.class);
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
