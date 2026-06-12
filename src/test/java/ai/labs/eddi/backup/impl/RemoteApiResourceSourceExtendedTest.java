/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource.*;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Extended unit tests for {@link RemoteApiResourceSource} covering workflow
 * reading with extensions, listRemoteAgentDescriptors, extension type mappings,
 * httpGet path validation, InterruptedException handling, and workflow reading
 * error scenarios.
 */
class RemoteApiResourceSourceExtendedTest {

    private IJsonSerialization jsonSerialization;
    private HttpClient mockHttpClient;

    private static final String BASE_URL = "https://staging.example.com";
    private static final String AGENT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";

    @BeforeEach
    void setUp() {
        jsonSerialization = Mockito.mock(IJsonSerialization.class);
        mockHttpClient = Mockito.mock(HttpClient.class);
    }

    // ==================== Workflow with extensions ====================

    @Nested
    @DisplayName("Workflow with extensions")
    class WorkflowWithExtensions {

        @Test
        @DisplayName("should read workflow with LLM extension from remote")
        void readsWorkflowWithExtensions() throws Exception {
            // Setup agent with one workflow
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabb00112233445566778899?version=1"))));

            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=1", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[]");
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            // Workflow config with LLM step
            var wfConfig = new WorkflowConfiguration();
            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("ai.labs.llm"));
            step.setExtensions(new HashMap<>(Map.of(
                    "uri", "eddi://ai.labs.llm/llmstore/llms/ccdd00112233445566778899?version=2")));
            wfConfig.setWorkflowSteps(List.of(step));

            mockHttpResponse("/workflowstore/workflows/aabb00112233445566778899?version=1", "{wfJson}");
            when(jsonSerialization.deserialize("{wfJson}", WorkflowConfiguration.class))
                    .thenReturn(wfConfig);

            mockHttpResponse("/workflowstore/workflows/descriptors?index=0&limit=0", "[]");

            // LLM extension content
            mockHttpResponse("/llmstore/llms/ccdd00112233445566778899?version=2", "{llmJson}");
            mockHttpResponse("/llmstore/llms/descriptors?index=0&limit=0", "[]");

            var source = createSource(AGENT_ID, 1);
            List<WorkflowSourceData> workflows = source.readWorkflows();

            assertFalse(workflows.isEmpty());
            assertEquals(1, workflows.size());
            assertEquals("aabb00112233445566778899", workflows.getFirst().sourceId());
            assertEquals(0, workflows.getFirst().positionIndex());

            // Should have one extension (LLM)
            assertFalse(workflows.getFirst().extensions().isEmpty());
            assertTrue(workflows.getFirst().extensions().containsKey("ai.labs.llm"));
            assertEquals("langchain", workflows.getFirst().extensions().get("ai.labs.llm").type());
        }

        @Test
        @DisplayName("should cache workflow data on repeated calls")
        void cachesWorkflowData() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());

            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=1", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[]");
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            var source = createSource(AGENT_ID, 1);
            List<WorkflowSourceData> first = source.readWorkflows();
            List<WorkflowSourceData> second = source.readWorkflows();

            assertSame(first, second);
        }

        @Test
        @DisplayName("should handle unknown step type gracefully")
        void unknownStepType() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aabb00112233445566778899?version=1"))));

            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=1", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[]");
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            // Workflow with unknown step type
            var wfConfig = new WorkflowConfiguration();
            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("ai.labs.unknown"));
            step.setExtensions(new HashMap<>(Map.of(
                    "uri", "eddi://ai.labs.unknown/unknownstore/unknowns/eeff00112233445566778899?version=1")));
            wfConfig.setWorkflowSteps(List.of(step));

            mockHttpResponse("/workflowstore/workflows/aabb00112233445566778899?version=1", "{wfJson}");
            when(jsonSerialization.deserialize("{wfJson}", WorkflowConfiguration.class))
                    .thenReturn(wfConfig);

            mockHttpResponse("/workflowstore/workflows/descriptors?index=0&limit=0", "[]");

            var source = createSource(AGENT_ID, 1);
            List<WorkflowSourceData> workflows = source.readWorkflows();

            assertEquals(1, workflows.size());
            // Unknown step type → no extension included
            assertTrue(workflows.getFirst().extensions().isEmpty());
        }
    }

    // ==================== Workflow reading error handling ====================

    @Nested
    @DisplayName("Workflow reading error handling")
    class WorkflowErrorHandling {

        @Test
        @DisplayName("should skip workflow that fails to load")
        void skipsFailedWorkflow() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/ddee00112233445566778899?version=1"))));

            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=1", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[]");
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            // Workflow fetch fails
            mockHttpErrorResponse("/workflowstore/workflows/ddee00112233445566778899?version=1", 500);

            var source = createSource(AGENT_ID, 1);
            List<WorkflowSourceData> workflows = source.readWorkflows();

            assertTrue(workflows.isEmpty());
        }
    }

    // ==================== InterruptedException handling ====================

    @Nested
    @DisplayName("InterruptedException handling")
    class InterruptedExceptionHandling {

        @Test
        @DisplayName("should wrap InterruptedException in RuntimeException")
        @SuppressWarnings("unchecked")
        void wrapsInterruptedException() throws Exception {
            when(mockHttpClient.send(any(HttpRequest.class), any()))
                    .thenThrow(new InterruptedException("Thread interrupted"));

            var source = createSource(AGENT_ID, 1);
            var ex = assertThrows(RuntimeException.class, source::readAgent);
            assertTrue(ex.getMessage().contains("Thread interrupted"));
        }
    }

    // ==================== httpGet path validation ====================

    @Nested
    @DisplayName("httpGet path validation")
    class HttpGetPathValidation {

        @Test
        @DisplayName("should handle path correctly with leading slash")
        void correctPathHandling() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());

            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=1", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[]");
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            var source = createSource(AGENT_ID, 1);
            assertDoesNotThrow(source::readAgent);
        }
    }

    // ==================== Agent name from descriptors ====================

    @Nested
    @DisplayName("Agent name from descriptors")
    class AgentNameFromDescriptors {

        @Test
        @DisplayName("should read agent name from matching descriptor")
        void readsAgentName() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());

            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=1", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            // Agent descriptor with matching ID and name
            var agentDesc = new DocumentDescriptor();
            agentDesc.setName("Production Agent");
            agentDesc.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=1"));

            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[desc]");
            when(jsonSerialization.deserialize("[desc]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[]{agentDesc});

            var source = createSource(AGENT_ID, 1);
            AgentSourceData result = source.readAgent();

            assertEquals("Production Agent", result.name());
        }

        @Test
        @DisplayName("should return null name when descriptor list has no match")
        void noMatchingDescriptor() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());

            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=1", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            // Descriptor for a different agent
            var otherDesc = new DocumentDescriptor();
            otherDesc.setName("Other Agent");
            otherDesc.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/bbbbbbbbbbbbbbbbbbbbbbbb?version=1"));

            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[desc]");
            when(jsonSerialization.deserialize("[desc]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[]{otherDesc});

            var source = createSource(AGENT_ID, 1);
            AgentSourceData result = source.readAgent();

            assertNull(result.name());
        }
    }

    // ==================== close() ====================

    @Nested
    @DisplayName("AutoCloseable behavior")
    class AutoCloseableBehavior {

        @Test
        @DisplayName("close() should be a no-op (default implementation)")
        void closeIsNoOp() {
            var source = createSource(AGENT_ID, 1);
            assertDoesNotThrow(source::close);
        }
    }

    // ==================== Helpers ====================

    private RemoteApiResourceSource createSource(String agentId, Integer version) {
        return new RemoteApiResourceSource(
                BASE_URL, agentId, version, "Bearer test-token", jsonSerialization, mockHttpClient);
    }

    @SuppressWarnings("unchecked")
    private void mockHttpResponse(String path, String body) throws Exception {
        HttpResponse<String> mockResponse = Mockito.mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(200);
        when(mockResponse.body()).thenReturn(body);

        when(mockHttpClient.send(
                Mockito.argThat(req -> req != null && req.uri().toString().equals(BASE_URL + path)),
                any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);
    }

    @SuppressWarnings("unchecked")
    private void mockHttpErrorResponse(String path, int status) throws Exception {
        HttpResponse<String> mockResponse = Mockito.mock(HttpResponse.class);
        when(mockResponse.statusCode()).thenReturn(status);
        when(mockResponse.body()).thenReturn("Error");

        when(mockHttpClient.send(
                Mockito.argThat(req -> req != null && req.uri().toString().equals(BASE_URL + path)),
                any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);
    }
}
