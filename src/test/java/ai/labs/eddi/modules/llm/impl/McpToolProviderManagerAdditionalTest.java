/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import dev.langchain4j.mcp.client.McpClient;
import org.junit.jupiter.api.*;
import org.mockito.Mock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Additional tests for {@link McpToolProviderManager} — covering branches
 * around server name fallback, API key resolution, timeout defaults,
 * closeClient with cached entry, shutdown with cached entries (including error
 * during close), and discoverTools with name fallback.
 */
@DisplayName("McpToolProviderManager — Additional Branch Coverage")
class McpToolProviderManagerAdditionalTest {

    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private SecretResolver secretResolver;

    private McpToolProviderManager manager;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        manager = new McpToolProviderManager(globalVariableResolver, secretResolver);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    /**
     * Helper to inject a mock client into the clientCache for testing
     * close/shutdown.
     */
    @SuppressWarnings("unchecked")
    private Map<String, McpClient> getClientCache() throws Exception {
        Field cacheField = McpToolProviderManager.class.getDeclaredField("clientCache");
        cacheField.setAccessible(true);
        return (Map<String, McpClient>) cacheField.get(manager);
    }

    // ==================== closeClient ====================

    @Nested
    @DisplayName("closeClient")
    class CloseClientTests {

        @Test
        @DisplayName("closing cached client — removes and closes it")
        void closeCachedClient() throws Exception {
            McpClient mockClient = mock(McpClient.class);
            getClientCache().put("http://test-server:8080", mockClient);
            assertEquals(1, manager.getActiveConnectionCount());

            manager.closeClient("http://test-server:8080");

            assertEquals(0, manager.getActiveConnectionCount());
            verify(mockClient).close();
        }

        @Test
        @DisplayName("closing cached client — handles close exception gracefully")
        void closeClientWithException() throws Exception {
            McpClient mockClient = mock(McpClient.class);
            doThrow(new RuntimeException("close error")).when(mockClient).close();
            getClientCache().put("http://error-server:8080", mockClient);

            assertDoesNotThrow(() -> manager.closeClient("http://error-server:8080"));
            assertEquals(0, manager.getActiveConnectionCount());
        }
    }

    // ==================== shutdown ====================

    @Nested
    @DisplayName("shutdown")
    class ShutdownTests {

        @Test
        @DisplayName("shutdown with cached clients — closes all and clears")
        void shutdownWithClients() throws Exception {
            McpClient client1 = mock(McpClient.class);
            McpClient client2 = mock(McpClient.class);
            getClientCache().put("http://server1:8080", client1);
            getClientCache().put("http://server2:8080", client2);
            assertEquals(2, manager.getActiveConnectionCount());

            manager.shutdown();

            assertEquals(0, manager.getActiveConnectionCount());
            verify(client1).close();
            verify(client2).close();
        }

        @Test
        @DisplayName("shutdown — error closing one client doesn't prevent others")
        void shutdownWithPartialError() throws Exception {
            McpClient errorClient = mock(McpClient.class);
            doThrow(new RuntimeException("close error")).when(errorClient).close();
            McpClient goodClient = mock(McpClient.class);
            getClientCache().put("http://error-server:8080", errorClient);
            getClientCache().put("http://good-server:8080", goodClient);

            assertDoesNotThrow(() -> manager.shutdown());
            assertEquals(0, manager.getActiveConnectionCount());
        }
    }

    // ==================== discoverTools — name fallback branches
    // ====================

    @Nested
    @DisplayName("discoverTools — server name fallback")
    class DiscoverToolsNameFallbackTests {

        @Test
        @DisplayName("server with null name — falls back to URL in logging")
        void nullNameFallsBackToUrl() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName(null); // null name — triggers fallback branch
            config.setTransport("http");
            config.setTimeoutMs(500L);

            // This will fail to connect but exercises the name==null branch
            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("server with explicit name — uses name in logging")
        void explicitNameUsed() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName("my-server");
            config.setTransport("http");
            config.setTimeoutMs(500L);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
        }

        @Test
        @DisplayName("server with null timeoutMs — uses default 30000")
        void nullTimeoutUsesDefault() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName("timeout-test");
            config.setTransport("http");
            config.setTimeoutMs(null); // null timeout — triggers default branch

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
        }
    }

    // ==================== discoverTools — API key branches ====================

    @Nested
    @DisplayName("discoverTools — API key resolution")
    class DiscoverToolsApiKeyTests {

        @Test
        @DisplayName("non-empty API key — resolves through global var and secret resolver")
        void apiKeyResolved() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName("api-key-test");
            config.setTransport("http");
            config.setTimeoutMs(500L);
            config.setApiKey("{{vault.my-key}}");

            doReturn("vault-resolved").when(globalVariableResolver).resolveValue("{{vault.my-key}}");
            doReturn("final-key").when(secretResolver).resolveValue("vault-resolved");

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);

            verify(globalVariableResolver).resolveValue("{{vault.my-key}}");
            verify(secretResolver).resolveValue("vault-resolved");
        }

        @Test
        @DisplayName("null API key — skips resolution")
        void nullApiKeySkipsResolution() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName("no-key-test");
            config.setTransport("http");
            config.setTimeoutMs(500L);
            config.setApiKey(null);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);

            verifyNoInteractions(globalVariableResolver);
            verifyNoInteractions(secretResolver);
        }

        @Test
        @DisplayName("empty API key — skips resolution")
        void emptyApiKeySkipsResolution() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-xyz-test:9999/mcp");
            config.setName("empty-key-test");
            config.setTransport("http");
            config.setTimeoutMs(500L);
            config.setApiKey("");

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);

            verifyNoInteractions(globalVariableResolver);
            verifyNoInteractions(secretResolver);
        }
    }

    // ==================== discoverTools — multiple servers ====================

    @Nested
    @DisplayName("discoverTools — multiple servers")
    class DiscoverToolsMultipleServersTests {

        @Test
        @DisplayName("mix of valid URL and empty URL — skips empty, processes valid")
        void mixedServers() {
            var emptyConfig = new McpServerConfig();
            emptyConfig.setUrl("");
            emptyConfig.setName("empty");

            var validConfig = new McpServerConfig();
            validConfig.setUrl("http://unreachable-xyz-test:9999/mcp");
            validConfig.setName("valid");
            validConfig.setTransport("http");
            validConfig.setTimeoutMs(500L);

            var result = manager.discoverTools(List.of(emptyConfig, validConfig));
            assertNotNull(result);
        }

        @Test
        @DisplayName("all servers with empty URLs — returns empty result")
        void allEmptyUrls() {
            var config1 = new McpServerConfig();
            config1.setUrl("");
            var config2 = new McpServerConfig();
            config2.setUrl(null);

            var result = manager.discoverTools(List.of(config1, config2));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }
    }

    // ==================== McpToolsResult record ====================

    @Nested
    @DisplayName("McpToolsResult record")
    class McpToolsResultTests {

        @Test
        @DisplayName("record accessors work correctly")
        void recordAccessors() {
            var result = new McpToolProviderManager.McpToolsResult(List.of(), Map.of());
            assertNotNull(result.toolSpecs());
            assertNotNull(result.executors());
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }
    }
}
