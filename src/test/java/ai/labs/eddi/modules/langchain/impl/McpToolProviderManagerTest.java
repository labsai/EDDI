package ai.labs.eddi.modules.langchain.impl;

import ai.labs.eddi.modules.langchain.model.LangChainConfiguration.McpServerConfig;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for McpToolProviderManager — config parsing, caching, and lifecycle.
 */
class McpToolProviderManagerTest {

    private McpToolProviderManager manager;
    private SecretResolver secretResolver;

    @BeforeEach
    void setUp() {
        secretResolver = mock(SecretResolver.class);
        when(secretResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        manager = new McpToolProviderManager(secretResolver);
    }

    @Test
    @DisplayName("discoverTools should return empty result for null mcpServers")
    void testDiscoverTools_NullServers() {
        var result = manager.discoverTools(null);

        assertNotNull(result);
        assertTrue(result.toolSpecs().isEmpty());
        assertTrue(result.executors().isEmpty());
    }

    @Test
    @DisplayName("discoverTools should return empty result for empty mcpServers list")
    void testDiscoverTools_EmptyServers() {
        var result = manager.discoverTools(List.of());

        assertNotNull(result);
        assertTrue(result.toolSpecs().isEmpty());
        assertTrue(result.executors().isEmpty());
    }

    @Test
    @DisplayName("discoverTools should skip servers with empty URL")
    void testDiscoverTools_EmptyUrl() {
        var config = new McpServerConfig();
        config.setUrl("");
        config.setName("empty-url-server");

        var result = manager.discoverTools(List.of(config));

        assertNotNull(result);
        assertTrue(result.toolSpecs().isEmpty());
    }

    @Test
    @DisplayName("discoverTools should skip servers with null URL")
    void testDiscoverTools_NullUrl() {
        var config = new McpServerConfig();
        config.setName("null-url-server");
        // url is null by default

        var result = manager.discoverTools(List.of(config));

        assertNotNull(result);
        assertTrue(result.toolSpecs().isEmpty());
    }

    @Test
    @DisplayName("discoverTools should handle unreachable server gracefully")
    void testDiscoverTools_UnreachableServer() {
        var config = new McpServerConfig();
        config.setUrl("http://localhost:99999/unreachable-mcp"); // Invalid port
        config.setName("bad-server");
        config.setTimeoutMs(1000L);

        // Should NOT throw — just log a warning and return empty
        var result = manager.discoverTools(List.of(config));

        assertNotNull(result);
        // May or may not be empty depending on how quickly the connection fails,
        // but it should not throw an exception
    }

    @Test
    @DisplayName("getActiveConnectionCount should start at zero")
    void testActiveConnectionCount_StartsAtZero() {
        assertEquals(0, manager.getActiveConnectionCount());
    }

    @Test
    @DisplayName("shutdown should clear all connections")
    void testShutdown_ClearsCache() {
        // Start with no connections
        assertEquals(0, manager.getActiveConnectionCount());

        // Shutdown should be idempotent
        manager.shutdown();
        assertEquals(0, manager.getActiveConnectionCount());
    }

    @Test
    @DisplayName("closeClient should handle non-existent URL gracefully")
    void testCloseClient_NonExistentUrl() {
        // Should not throw
        assertDoesNotThrow(() -> manager.closeClient("http://nowhere:1234/mcp"));
        assertEquals(0, manager.getActiveConnectionCount());
    }

    @Test
    @DisplayName("discoverTools should resolve vault references in API key")
    void testDiscoverTools_VaultRefResolution() {
        var config = new McpServerConfig();
        config.setUrl("http://localhost:99999/mcp-vault-test");
        config.setApiKey("${vault:my-secret}");
        config.setTimeoutMs(1000L);

        // The discoverTools call will fail to connect, but we can verify
        // the secret resolver was called
        manager.discoverTools(List.of(config));

        verify(secretResolver).resolveValue("${vault:my-secret}");
    }
}
