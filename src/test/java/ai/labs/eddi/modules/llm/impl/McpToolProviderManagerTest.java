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

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("McpToolProviderManager Tests")
class McpToolProviderManagerTest {

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

    @Nested
    @DisplayName("discoverTools")
    class DiscoverToolsTests {

        @Test
        @DisplayName("null mcpServers — returns empty result")
        void nullServers() {
            var result = manager.discoverTools(null);
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
            assertTrue(result.executors().isEmpty());
        }

        @Test
        @DisplayName("empty mcpServers — returns empty result")
        void emptyServers() {
            var result = manager.discoverTools(Collections.emptyList());
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("server with empty URL — skipped with warning")
        void emptyUrl() {
            var config = new McpServerConfig();
            config.setUrl("");
            config.setName("test");

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("server with null URL — skipped")
        void nullUrl() {
            var config = new McpServerConfig();
            config.setUrl(null);
            config.setName("test");

            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
            assertTrue(result.toolSpecs().isEmpty());
        }

        @Test
        @DisplayName("server with unreachable URL — logs warning, continues")
        void unreachableServer() {
            var config = new McpServerConfig();
            config.setUrl("http://unreachable-host-xyz-12345:9999/mcp");
            config.setName("unreachable");
            config.setTransport("http");
            config.setTimeoutMs(1000L);

            // This will fail to connect but should not throw
            var result = manager.discoverTools(List.of(config));
            assertNotNull(result);
        }
    }

    @Nested
    @DisplayName("closeClient")
    class CloseClientTests {

        @Test
        @DisplayName("closing non-existent client — no error")
        void closeNonExistent() {
            assertDoesNotThrow(() -> manager.closeClient("http://no-such-url"));
        }
    }

    @Nested
    @DisplayName("shutdown")
    class ShutdownTests {

        @Test
        @DisplayName("shutdown with no cached clients — no error")
        void shutdownEmpty() {
            assertDoesNotThrow(() -> manager.shutdown());
        }
    }

    @Nested
    @DisplayName("getActiveConnectionCount")
    class ActiveConnectionCountTests {

        @Test
        @DisplayName("initially zero")
        void initiallyZero() {
            assertEquals(0, manager.getActiveConnectionCount());
        }
    }
}
