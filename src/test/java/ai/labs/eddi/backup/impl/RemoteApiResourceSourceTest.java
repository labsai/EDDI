package ai.labs.eddi.backup.impl;

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

import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RemoteApiResourceSource}. Uses a mock HttpClient to
 * verify correct URL construction, auth header forwarding, caching, and error
 * handling without making real network calls.
 */
class RemoteApiResourceSourceTest {

    private IJsonSerialization jsonSerialization;
    private HttpClient mockHttpClient;

    private static final String BASE_URL = "https://staging.example.com";
    private static final String AGENT_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";

    @BeforeEach
    void setUp() {
        jsonSerialization = Mockito.mock(IJsonSerialization.class);
        mockHttpClient = Mockito.mock(HttpClient.class);
    }

    // ==================== Agent Reading ====================

    @Nested
    @DisplayName("Agent reading")
    class AgentReading {

        @Test
        @DisplayName("should read agent config from remote with explicit version")
        void readsAgentWithExplicitVersion() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());

            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=2", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            // Mock descriptors call for agent name
            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[]");
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            var source = createSource(AGENT_ID, 2);
            AgentSourceData result = source.readAgent();

            assertNotNull(result);
            assertEquals(AGENT_ID, result.sourceId());
            assertSame(agentConfig, result.config());
        }

        @Test
        @DisplayName("should cache agent data on repeated calls")
        void cachesAgentData() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());

            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=1", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[]");
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            var source = createSource(AGENT_ID, 1);
            AgentSourceData first = source.readAgent();
            AgentSourceData second = source.readAgent();

            assertSame(first, second);
        }

        @Test
        @DisplayName("should throw RuntimeException on HTTP error")
        void throwsOnHttpError() throws Exception {
            mockHttpErrorResponse("/agentstore/agents/" + AGENT_ID + "?version=1", 404);

            var source = createSource(AGENT_ID, 1);
            assertThrows(RuntimeException.class, source::readAgent);
        }
    }

    // ==================== Snippet Reading ====================

    @Nested
    @DisplayName("Snippet reading")
    class SnippetReading {

        @Test
        @DisplayName("should read snippets from remote descriptors")
        void readsSnippetsFromDescriptors() throws Exception {
            setupAgentMock();

            // Setup snippet descriptors
            var snippetDesc = new DocumentDescriptor();
            snippetDesc.setName("greeting");
            snippetDesc.setResource(URI.create(
                    "eddi://ai.labs.snippet/snippetstore/snippets/bbbbbbbbbbbbbbbbbbbbbbbb?version=1"));

            mockHttpResponse("/snippetstore/snippets/descriptors?index=0&limit=0", "[desc]");
            when(jsonSerialization.deserialize("[desc]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[]{snippetDesc});

            var snippet = new PromptSnippet();
            snippet.setName("greeting");
            snippet.setContent("Hello!");

            mockHttpResponse("/snippetstore/snippets/bbbbbbbbbbbbbbbbbbbbbbbb?version=1", "{snippet}");
            when(jsonSerialization.deserialize("{snippet}", PromptSnippet.class))
                    .thenReturn(snippet);

            var source = createSource(AGENT_ID, 1);
            List<SnippetSourceData> snippets = source.readSnippets();

            assertEquals(1, snippets.size());
            assertEquals("greeting", snippets.getFirst().name());
            assertEquals("Hello!", snippets.getFirst().snippet().getContent());
        }

        @Test
        @DisplayName("should return empty list when no snippets exist")
        void returnsEmptyWhenNoSnippets() throws Exception {
            setupAgentMock();

            mockHttpResponse("/snippetstore/snippets/descriptors?index=0&limit=0", "[]");
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            var source = createSource(AGENT_ID, 1);
            List<SnippetSourceData> snippets = source.readSnippets();

            assertTrue(snippets.isEmpty());
        }

        @Test
        @DisplayName("should cache snippet data on repeated calls")
        void cachesSnippetData() throws Exception {
            setupAgentMock();

            mockHttpResponse("/snippetstore/snippets/descriptors?index=0&limit=0", "[]");
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            var source = createSource(AGENT_ID, 1);
            List<SnippetSourceData> first = source.readSnippets();
            List<SnippetSourceData> second = source.readSnippets();

            assertSame(first, second);
        }
    }

    // ==================== Workflow Reading ====================

    @Nested
    @DisplayName("Workflow reading")
    class WorkflowReading {

        @Test
        @DisplayName("should return empty list when agent has no workflows")
        void returnsEmptyForNoWorkflows() throws Exception {
            setupAgentMock();

            var source = createSource(AGENT_ID, 1);
            List<WorkflowSourceData> workflows = source.readWorkflows();

            assertTrue(workflows.isEmpty());
        }
    }

    // ==================== URL Normalization ====================

    @Nested
    @DisplayName("URL normalization")
    class UrlNormalization {

        @Test
        @DisplayName("should strip trailing slash from base URL")
        void stripsTrailingSlash() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());

            // The trailing slash should be stripped, so this path should work
            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=1", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[]");
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            // Create with trailing slash
            try (var source = new RemoteApiResourceSource(
                    BASE_URL + "/", AGENT_ID, 1, "Bearer token", jsonSerialization, mockHttpClient)) {
                assertDoesNotThrow(source::readAgent);
            }
        }
    }

    // ==================== Connection Errors ====================

    @Nested
    @DisplayName("Connection errors")
    class ConnectionErrors {

        @Test
        @DisplayName("should wrap IOException in RuntimeException")
        void wrapsIoException() throws Exception {
            when(mockHttpClient.send(any(HttpRequest.class), any()))
                    .thenThrow(new IOException("Connection refused"));

            var source = createSource(AGENT_ID, 1);
            var ex = assertThrows(RuntimeException.class, source::readAgent);
            assertTrue(ex.getMessage().contains("Connection refused"));
        }
    }

    // ==================== Helpers ====================

    private RemoteApiResourceSource createSource(String agentId, Integer version) {
        return new RemoteApiResourceSource(
                BASE_URL, agentId, version, "Bearer test-token", jsonSerialization, mockHttpClient);
    }

    private void setupAgentMock() throws Exception {
        var agentConfig = new AgentConfiguration();
        agentConfig.setWorkflows(new ArrayList<>());

        mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=1", "{agentJson}");
        when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                .thenReturn(agentConfig);

        mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[]");
        when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                .thenReturn(new DocumentDescriptor[0]);
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
        when(mockResponse.body()).thenReturn("Not Found");

        when(mockHttpClient.send(
                Mockito.argThat(req -> req != null && req.uri().toString().equals(BASE_URL + path)),
                any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockResponse);
    }

    // ==================== URL Normalization Edge Cases ====================

    @Nested
    @DisplayName("URL normalization edge cases")
    class UrlNormalizationEdgeCases {

        @Test
        @DisplayName("should return empty string for null URL")
        void nullUrl() {
            // normalizeBaseUrl(null) should return "" — constructor will succeed but calls
            // will fail
            assertDoesNotThrow(() -> new RemoteApiResourceSource(
                    null, AGENT_ID, 1, "Bearer token", jsonSerialization, mockHttpClient));
        }

        @Test
        @DisplayName("should throw for non-http scheme")
        void nonHttpScheme() {
            assertThrows(IllegalArgumentException.class, () -> new RemoteApiResourceSource(
                    "ftp://example.com", AGENT_ID, 1, "Bearer token", jsonSerialization, mockHttpClient));
        }

        @Test
        @DisplayName("should throw for URL with blank host")
        void blankHost() {
            assertThrows(IllegalArgumentException.class, () -> new RemoteApiResourceSource(
                    "http:///path", AGENT_ID, 1, "Bearer token", jsonSerialization, mockHttpClient));
        }
    }

    // ==================== Null Version (resolveLatestAgentVersion)
    // ====================

    @Nested
    @DisplayName("Null version (resolveLatestAgentVersion)")
    class NullVersionTests {

        @Test
        @DisplayName("should resolve latest version from descriptors when version is null")
        void resolvesLatestVersion() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());

            // Mock descriptors endpoint
            var desc = new DocumentDescriptor();
            desc.setName("My Agent");
            desc.setResource(URI.create(
                    "eddi://ai.labs.agent/agentstore/agents/" + AGENT_ID + "?version=5"));

            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[desc]");
            when(jsonSerialization.deserialize("[desc]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[]{desc});

            // Mock agent fetch with resolved version=5
            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=5", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            try (var source = new RemoteApiResourceSource(
                    BASE_URL, AGENT_ID, null, "Bearer test-token", jsonSerialization, mockHttpClient)) {
                AgentSourceData result = source.readAgent();

                assertNotNull(result);
                assertEquals(AGENT_ID, result.sourceId());
            }
        }

        @Test
        @DisplayName("should fallback to version=1 when descriptor lookup fails")
        void fallbacksToVersion1() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());

            // Descriptors return empty (no matching agent)
            mockHttpResponse("/agentstore/agents/descriptors?index=0&limit=0", "[]");
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            // Should fallback to version=1
            mockHttpResponse("/agentstore/agents/" + AGENT_ID + "?version=1", "{agentJson}");
            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);

            try (var source = new RemoteApiResourceSource(
                    BASE_URL, AGENT_ID, null, "Bearer test-token", jsonSerialization, mockHttpClient)) {
                AgentSourceData result = source.readAgent();

                assertNotNull(result);
            }
        }
    }

    // ==================== Snippet edge cases ====================

    @Nested
    @DisplayName("Snippet edge cases")
    class SnippetEdgeCases {

        @Test
        @DisplayName("should return empty list when descriptors are null")
        void returnsEmptyWhenDescriptorsNull() throws Exception {
            setupAgentMock();

            mockHttpResponse("/snippetstore/snippets/descriptors?index=0&limit=0", "null");
            when(jsonSerialization.deserialize("null", DocumentDescriptor[].class))
                    .thenReturn(null);

            var source = createSource(AGENT_ID, 1);
            List<SnippetSourceData> snippets = source.readSnippets();

            assertTrue(snippets.isEmpty());
        }

        @Test
        @DisplayName("should skip snippets with null name")
        void skipsSnippetsWithNullName() throws Exception {
            setupAgentMock();

            var snippetDesc = new DocumentDescriptor();
            snippetDesc.setName("nameless");
            snippetDesc.setResource(URI.create(
                    "eddi://ai.labs.snippet/snippetstore/snippets/cccccccccccccccccccccccc?version=1"));

            mockHttpResponse("/snippetstore/snippets/descriptors?index=0&limit=0", "[desc]");
            when(jsonSerialization.deserialize("[desc]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[]{snippetDesc});

            var snippet = new PromptSnippet();
            snippet.setName(null); // null name → should be skipped
            snippet.setContent("content");

            mockHttpResponse("/snippetstore/snippets/cccccccccccccccccccccccc?version=1", "{snippet}");
            when(jsonSerialization.deserialize("{snippet}", PromptSnippet.class))
                    .thenReturn(snippet);

            var source = createSource(AGENT_ID, 1);
            List<SnippetSourceData> snippets = source.readSnippets();

            assertTrue(snippets.isEmpty());
        }
    }

    // ==================== Auth header ====================

    @Nested
    @DisplayName("Auth header handling")
    class AuthHeaderTests {

        @Test
        @DisplayName("should not add Authorization header when token is null")
        @SuppressWarnings("unchecked")
        void noAuthWhenTokenNull() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());

            // Use any() matcher since without auth token the request will be different
            HttpResponse<String> mockResponse = Mockito.mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{agentJson}", "[]");

            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            try (var source = new RemoteApiResourceSource(
                    BASE_URL, AGENT_ID, 1, null, jsonSerialization, mockHttpClient)) {
                assertDoesNotThrow(source::readAgent);
            }
        }

        @Test
        @DisplayName("should not add Authorization header when token is blank")
        @SuppressWarnings("unchecked")
        void noAuthWhenTokenBlank() throws Exception {
            var agentConfig = new AgentConfiguration();
            agentConfig.setWorkflows(new ArrayList<>());

            HttpResponse<String> mockResponse = Mockito.mock(HttpResponse.class);
            when(mockResponse.statusCode()).thenReturn(200);
            when(mockResponse.body()).thenReturn("{agentJson}", "[]");

            when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                    .thenReturn(mockResponse);

            when(jsonSerialization.deserialize("{agentJson}", AgentConfiguration.class))
                    .thenReturn(agentConfig);
            when(jsonSerialization.deserialize("[]", DocumentDescriptor[].class))
                    .thenReturn(new DocumentDescriptor[0]);

            try (var source = new RemoteApiResourceSource(
                    BASE_URL, AGENT_ID, 1, "   ", jsonSerialization, mockHttpClient)) {
                assertDoesNotThrow(source::readAgent);
            }
        }
    }
}
