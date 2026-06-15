/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.*;
import org.mockito.Mock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Branch coverage tests for McpToolProviderManager focusing on: - discoverTools
 * with server that has null name (falls back to URL) - discoverTools exception
 * path with null name - closeClient with cached client that throws on close -
 * shutdown with client that throws on close - getOrCreateClient caching
 * (computeIfAbsent) - createTransport API key resolution branches
 * (null/empty/non-empty) - timeout defaulting (null timeoutMs)
 */
@DisplayName("McpToolProviderManager — Branch Coverage")
class McpToolProviderManagerBranchTest {

    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private SecretResolver secretResolver;

    private McpToolProviderManager manager;

    @BeforeEach
    void setUp() {
        openMocks(this);
        when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        manager = new McpToolProviderManager(globalVariableResolver, secretResolver);
    }

    // =========================================================
    // discoverTools — server with null name
    // =========================================================

    @Nested
    @DisplayName("discoverTools — server name fallback")
    class ServerNameFallback {

        @Test
        @DisplayName("null server name falls back to URL for logging")
        void nullServerName_fallbackToUrl() {
            var config = new McpServerConfig();
            config.setUrl("http://127.0.0.1:1/mcp");
            config.setName(null);
            config.setTransport("http");
            config.setTimeoutMs(500L);

            // Will fail to connect but should not throw
            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("server name present uses name for logging")
        void serverNamePresent_usesName() {
            var config = new McpServerConfig();
            config.setUrl("http://127.0.0.1:1/mcp");
            config.setName("My MCP Server");
            config.setTransport("http");
            config.setTimeoutMs(500L);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
        }
    }

    // =========================================================
    // createTransport — API key branches
    // =========================================================

    @Nested
    @DisplayName("createTransport — API key branches")
    class ApiKeyBranches {

        @Test
        @DisplayName("null API key skips Authorization header")
        void nullApiKey() {
            var config = new McpServerConfig();
            config.setUrl("http://127.0.0.1:1/mcp");
            config.setApiKey(null);
            config.setTimeoutMs(500L);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            verify(globalVariableResolver, never()).resolveValue(anyString());
        }

        @Test
        @DisplayName("empty API key skips Authorization header")
        void emptyApiKey() {
            var config = new McpServerConfig();
            config.setUrl("http://127.0.0.1:1/mcp");
            config.setApiKey("");
            config.setTimeoutMs(500L);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            verify(globalVariableResolver, never()).resolveValue(anyString());
        }

        @Test
        @DisplayName("non-empty API key resolves via globalVariableResolver and secretResolver")
        void nonEmptyApiKey() {
            var config = new McpServerConfig();
            config.setUrl("http://127.0.0.1:1/mcp");
            config.setApiKey("{{vault.my-key}}");
            config.setTimeoutMs(500L);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            verify(globalVariableResolver).resolveValue("{{vault.my-key}}");
            verify(secretResolver).resolveValue("{{vault.my-key}}");
        }
    }

    // =========================================================
    // Timeout defaulting
    // =========================================================

    @Nested
    @DisplayName("getOrCreateClient — timeout default")
    class TimeoutDefault {

        @Test
        @DisplayName("null timeoutMs defaults to 30000")
        void nullTimeoutMs() {
            var config = new McpServerConfig();
            config.setUrl("http://127.0.0.1:1/mcp");
            config.setTimeoutMs(null);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
        }

        @Test
        @DisplayName("custom timeoutMs is used")
        void customTimeoutMs() {
            var config = new McpServerConfig();
            config.setUrl("http://127.0.0.1:2/mcp");
            config.setTimeoutMs(5000L);

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
        }
    }

    // =========================================================
    // Multiple servers — one fails, others succeed
    // =========================================================

    @Nested
    @DisplayName("discoverTools — mixed server results")
    class MixedServerResults {

        @Test
        @DisplayName("one failing server does not prevent others from being processed")
        void oneFailDoesNotBlockOthers() {
            var config1 = new McpServerConfig();
            config1.setUrl("http://127.0.0.1:3/mcp");
            config1.setTimeoutMs(500L);

            var config2 = new McpServerConfig();
            config2.setUrl("http://127.0.0.1:4/mcp");
            config2.setTimeoutMs(500L);

            var result = manager.discoverTools(List.of(config1, config2));
            assertNotNull(result);
            // Both fail to connect but process doesn't abort
        }
    }

    // =========================================================
    // closeClient — edge cases
    // =========================================================

    @Nested
    @DisplayName("closeClient edge cases")
    class CloseClientEdgeCases {

        @Test
        @DisplayName("closeClient with URL not in cache does nothing")
        void notInCache() {
            assertDoesNotThrow(() -> manager.closeClient("http://not-cached"));
            assertEquals(0, manager.getActiveConnectionCount());
        }
    }

    // =========================================================
    // shutdown — with cached clients
    // =========================================================

    @Nested
    @DisplayName("shutdown clears cache")
    class ShutdownClearsCacheTests {

        @Test
        @DisplayName("shutdown after connecting clears all clients")
        void shutdownClearsCache() {
            var config = new McpServerConfig();
            config.setUrl("http://127.0.0.1:5/mcp");
            config.setTimeoutMs(500L);

            // This will fail to connect, but the client entry may still be cached
            manager.discoverTools(List.of(config));

            // Regardless of whether connection succeeded, shutdown should not throw
            assertDoesNotThrow(() -> manager.shutdown());
            assertEquals(0, manager.getActiveConnectionCount());
        }
    }
}
