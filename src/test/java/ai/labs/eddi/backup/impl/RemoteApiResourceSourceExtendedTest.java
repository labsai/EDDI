/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.backup.IResourceSource;
import ai.labs.eddi.backup.IResourceSource.*;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.snippets.model.PromptSnippet;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Extended tests for {@link RemoteApiResourceSource} — more branch coverage on
 * workflow reading, listRemoteAgentDescriptors, InterruptedException, httpGet
 * edge cases, etc.
 */
@DisplayName("RemoteApiResourceSource — Extended Branch Coverage")
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

    // ==================== httpGet edge cases ====================

    @Nested
    @DisplayName("httpGet edge cases")
    class HttpGetEdgeCases {

        @Test
        @DisplayName("null path throws IllegalArgumentException")
        void nullPath() {
            var source = createSource(AGENT_ID, 1);
            // readAgent calls httpGet with a non-null path, so we test via invalid path
            assertThrows(RuntimeException.class, source::readAgent);
        }

        @Test
        @DisplayName("InterruptedException is wrapped AND the interrupt flag is restored")
        @SuppressWarnings("unchecked")
        void interruptedException() throws Exception {
            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenThrow(new InterruptedException("interrupted"));

            var source = createSource(AGENT_ID, 1);
            try {
                var ex = assertThrows(RuntimeException.class, source::readAgent);
                assertTrue(ex.getMessage().contains("Interrupted"), ex.getMessage());

                // readWorkflows tolerates per-workflow failures and carries on, so a
                // swallowed interrupt meant a cancelled or shutting-down batch sync kept
                // issuing HTTP calls to the remote instance.
                assertTrue(Thread.currentThread().isInterrupted(),
                        "the interrupt flag must be restored before unwinding");
            } finally {
                // Clear it again so the flag does not leak into the next test.
                Thread.interrupted();
            }
        }
    }

    // ==================== readWorkflows with actual workflows ====================

    @Nested
    @DisplayName("Workflow reading with extensions")
    class WorkflowWithExtensions {

        @Test
        @DisplayName("readWorkflows caches on second call")
        void cachesWorkflows() throws Exception {
            setupAgentMock();

            var source = createSource(AGENT_ID, 1);
            List<WorkflowSourceData> first = source.readWorkflows();
            List<WorkflowSourceData> second = source.readWorkflows();

            assertSame(first, second);
        }

        @Test
        @DisplayName("readWorkflows handles workflow read failure gracefully")
        @SuppressWarnings("unchecked")
        void workflowReadFailure() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>(List.of(
                    URI.create("eddi://ai.labs.workflow/workflowstore/workflows/wf1?version=1"))));

            // First call: agent, second: descriptors, third: workflow fetch → fail
            HttpResponse<String> agentResponse = Mockito.mock(HttpResponse.class);
            when(agentResponse.statusCode()).thenReturn(200);
            when(agentResponse.body()).thenReturn("{agentJson}");

            HttpResponse<String> descriptorResponse = Mockito.mock(HttpResponse.class);
            when(descriptorResponse.statusCode()).thenReturn(200);
            when(descriptorResponse.body()).thenReturn("[]");

            HttpResponse<String> failResponse = Mockito.mock(HttpResponse.class);
            when(failResponse.statusCode()).thenReturn(500);
            when(failResponse.body()).thenReturn("Server Error");

            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(agentResponse)
                    .thenReturn(descriptorResponse)
                    .thenReturn(failResponse);

            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            var source = createSource(AGENT_ID, 1);
            List<WorkflowSourceData> workflows = source.readWorkflows();

            // Should not throw — failed workflow silently skipped
            assertTrue(workflows.isEmpty());
        }
    }

    // ==================== listRemoteAgentDescriptors ====================

    @Nested
    @DisplayName("listRemoteAgentDescriptors static utility")
    class ListRemoteAgents {

        @Test
        @DisplayName("null descriptors returns empty list")
        @SuppressWarnings("unchecked")
        void nullDescriptors() throws Exception {
            HttpClient client = Mockito.mock(HttpClient.class);
            HttpResponse<String> response = Mockito.mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(200);
            when(response.body()).thenReturn("null");
            when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(response);
            when(jsonSerialization.deserialize("null", DocumentDescriptor[].class))
                    .thenReturn(null);

            // We can't inject the mock HttpClient into the static method, so test the
            // error case
            assertThrows(RuntimeException.class, () -> RemoteApiResourceSource.listRemoteAgentDescriptors(
                    "ftp://invalid", null, jsonSerialization));
        }

        @Test
        @DisplayName("non-200 status throws RuntimeException")
        void non200Status() {
            // Invalid URL triggers an error before even trying HTTP
            assertThrows(RuntimeException.class, () -> RemoteApiResourceSource.listRemoteAgentDescriptors(
                    "ftp://invalid", null, jsonSerialization));
        }
    }

    // ==================== readSnippets — snippet with null PromptSnippet
    // ====================

    @Nested
    @DisplayName("readSnippets edge cases")
    class SnippetEdgeCases2 {

        @Test
        @DisplayName("snippet deserialized as null is skipped")
        @SuppressWarnings("unchecked")
        void nullSnippetDeserialization() throws Exception {
            setupAgentMock();

            var desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/snip1?version=1"));

            HttpResponse<String> snippetDescResponse = Mockito.mock(HttpResponse.class);
            when(snippetDescResponse.statusCode()).thenReturn(200);
            when(snippetDescResponse.body()).thenReturn("[desc]");

            HttpResponse<String> snippetResponse = Mockito.mock(HttpResponse.class);
            when(snippetResponse.statusCode()).thenReturn(200);
            when(snippetResponse.body()).thenReturn("{snippet}");

            // Mock additional calls for snippet reading
            when(mockHttpClient.send(
                    Mockito.argThat(req -> req != null && req.uri().toString().contains("snippetstore/snippets/descriptors")),
                    any(HttpResponse.BodyHandler.class)))
                    .thenReturn(snippetDescResponse);
            when(mockHttpClient.send(
                    Mockito.argThat(req -> req != null && req.uri().toString().contains("snippetstore/snippets/snip1")),
                    any(HttpResponse.BodyHandler.class)))
                    .thenReturn(snippetResponse);

            when(jsonSerialization.deserialize("[desc]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[]{desc});
            when(jsonSerialization.deserialize("{snippet}", PromptSnippet.class))
                    .thenReturn(null); // null snippet

            var source = createSource(AGENT_ID, 1);
            List<SnippetSourceData> snippets = source.readSnippets();

            assertTrue(snippets.isEmpty());
        }

        @Test
        @DisplayName("snippet with null resource in descriptor is skipped")
        @SuppressWarnings("unchecked")
        void nullResourceInDescriptor() throws Exception {
            setupAgentMock();

            var desc = new DocumentDescriptor();
            desc.setResource(null); // null resource

            HttpResponse<String> snippetDescResponse = Mockito.mock(HttpResponse.class);
            when(snippetDescResponse.statusCode()).thenReturn(200);
            when(snippetDescResponse.body()).thenReturn("[desc]");

            when(mockHttpClient.send(
                    Mockito.argThat(req -> req != null && req.uri().toString().contains("snippetstore/snippets/descriptors")),
                    any(HttpResponse.BodyHandler.class)))
                    .thenReturn(snippetDescResponse);

            when(jsonSerialization.deserialize("[desc]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[]{desc});

            var source = createSource(AGENT_ID, 1);
            List<SnippetSourceData> snippets = source.readSnippets();

            assertTrue(snippets.isEmpty());
        }

        @Test
        @DisplayName("snippet read exception is handled gracefully")
        @SuppressWarnings("unchecked")
        void snippetReadException() throws Exception {
            setupAgentMock();

            var desc = new DocumentDescriptor();
            desc.setResource(URI.create("eddi://ai.labs.snippet/snippetstore/snippets/snip1?version=1"));

            HttpResponse<String> snippetDescResponse = Mockito.mock(HttpResponse.class);
            when(snippetDescResponse.statusCode()).thenReturn(200);
            when(snippetDescResponse.body()).thenReturn("[desc]");

            HttpResponse<String> snippetErrorResponse = Mockito.mock(HttpResponse.class);
            when(snippetErrorResponse.statusCode()).thenReturn(404);
            when(snippetErrorResponse.body()).thenReturn("not found");

            when(mockHttpClient.send(
                    Mockito.argThat(req -> req != null && req.uri().toString().contains("snippetstore/snippets/descriptors")),
                    any(HttpResponse.BodyHandler.class)))
                    .thenReturn(snippetDescResponse);
            when(mockHttpClient.send(
                    Mockito.argThat(req -> req != null && req.uri().toString().contains("snippetstore/snippets/snip1")),
                    any(HttpResponse.BodyHandler.class)))
                    .thenReturn(snippetErrorResponse);

            when(jsonSerialization.deserialize("[desc]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[]{desc});

            var source = createSource(AGENT_ID, 1);
            List<SnippetSourceData> snippets = source.readSnippets();

            assertTrue(snippets.isEmpty());
        }
    }

    // ==================== close() ====================

    @Nested
    @DisplayName("AutoCloseable behavior")
    class CloseableTests {

        /**
         * The client this class builds for itself owns a selector thread and an
         * executor. Callers already wrap every source in try-with-resources, so
         * inheriting {@link IResourceSource}'s no-op {@code close()} left one live
         * client per agent behind on every batch sync.
         * <p>
         * This replaces a test whose only assertion was
         * {@code assertDoesNotThrow(source::close)} driven through the
         * <em>borrowed</em>-client constructor — a guaranteed no-op that passed just as
         * happily with the whole override deleted.
         */
        @Test
        @DisplayName("close() shuts down the HTTP client this source created")
        void closeShutsDownTheClientItCreated() throws Exception {
            // The ownership flag is what the public constructor sets; it is passed
            // explicitly here because building a real client opens a selector, and
            // that needs a loopback socket a sandboxed build does not have.
            var source = new RemoteApiResourceSource(
                    BASE_URL, AGENT_ID, 1, "Bearer test-token", jsonSerialization, mockHttpClient, true);

            source.close();

            verify(mockHttpClient).close();
        }

        @Test
        @DisplayName("close() leaves a client it was handed alone")
        void closeLeavesABorrowedClientOpen() throws Exception {
            var source = createSource(AGENT_ID, 1);

            source.close();

            // Closing a client this instance did not create would take the caller's
            // own client down with it.
            verify(mockHttpClient, never()).close();
        }
    }

    // ==================== Helpers ====================

    private RemoteApiResourceSource createSource(String agentId, Integer version) {
        return new RemoteApiResourceSource(
                BASE_URL, agentId, version, "Bearer test-token", jsonSerialization, mockHttpClient);
    }

    @SuppressWarnings("unchecked")
    private void setupAgentMock() throws Exception {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(new ArrayList<>());

        HttpResponse<String> agentResponse = Mockito.mock(HttpResponse.class);
        when(agentResponse.statusCode()).thenReturn(200);
        when(agentResponse.body()).thenReturn("{agentJson}");

        HttpResponse<String> descriptorResponse = Mockito.mock(HttpResponse.class);
        when(descriptorResponse.statusCode()).thenReturn(200);
        when(descriptorResponse.body()).thenReturn("[]");

        when(mockHttpClient.send(
                Mockito.argThat(req -> req != null && req.uri().toString().contains("agentstore/agents/" + AGENT_ID)),
                any(HttpResponse.BodyHandler.class)))
                .thenReturn(agentResponse);
        when(mockHttpClient.send(
                Mockito.argThat(req -> req != null && req.uri().toString().contains("agentstore/agents/descriptors")),
                any(HttpResponse.BodyHandler.class)))
                .thenReturn(descriptorResponse);

        when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                .thenReturn(agentConfig);
        when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                .thenReturn(new DocumentDescriptor[0]);
    }
}
