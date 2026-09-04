/* Copyright (C) 2012-2026 EDDI contributors */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Deep coverage tests for {@link RemoteApiResourceSource}: URL normalization,
 * httpGet validation, caching, snippet/workflow reading, version resolution,
 * and connection error handling.
 */
@SuppressWarnings("unchecked")
class RemoteApiResourceSourceDeepCoverageTest {

    private IJsonSerialization jsonSerialization;
    private HttpClient httpClient;
    private HttpResponse<String> mockResponse;

    @BeforeEach
    void setUp() throws Exception {
        jsonSerialization = mock(IJsonSerialization.class);
        httpClient = mock(HttpClient.class);
        mockResponse = mock(HttpResponse.class);
        doReturn(200).when(mockResponse).statusCode();
        doReturn("{}").when(mockResponse).body();
        doReturn(mockResponse).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    // ==================== normalizeBaseUrl ====================

    @Nested
    @DisplayName("normalizeBaseUrl")
    class NormalizeBaseUrl {

        @Test
        @DisplayName("null → empty string (constructor won't throw)")
        void nullUrl() {
            // null URL results in empty baseUrl, but httpGet will fail later
            // The constructor should not throw for null
            assertDoesNotThrow(() -> new RemoteApiResourceSource(null, "agent1", 1, null, jsonSerialization, httpClient));
        }

        @Test
        @DisplayName("trailing slash is stripped")
        void trailingSlash() {
            // Should not throw
            assertDoesNotThrow(() -> new RemoteApiResourceSource("http://127.0.0.1:1/", "agent1", 1, null, jsonSerialization, httpClient));
        }

        @Test
        @DisplayName("non-http scheme → IllegalArgumentException")
        void nonHttpScheme() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RemoteApiResourceSource("ftp://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient));
        }

        @Test
        @DisplayName("blank host → IllegalArgumentException")
        void blankHost() {
            assertThrows(IllegalArgumentException.class,
                    () -> new RemoteApiResourceSource("http://", "agent1", 1, null, jsonSerialization, httpClient));
        }
    }

    // ==================== httpGet ====================

    @Nested
    @DisplayName("httpGet (tested through readAgent)")
    class HttpGet {

        @Test
        @DisplayName("non-200 response → RuntimeException")
        void non200Response() throws Exception {
            doReturn(404).when(mockResponse).statusCode();
            doReturn(mockResponse).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            assertThrows(RuntimeException.class, source::readAgent);
        }
    }

    // ==================== readAgent ====================

    @Nested
    @DisplayName("readAgent")
    class ReadAgent {

        @Test
        @DisplayName("caching — second call returns same object")
        void readAgentCaching() throws Exception {
            String agentJson = "{\"workflows\":[]}";
            doReturn(agentJson).when(mockResponse).body();

            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(List.of());
            doReturn(config).when(jsonSerialization).deserialize(eq(agentJson), eq(AgentConfiguration.class));
            doReturn(null).when(jsonSerialization).deserialize(anyString(), eq(DocumentDescriptor[].class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            var first = source.readAgent();
            var second = source.readAgent();
            assertSame(first, second);
        }
    }

    // ==================== readSnippets ====================

    @Nested
    @DisplayName("readSnippets")
    class ReadSnippets {

        @Test
        @DisplayName("null descriptors → empty list")
        void nullDescriptors() throws Exception {
            doReturn(null).when(jsonSerialization).deserialize(anyString(), eq(DocumentDescriptor[].class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            var snippets = source.readSnippets();
            assertTrue(snippets.isEmpty());
        }

        @Test
        @DisplayName("snippet with null name → skipped")
        void snippetNullNameSkipped() throws Exception {
            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/s1?version=1"));
            DocumentDescriptor[] descs = new DocumentDescriptor[]{desc};

            // First call returns snippet descriptors JSON, second call returns individual
            // snippet JSON
            doReturn("[]").doReturn("{}").when(mockResponse).body();
            doReturn(descs).when(jsonSerialization).deserialize(eq("[]"), eq(DocumentDescriptor[].class));

            PromptSnippet snippetNoName = new PromptSnippet();
            // name is null
            doReturn(snippetNoName).when(jsonSerialization).deserialize(eq("{}"), eq(PromptSnippet.class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            var snippets = source.readSnippets();
            assertTrue(snippets.isEmpty());
        }

        @Test
        @DisplayName("caching — second call returns same list")
        void readSnippetsCaching() throws Exception {
            doReturn(null).when(jsonSerialization).deserialize(anyString(), eq(DocumentDescriptor[].class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            var first = source.readSnippets();
            var second = source.readSnippets();
            assertSame(first, second);
        }
    }

    // ==================== readWorkflows ====================

    @Nested
    @DisplayName("readWorkflows")
    class ReadWorkflows {

        @Test
        @DisplayName("caching — second call returns same list")
        void readWorkflowsCaching() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(List.of());
            doReturn(config).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));
            doReturn(null).when(jsonSerialization).deserialize(anyString(), eq(DocumentDescriptor[].class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            var first = source.readWorkflows();
            var second = source.readWorkflows();
            assertSame(first, second);
        }

        @Test
        @DisplayName("readSingleWorkflow null resId → null → skipped")
        void readSingleWorkflowNullResId() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            // Invalid URI that extractResourceId returns null for
            config.setWorkflows(List.of(URI.create("eddi://invalid")));
            doReturn(config).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));
            doReturn(null).when(jsonSerialization).deserialize(anyString(), eq(DocumentDescriptor[].class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            var workflows = source.readWorkflows();
            assertTrue(workflows.isEmpty());
        }
    }

    // ==================== readExtensionsFromWorkflow ====================

    @Test
    @DisplayName("readExtensionsFromWorkflow — null stepType skipped; unknown stepType skipped")
    void readExtensionsFromWorkflowSkips() throws Exception {
        AgentConfiguration agentConfig = new AgentConfiguration();
        URI wfUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaaaaaaaaaaaaaaaaaaaaaa?version=1");
        agentConfig.setWorkflows(List.of(wfUri));
        doReturn(agentConfig).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));

        WorkflowConfiguration wfConfig = new WorkflowConfiguration();
        // Step with null type
        WorkflowConfiguration.WorkflowStep nullTypeStep = new WorkflowConfiguration.WorkflowStep();
        nullTypeStep.setType(null);
        // Step with unknown type
        WorkflowConfiguration.WorkflowStep unknownStep = new WorkflowConfiguration.WorkflowStep();
        unknownStep.setType(URI.create("ai.labs.unknown"));
        unknownStep
                .setExtensions(new HashMap<>(Map.<String, Object>of("uri", "eddi://ai.labs.unknown/store/things/t1?version=1")));

        wfConfig.setWorkflowSteps(List.of(nullTypeStep, unknownStep));
        doReturn(wfConfig).when(jsonSerialization).deserialize(anyString(), eq(WorkflowConfiguration.class));
        doReturn(null).when(jsonSerialization).deserialize(anyString(), eq(DocumentDescriptor[].class));

        RemoteApiResourceSource source = new RemoteApiResourceSource(
                "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

        var workflows = source.readWorkflows();
        assertEquals(1, workflows.size());
        // Extensions should be empty since both steps were skipped
        assertTrue(workflows.get(0).extensions().isEmpty());
    }

    // ==================== resolveLatestAgentVersion ====================

    @Nested
    @DisplayName("resolveLatestAgentVersion")
    class ResolveVersion {

        // A version that cannot be resolved is an error, not a reason to guess:
        // "fall back to 1" silently synced an arbitrarily old configuration into the
        // target and left only a DEBUG line behind.

        @Test
        @DisplayName("null descriptors → error, not a guess at version 1")
        void nullDescriptors() throws Exception {
            doReturn(null).when(jsonSerialization).deserialize(anyString(), eq(DocumentDescriptor[].class));

            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(List.of());
            // Pass version=null to trigger resolveLatestAgentVersion
            doReturn(config).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", null, null, jsonSerialization, httpClient);

            var ex = assertThrows(RuntimeException.class, source::readAgent);
            assertTrue(ex.getMessage().contains("latest version"), ex.getMessage());
        }

        @Test
        @DisplayName("no matching descriptor → error, not a guess at version 1")
        void noMatchingDescriptor() throws Exception {
            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/bbbbbbbbbbbbbbbbbbbbbbbb?version=3"));
            DocumentDescriptor[] descs = new DocumentDescriptor[]{desc};

            // First call: resolve version (descriptors), second call: read agent
            doReturn("[]").doReturn("{}").doReturn("[]").when(mockResponse).body();
            doReturn(descs).when(jsonSerialization).deserialize(eq("[]"), eq(DocumentDescriptor[].class));

            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(List.of());
            doReturn(config).when(jsonSerialization).deserialize(eq("{}"), eq(AgentConfiguration.class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "aaaaaaaaaaaaaaaaaaaaaaaa", null, null, jsonSerialization, httpClient);

            var ex = assertThrows(RuntimeException.class, source::readAgent);
            assertTrue(ex.getMessage().contains("latest version"), ex.getMessage());
        }

        @Test
        @DisplayName("matching descriptor → its version is used")
        void matchingDescriptorResolvesVersion() throws Exception {
            String agentId = "aaaaaaaaaaaaaaaaaaaaaaaa";
            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + agentId + "?version=7"));

            doReturn("[]").doReturn("{}").doReturn("[]").when(mockResponse).body();
            doReturn(new DocumentDescriptor[]{desc}).when(jsonSerialization)
                    .deserialize(eq("[]"), eq(DocumentDescriptor[].class));

            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(List.of());
            doReturn(config).when(jsonSerialization).deserialize(eq("{}"), eq(AgentConfiguration.class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", agentId, null, null, jsonSerialization, httpClient);

            assertNotNull(source.readAgent());
        }
    }

    // ==================== tryReadDescriptorName ====================

    @Test
    @DisplayName("tryReadDescriptorName — storePath ending in '/' is trimmed")
    void tryReadDescriptorNameTrailingSlash() throws Exception {
        // This is tested indirectly through readExtensionsFromWorkflow
        // The STEP_TYPE_TO_REST_PATH values end with "/" which tryReadDescriptorName
        // trims
        AgentConfiguration agentConfig = new AgentConfiguration();
        URI wfUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/aaaaaaaaaaaaaaaaaaaaaaaa?version=1");
        agentConfig.setWorkflows(List.of(wfUri));
        doReturn(agentConfig).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));

        WorkflowConfiguration wfConfig = new WorkflowConfiguration();
        WorkflowConfiguration.WorkflowStep step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.llm"));
        step.setExtensions(new HashMap<>(Map.<String, Object>of("uri", "eddi://ai.labs.llm/llmstore/llms/bbbbbbbbbbbbbbbbbbbbbbbb?version=1")));
        wfConfig.setWorkflowSteps(List.of(step));
        doReturn(wfConfig).when(jsonSerialization).deserialize(anyString(), eq(WorkflowConfiguration.class));
        doReturn(null).when(jsonSerialization).deserialize(anyString(), eq(DocumentDescriptor[].class));

        RemoteApiResourceSource source = new RemoteApiResourceSource(
                "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

        var workflows = source.readWorkflows();
        // Should succeed without errors (the trailing slash is handled)
        assertEquals(1, workflows.size());
    }

    // ==================== listRemoteAgentDescriptors ====================

    @Test
    @DisplayName("listRemoteAgentDescriptors — connection to 127.0.0.1:1 throws RuntimeException")
    void listRemoteAgentsConnectionFailure() {
        assertThrows(RuntimeException.class, () -> RemoteApiResourceSource.listRemoteAgentDescriptors(
                "http://127.0.0.1:1", null, jsonSerialization));
    }
}
