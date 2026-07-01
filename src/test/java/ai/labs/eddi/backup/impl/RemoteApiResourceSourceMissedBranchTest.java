/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
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

@DisplayName("RemoteApiResourceSource — Missed Branch Coverage")
@SuppressWarnings("unchecked")
class RemoteApiResourceSourceMissedBranchTest {

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

    // ─── httpGet — path validation ──────────────────────────────────────

    @Nested
    @DisplayName("httpGet path validation")
    class HttpGetPathValidation {

        @Test
        @DisplayName("IOException from client.send → RuntimeException")
        void ioException() throws Exception {
            doThrow(new IOException("connection failed"))
                    .when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            assertThrows(RuntimeException.class, source::readAgent);
        }

        @Test
        @DisplayName("InterruptedException from client.send → RuntimeException")
        void interruptedException() throws Exception {
            doThrow(new InterruptedException("interrupted"))
                    .when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            assertThrows(RuntimeException.class, source::readAgent);
        }
    }

    // ─── httpGet — auth token ───────────────────────────────────────────

    @Nested
    @DisplayName("httpGet with auth token")
    class HttpGetWithAuth {

        @Test
        @DisplayName("non-blank auth token — Authorization header added")
        void withAuthToken() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(List.of());
            doReturn(config).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));
            doReturn(null).when(jsonSerialization).deserialize(anyString(), eq(DocumentDescriptor[].class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, "Bearer my-token", jsonSerialization, httpClient);

            source.readAgent();
            verify(httpClient, atLeastOnce()).send(
                    argThat(request -> request.headers().firstValue("Authorization").orElse("").equals("Bearer my-token")),
                    any(HttpResponse.BodyHandler.class));
        }

        @Test
        @DisplayName("blank auth token — no Authorization header")
        void blankAuthToken() throws Exception {
            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(List.of());
            doReturn(config).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));
            doReturn(null).when(jsonSerialization).deserialize(anyString(), eq(DocumentDescriptor[].class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, "   ", jsonSerialization, httpClient);

            source.readAgent();
            verify(httpClient, atLeastOnce()).send(argThat(request -> request.headers().firstValue("Authorization").isEmpty()),
                    any(HttpResponse.BodyHandler.class));
        }
    }

    // ─── readSnippets — valid snippet ───────────────────────────────────

    @Nested
    @DisplayName("readSnippets — success paths")
    class ReadSnippetsSuccess {

        @Test
        @DisplayName("valid snippet with name — added to list")
        void validSnippetAdded() throws Exception {
            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/s1?version=1"));
            DocumentDescriptor[] descs = new DocumentDescriptor[]{desc};

            doReturn("[]").doReturn("{}").when(mockResponse).body();
            doReturn(descs).when(jsonSerialization).deserialize(eq("[]"), eq(DocumentDescriptor[].class));

            PromptSnippet snippet = new PromptSnippet();
            snippet.setName("my-snippet");
            doReturn(snippet).when(jsonSerialization).deserialize(eq("{}"), eq(PromptSnippet.class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            var snippets = source.readSnippets();
            assertEquals(1, snippets.size());
            assertEquals("my-snippet", snippets.get(0).name());
        }

        @Test
        @DisplayName("descriptor with null resource ID — skipped")
        void nullResourceId() throws Exception {
            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://invalid"));
            DocumentDescriptor[] descs = new DocumentDescriptor[]{desc};

            doReturn("[]").when(mockResponse).body();
            doReturn(descs).when(jsonSerialization).deserialize(eq("[]"), eq(DocumentDescriptor[].class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            var snippets = source.readSnippets();
            assertTrue(snippets.isEmpty());
        }

        @Test
        @DisplayName("exception reading individual snippet — skipped")
        void snippetReadException() throws Exception {
            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/s1?version=1"));
            DocumentDescriptor[] descs = new DocumentDescriptor[]{desc};

            // First call OK for descriptors, second call fails for individual snippet
            doReturn("[]").when(mockResponse).body();
            doReturn(descs).when(jsonSerialization).deserialize(eq("[]"), eq(DocumentDescriptor[].class));
            doThrow(new IOException("parse error")).when(jsonSerialization).deserialize(eq("{}"), eq(PromptSnippet.class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            var snippets = source.readSnippets();
            assertTrue(snippets.isEmpty());
        }
    }

    // ─── readWorkflows — exception paths ────────────────────────────────

    @Nested
    @DisplayName("readWorkflows — exception paths")
    class ReadWorkflowsExceptions {

        @Test
        @DisplayName("exception reading individual workflow — skipped")
        void workflowReadException() throws Exception {
            // readWorkflows() calls readAgent() first, so we need the agent read to succeed
            // Agent read: 1st call for agent JSON, 2nd call for agent descriptor name
            // Then workflow read: 3rd call returns 404
            HttpResponse<String> okResponse = mock(HttpResponse.class);
            doReturn(200).when(okResponse).statusCode();

            HttpResponse<String> failResponse = mock(HttpResponse.class);
            doReturn(404).when(failResponse).statusCode();

            // Chain: agent JSON (200) → descriptor (200) → workflow (404)
            doReturn(okResponse).doReturn(okResponse).doReturn(failResponse)
                    .when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

            AgentConfiguration agentConfig = new AgentConfiguration();
            URI wfUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1");
            agentConfig.setWorkflows(List.of(wfUri));
            doReturn("{}").when(okResponse).body();
            doReturn(agentConfig).when(jsonSerialization).deserialize(eq("{}"), eq(AgentConfiguration.class));
            doReturn(null).when(jsonSerialization).deserialize(eq("{}"), eq(DocumentDescriptor[].class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

            var workflows = source.readWorkflows();
            assertTrue(workflows.isEmpty());
        }
    }

    // ─── readExtensionsFromWorkflow — null uri extension ────────────────

    @Test
    @DisplayName("step with null uri extension — skipped")
    void nullUriExtension() throws Exception {
        AgentConfiguration agentConfig = new AgentConfiguration();
        URI wfUri = URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1");
        agentConfig.setWorkflows(List.of(wfUri));
        doReturn(agentConfig).when(jsonSerialization).deserialize(anyString(), eq(AgentConfiguration.class));

        WorkflowConfiguration wfConfig = new WorkflowConfiguration();
        WorkflowConfiguration.WorkflowStep step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("ai.labs.llm"));
        // uri key is missing from extensions
        step.setExtensions(new HashMap<>(Map.of("other", "value")));
        wfConfig.setWorkflowSteps(List.of(step));
        doReturn(wfConfig).when(jsonSerialization).deserialize(anyString(), eq(WorkflowConfiguration.class));
        doReturn(null).when(jsonSerialization).deserialize(anyString(), eq(DocumentDescriptor[].class));

        RemoteApiResourceSource source = new RemoteApiResourceSource(
                "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient);

        var workflows = source.readWorkflows();
        assertEquals(1, workflows.size());
        assertTrue(workflows.get(0).extensions().isEmpty());
    }

    // ─── resolveLatestAgentVersion — matching descriptor ────────────────

    @Nested
    @DisplayName("resolveLatestAgentVersion")
    class ResolveVersion {

        @Test
        @DisplayName("matching descriptor — returns its version")
        void matchingDescriptor() throws Exception {
            DocumentDescriptor desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/agent1?version=5"));
            DocumentDescriptor[] descs = new DocumentDescriptor[]{desc};

            doReturn("[]").doReturn("{}").doReturn("[]").when(mockResponse).body();
            doReturn(descs).when(jsonSerialization).deserialize(eq("[]"), eq(DocumentDescriptor[].class));

            AgentConfiguration config = new AgentConfiguration();
            config.setWorkflows(List.of());
            doReturn(config).when(jsonSerialization).deserialize(eq("{}"), eq(AgentConfiguration.class));

            RemoteApiResourceSource source = new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", null, null, jsonSerialization, httpClient);

            var agentData = source.readAgent();
            assertNotNull(agentData);
        }
    }

    // ─── normalizeBaseUrl — additional paths ────────────────────────────

    @Nested
    @DisplayName("normalizeBaseUrl — additional")
    class NormalizeAdditional {

        @Test
        @DisplayName("https scheme — accepted")
        void httpsScheme() {
            assertDoesNotThrow(() -> new RemoteApiResourceSource(
                    "https://remote.example.com", "agent1", 1, null, jsonSerialization, httpClient));
        }

        @Test
        @DisplayName("URL without trailing slash — accepted")
        void noTrailingSlash() {
            assertDoesNotThrow(() -> new RemoteApiResourceSource(
                    "http://127.0.0.1:1", "agent1", 1, null, jsonSerialization, httpClient));
        }
    }
}
